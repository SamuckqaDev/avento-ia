package com.avento.service.provider;

import com.avento.api.dto.ProviderSettingsResponse;
import com.avento.api.dto.ProviderSettingsUpdateRequest;
import com.avento.api.dto.ProviderTestRequest;
import com.avento.api.dto.ProviderTestResponse;
import com.avento.model.ProviderSettings;
import com.avento.repository.ProviderSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ModelProviderService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ModelProviderService.class);

    private static final String SYS_KEY = "avento:system:ai_server";

    private final StringRedisTemplate redisTemplate;
    private final String defaultOllamaUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    // Postgres e a verdade; o Redis fica como cache na frente. A chave de API vivia so no Redis, que
    // nao persiste — o usuario tinha de reconfigura-la a cada restart do container.
    private final ProviderSettingsRepository repository;
    private final SecretCipher cipher;

    public ModelProviderService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${spring.ai.ollama.base-url:http://127.0.0.1:11434}") String defaultOllamaUrl,
            ObjectMapper objectMapper,
            ObjectProvider<ProviderSettingsRepository> repositoryProvider,
            ObjectProvider<SecretCipher> cipherProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.defaultOllamaUrl = defaultOllamaUrl;
        this.objectMapper = objectMapper;
        this.repository = repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
        this.cipher = cipherProvider == null ? null : cipherProvider.getIfAvailable();
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public ProviderSettingsResponse getSettings(UUID userId) {
        String systemUrl = readSystemField("serverUrl", defaultOllamaUrl);
        String systemType = readSystemField("serverType", "OLLAMA");
        String systemModel = readSystemField("defaultModel", "qwen3.5:9b");

        boolean usePersonalCloud = false;
        String cloudProvider = "GEMINI";
        String cloudApiKeyMasked = "";
        String cloudModel = "gemini-2.5-flash";

        ProviderSettings stored = findStored(userId);
        if (stored != null) {
            usePersonalCloud = stored.isUsePersonalCloud();
            if (stored.getCloudProvider() != null && !stored.getCloudProvider().isBlank()) {
                cloudProvider = stored.getCloudProvider();
            }
            if (stored.getCloudModel() != null && !stored.getCloudModel().isBlank()) {
                cloudModel = stored.getCloudModel();
            }
            String plainKey = decryptedKey(stored);
            if (!plainKey.isBlank()) {
                cloudApiKeyMasked = maskApiKey(plainKey);
            }
        }

        return new ProviderSettingsResponse(
                systemUrl, systemType, systemModel, usePersonalCloud, cloudProvider, cloudApiKeyMasked, cloudModel);
    }

    public ProviderSettingsResponse updateSettings(UUID userId, ProviderSettingsUpdateRequest request) {
        if (request == null) {
            return getSettings(userId);
        }

        if (redisTemplate != null) {
            // Update shared system LAN server if provided
            if (request.systemServerUrl() != null && !request.systemServerUrl().isBlank()) {
                redisTemplate
                        .opsForHash()
                        .put(SYS_KEY, "serverUrl", request.systemServerUrl().trim());
            }
            if (request.systemServerType() != null
                    && !request.systemServerType().isBlank()) {
                redisTemplate
                        .opsForHash()
                        .put(SYS_KEY, "serverType", request.systemServerType().trim());
            }
            if (request.systemDefaultModel() != null
                    && !request.systemDefaultModel().isBlank()) {
                redisTemplate
                        .opsForHash()
                        .put(
                                SYS_KEY,
                                "defaultModel",
                                request.systemDefaultModel().trim());
            }
        }

        persistCloudSettings(userId, request);

        return getSettings(userId);
    }

    public ProviderTestResponse testConnection(ProviderTestRequest request) {
        if (request == null) {
            return new ProviderTestResponse(false, "Parâmetros de teste inválidos.", 0);
        }

        long start = System.currentTimeMillis();

        if ("SYSTEM_LAN".equalsIgnoreCase(request.targetType())) {
            String url = request.serverUrl() != null && !request.serverUrl().isBlank()
                    ? request.serverUrl().trim()
                    : defaultOllamaUrl;
            return testLanServer(url, request.serverType(), start);
        } else if ("PERSONAL_CLOUD".equalsIgnoreCase(request.targetType())) {
            return testCloudApi(request.serverUrl(), request.apiKey(), request.modelName(), start);
        }

        return new ProviderTestResponse(false, "Tipo de destino não reconhecido.", 0);
    }

    public JsonNode listAvailableModels(UUID userId) {
        ProviderSettingsResponse settings = getSettings(userId);
        if (settings.usePersonalCloud() && !settings.personalCloudApiKeyMasked().isBlank()) {
            ArrayNode models = objectMapper.createArrayNode();
            if ("GEMINI".equalsIgnoreCase(settings.personalCloudProvider())) {
                models.add(createModelNode("gemini-2.5-flash", "Google Gemini 2.5 Flash (Cloud)"));
                models.add(createModelNode("gemini-2.5-pro", "Google Gemini 2.5 Pro (Cloud)"));
                models.add(createModelNode("gemini-1.5-pro", "Google Gemini 1.5 Pro (Cloud)"));
            } else {
                models.add(createModelNode("gpt-4o", "OpenAI GPT-4o (Cloud)"));
                models.add(createModelNode("gpt-4o-mini", "OpenAI GPT-4o Mini (Cloud)"));
                models.add(createModelNode("o3-mini", "OpenAI o3 Mini (Cloud)"));
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.set("data", models);
            return root;
        }

        String activeUrl = settings.systemServerUrl().replaceAll("/+$", "");
        try {
            String endpoint = "OPENAI_COMPATIBLE".equalsIgnoreCase(settings.systemServerType())
                    ? activeUrl + "/v1/models"
                    : activeUrl + "/api/tags";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .GET()
                    .timeout(Duration.ofSeconds(4))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readTree(response.body());
            }
        } catch (Exception exception) {
            // Fallback
        }

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode fallback = objectMapper.createArrayNode();
        fallback.add(
                createModelNode(settings.systemDefaultModel(), settings.systemDefaultModel() + " (" + activeUrl + ")"));
        root.set("data", fallback);
        return root;
    }

    /**
     * Verdadeiro quando o usuario escolheu um provedor de nuvem na tela de configuracao.
     *
     * <p>Existe para o agente poder AVISAR: o fluxo de chat fala direto com o Ollama
     * ({@code /api/chat}, corpo no formato nativo), entao escolher Gemini nao muda para onde a
     * requisicao vai — e sem aviso o usuario recebe o modelo local achando que falou com a nuvem.
     */
    private ProviderSettings findStored(UUID userId) {
        if (userId == null || repository == null) {
            return null;
        }
        try {
            return repository.findById(userId).orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String decryptedKey(ProviderSettings stored) {
        if (stored == null || cipher == null) {
            return "";
        }
        return cipher.decrypt(stored.getCloudApiKeyEncrypted());
    }

    /**
     * Grava a configuracao de nuvem no banco. Campo nulo significa "nao mexi": manter o que estava e
     * o que permite salvar so o modelo sem apagar a chave.
     *
     * <p>O valor mascarado que a tela devolve (com marcadores) nunca e regravado como chave — senao
     * um "salvar" sem editar a chave a destruiria.
     */
    private void persistCloudSettings(UUID userId, ProviderSettingsUpdateRequest request) {
        if (userId == null || repository == null || request == null) {
            return;
        }
        try {
            ProviderSettings stored = repository.findById(userId).orElseGet(() -> new ProviderSettings(userId));
            if (request.usePersonalCloud() != null) {
                stored.setUsePersonalCloud(request.usePersonalCloud());
            }
            if (request.personalCloudProvider() != null) {
                stored.setCloudProvider(request.personalCloudProvider().trim());
            }
            if (request.personalCloudModel() != null) {
                stored.setCloudModel(request.personalCloudModel().trim());
            }
            if (isRealApiKey(request.personalCloudApiKey()) && cipher != null) {
                stored.setCloudApiKeyEncrypted(
                        cipher.encrypt(request.personalCloudApiKey().trim()));
            }
            repository.save(stored);
        } catch (RuntimeException exception) {
            // Nunca inclui a chave na mensagem.
            logger.warn(
                    "Falha ao gravar a configuracao de provedor: {}",
                    exception.getClass().getSimpleName());
        }
    }

    /** Falso para vazio e para o valor mascarado que a propria tela devolve. */
    static boolean isRealApiKey(String candidate) {
        return candidate != null && !candidate.isBlank() && !candidate.contains("\u2022");
    }

    public boolean cloudProviderSelected(UUID userId) {
        if (userId == null) {
            return false;
        }
        try {
            ProviderSettingsResponse settings = getSettings(userId);
            return settings.usePersonalCloud()
                    && settings.personalCloudApiKeyMasked() != null
                    && !settings.personalCloudApiKeyMasked().isBlank();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Nome do provedor de nuvem escolhido (ex.: GEMINI), vazio quando nao ha. */
    public String selectedCloudProviderName(UUID userId) {
        if (!cloudProviderSelected(userId)) {
            return "";
        }
        ProviderSettingsResponse settings = getSettings(userId);
        String provider = settings.personalCloudProvider();
        String model = settings.personalCloudModel();
        return model == null || model.isBlank() ? provider : provider + " (" + model + ")";
    }

    /**
     * Chave crua do provedor de nuvem, para uso SERVIDOR-A-SERVIDOR apenas.
     *
     * <p>O DTO de settings expõe só a versão mascarada, e é assim que deve continuar: esta aqui não
     * pode voltar para o cliente nem entrar em log. Existe porque a chamada ao provedor precisa dela.
     */
    public String rawCloudApiKey(UUID userId) {
        return decryptedKey(findStored(userId));
    }

    /** Modelo de nuvem escolhido, ex.: {@code gemini-2.5-flash}. */
    public String cloudModelName(UUID userId) {
        return getSettings(userId).personalCloudModel();
    }

    public String resolveActiveModelUrl(UUID userId) {
        ProviderSettingsResponse settings = getSettings(userId);
        if (settings.usePersonalCloud() && !settings.personalCloudApiKeyMasked().isBlank()) {
            return "https://generativelanguage.googleapis.com";
        }
        return settings.systemServerUrl();
    }

    public String resolveActiveModelName(UUID userId) {
        ProviderSettingsResponse settings = getSettings(userId);
        if (settings.usePersonalCloud() && !settings.personalCloudApiKeyMasked().isBlank()) {
            return settings.personalCloudModel();
        }
        return settings.systemDefaultModel();
    }

    private ProviderTestResponse testLanServer(String baseUrl, String serverType, long startTimeMs) {
        try {
            String endpoint = baseUrl.replaceAll("/+$", "")
                    + ("OPENAI_COMPATIBLE".equalsIgnoreCase(serverType) ? "/v1/models" : "/api/tags");

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .GET()
                    .timeout(Duration.ofSeconds(4))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - startTimeMs;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ProviderTestResponse(
                        true, "Conectado ao servidor da rede local (" + latency + "ms)", latency);
            } else {
                return new ProviderTestResponse(
                        false, "Servidor respondeu com código HTTP " + response.statusCode(), latency);
            }
        } catch (Exception exception) {
            long latency = System.currentTimeMillis() - startTimeMs;
            return new ProviderTestResponse(false, "Falha ao conectar: " + exception.getMessage(), latency);
        }
    }

    private ProviderTestResponse testCloudApi(String provider, String apiKey, String modelName, long startTimeMs) {
        if (apiKey == null || apiKey.isBlank()) {
            return new ProviderTestResponse(false, "Chave de API não informada.", 0);
        }

        try {
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey.trim();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - startTimeMs;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ProviderTestResponse(true, "Chave de API autenticada na Nuvem (" + latency + "ms)", latency);
            } else {
                return new ProviderTestResponse(false, "Chave recusada (HTTP " + response.statusCode() + ")", latency);
            }
        } catch (Exception exception) {
            long latency = System.currentTimeMillis() - startTimeMs;
            return new ProviderTestResponse(
                    false, "Erro de rede ao validar chave Cloud: " + exception.getMessage(), latency);
        }
    }

    private ObjectNode createModelNode(String id, String name) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        node.put("name", name);
        return node;
    }

    private String readSystemField(String field, String fallback) {
        if (redisTemplate == null) {
            return fallback;
        }
        try {
            Object raw = redisTemplate.opsForHash().get(SYS_KEY, field);
            return raw == null ? fallback : raw.toString();
        } catch (Exception exception) {
            return fallback;
        }
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() <= 8) {
            return "••••••••";
        }
        return key.substring(0, 4) + "••••••••" + key.substring(key.length() - 4);
    }
}
