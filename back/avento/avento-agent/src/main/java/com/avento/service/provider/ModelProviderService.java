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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Configuração de provedor: um provedor ativo por usuário, com o TIPO dirigindo o comportamento.
 *
 * <p>Antes havia uma divisão binária — "servidor do sistema" contra "nuvem pessoal" — com os nomes
 * dos modelos de nuvem escritos no código. Isso não descreve a realidade: um Ollama na máquina da
 * rede, um DGX com endpoint compatível com OpenAI e o Gemini são o mesmo conceito com endereço,
 * formato e chave diferentes. Agora o usuário escolhe o tipo, informa endereço e chave, e o sistema
 * reage: lista os modelos que aquele provedor realmente tem e roteia a conversa para ele.
 *
 * <p>Postgres é a verdade; o Redis só guarda a configuração de sistema herdada.
 */
@Service
public class ModelProviderService {

    private static final Logger logger = LoggerFactory.getLogger(ModelProviderService.class);
    private static final String SYS_KEY = "avento:system:ai_server";

    private final StringRedisTemplate redisTemplate;
    private final String defaultOllamaUrl;
    private final ObjectMapper objectMapper;
    private final ProviderSettingsRepository repository;
    private final SecretCipher cipher;
    private final ProviderModelCatalog modelCatalog;
    private final Map<String, Integer> contextLimitCache = new ConcurrentHashMap<>();

    public ModelProviderService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${spring.ai.ollama.base-url:http://127.0.0.1:11434}") String defaultOllamaUrl,
            ObjectMapper objectMapper,
            ObjectProvider<ProviderSettingsRepository> repositoryProvider,
            ObjectProvider<SecretCipher> cipherProvider,
            ObjectProvider<ProviderModelCatalog> modelCatalogProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.defaultOllamaUrl = defaultOllamaUrl;
        this.objectMapper = objectMapper;
        this.repository = repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
        this.cipher = cipherProvider == null ? null : cipherProvider.getIfAvailable();
        this.modelCatalog = modelCatalogProvider == null ? null : modelCatalogProvider.getIfAvailable();
    }

    // ---------------------------------------------------------------- leitura

    /** Tipo do provedor ativo. Sem configuração, OLLAMA — o modo local. */
    public ProviderKind activeKind(UUID userId) {
        ProviderSettings stored = findStored(userId);
        return stored == null ? ProviderKind.OLLAMA : ProviderKind.from(stored.getProviderKind());
    }

    /** Endereço do provedor ativo, já com o padrão do tipo quando não informado. */
    public String activeBaseUrl(UUID userId) {
        ProviderSettings stored = findStored(userId);
        if (stored != null
                && stored.getBaseUrl() != null
                && !stored.getBaseUrl().isBlank()) {
            return stored.getBaseUrl();
        }
        ProviderKind kind = stored == null ? ProviderKind.OLLAMA : ProviderKind.from(stored.getProviderKind());
        return kind == ProviderKind.OLLAMA ? defaultOllamaUrl : kind.defaultBaseUrl();
    }

    /**
     * Modelo escolhido no provedor ativo.
     *
     * <p>Num provedor remoto sem escolha, devolve vazio de proposito: cair no padrao LOCAL mandaria
     * um nome de modelo do Ollama para o Gemini, que responde 404 sem explicar.
     */
    public String activeModelName(UUID userId) {
        ProviderSettings stored = findStored(userId);
        if (stored != null
                && stored.getCloudModel() != null
                && !stored.getCloudModel().isBlank()) {
            return stored.getCloudModel();
        }
        return activeKind(userId) == ProviderKind.OLLAMA ? readSystemField("defaultModel", "qwen3.5:9b") : "";
    }

    /**
     * Modelo de visao configurado. Vazio quando nao ha escolha — quem chama decide o padrao.
     *
     * <p>Estes acessores existem para tirar a configuracao de provedor dos arquivos: quem usa o
     * produto nao deveria editar YAML para trocar de modelo.
     */
    public String activeVisionModel(UUID userId) {
        ProviderSettings stored = findStored(userId);
        return stored == null ? "" : blankToEmpty(stored.getVisionModel());
    }

    /** Modelo de geracao de imagem configurado, ou vazio. */
    public String activeImageModel(UUID userId) {
        ProviderSettings stored = findStored(userId);
        return stored == null ? "" : blankToEmpty(stored.getImageModel());
    }

    /** Modelo do planejador; vazio significa "use o de conversa". */
    public String activePlannerModel(UUID userId) {
        ProviderSettings stored = findStored(userId);
        return stored == null ? "" : blankToEmpty(stored.getPlannerModel());
    }

    /** Modelo de embedding configurado, ou vazio. */
    public String activeEmbeddingModel(UUID userId) {
        ProviderSettings stored = findStored(userId);
        return stored == null ? "" : blankToEmpty(stored.getEmbeddingModel());
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    /** Chave crua, para uso SERVIDOR-A-SERVIDOR apenas. Nunca volta ao cliente nem para log. */
    public String rawApiKey(UUID userId) {
        return decryptedKey(findStored(userId));
    }

    /**
     * Verdadeiro quando o provedor ativo não é o local E está pronto para uso.
     *
     * <p>Um tipo que exige chave sem chave configurada não conta como pronto: rotear para ele daria
     * erro de autenticação em toda mensagem, e o usuário não saberia que faltou a chave.
     */
    public boolean remoteProviderReady(UUID userId) {
        ProviderKind kind = activeKind(userId);
        if (kind == ProviderKind.OLLAMA) {
            return false;
        }
        if (kind.requiresApiKey() && rawApiKey(userId).isBlank()) {
            return false;
        }
        // Sem modelo escolhido nao ha o que chamar: rotear daria 404 na primeira mensagem, e o
        // usuario nao saberia que faltou escolher.
        return !activeModelName(userId).isBlank();
    }

    /** Rótulo para mensagens ao usuário, ex.: {@code GEMINI (gemini-2.5-flash)}. */
    public String activeProviderLabel(UUID userId) {
        String model = activeModelName(userId);
        String kind = activeKind(userId).name();
        return model == null || model.isBlank() ? kind : kind + " (" + model + ")";
    }

    public ProviderSettingsResponse getSettings(UUID userId) {
        ProviderSettings stored = findStored(userId);
        ProviderKind kind = stored == null ? ProviderKind.OLLAMA : ProviderKind.from(stored.getProviderKind());
        String baseUrl = activeBaseUrl(userId);
        String model = activeModelName(userId);
        String plainKey = decryptedKey(stored);
        String maskedKey = plainKey.isBlank() ? "" : maskApiKey(plainKey);
        boolean remote = kind != ProviderKind.OLLAMA;

        return new ProviderSettingsResponse(
                kind.name(),
                baseUrl,
                model,
                maskedKey,
                activeVisionModel(userId),
                activeImageModel(userId),
                activePlannerModel(userId),
                activeEmbeddingModel(userId),
                // Campos herdados, mantidos enquanto a tela migra: descrevem a MESMA configuracao,
                // nao dois provedores paralelos como antes.
                baseUrl,
                kind.name(),
                model,
                remote,
                kind.name(),
                maskedKey,
                model);
    }

    // ---------------------------------------------------------------- escrita

    public ProviderSettingsResponse updateSettings(UUID userId, ProviderSettingsUpdateRequest request) {
        if (request == null || userId == null || repository == null) {
            return getSettings(userId);
        }
        try {
            ProviderSettings stored = repository.findById(userId).orElseGet(() -> new ProviderSettings(userId));

            String kind = firstPresent(request.providerKind(), request.systemServerType(), legacyKind(request));
            if (kind != null) {
                stored.setProviderKind(ProviderKind.from(kind).name());
            }
            String baseUrl = firstPresent(request.baseUrl(), request.systemServerUrl());
            if (baseUrl != null) {
                stored.setBaseUrl(baseUrl.trim());
            }
            String model =
                    firstPresent(request.selectedModel(), request.personalCloudModel(), request.systemDefaultModel());
            if (model != null) {
                stored.setCloudModel(model.trim());
            }
            if (request.visionModel() != null) {
                stored.setVisionModel(request.visionModel().trim());
            }
            if (request.imageModel() != null) {
                stored.setImageModel(request.imageModel().trim());
            }
            if (request.plannerModel() != null) {
                stored.setPlannerModel(request.plannerModel().trim());
            }
            if (request.embeddingModel() != null) {
                stored.setEmbeddingModel(request.embeddingModel().trim());
            }
            String apiKey = firstPresent(request.apiKey(), request.personalCloudApiKey());
            if (isRealApiKey(apiKey) && cipher != null) {
                stored.setCloudApiKeyEncrypted(cipher.encrypt(apiKey.trim()));
            }
            // Derivados, nao fonte de verdade: existem para a tela antiga continuar lendo.
            stored.setUsePersonalCloud(ProviderKind.from(stored.getProviderKind()) != ProviderKind.OLLAMA);
            stored.setCloudProvider(stored.getProviderKind());
            repository.save(stored);
        } catch (RuntimeException exception) {
            // Nunca inclui a chave na mensagem.
            logger.warn(
                    "Falha ao gravar a configuracao de provedor: {}",
                    exception.getClass().getSimpleName());
        }
        return getSettings(userId);
    }

    /**
     * Desconecta o provedor remoto: apaga a chave e volta para o local.
     *
     * <p>Existe para a tela ter o par completo — conectar e desconectar. Sem isso, tirar uma chave
     * exigiria mexer no banco, e quem usa o produto nao deveria precisar disso.
     */
    public ProviderSettingsResponse disconnect(UUID userId) {
        if (userId == null || repository == null) {
            return getSettings(userId);
        }
        try {
            repository.findById(userId).ifPresent(stored -> {
                stored.setProviderKind(ProviderKind.OLLAMA.name());
                stored.setCloudApiKeyEncrypted(null);
                stored.setCloudModel(null);
                stored.setVisionModel(null);
                stored.setImageModel(null);
                stored.setBaseUrl(null);
                stored.setUsePersonalCloud(false);
                stored.setCloudProvider(ProviderKind.OLLAMA.name());
                repository.save(stored);
            });
            contextLimitCache.clear();
        } catch (RuntimeException exception) {
            logger.warn(
                    "Falha ao desconectar o provedor: {}", exception.getClass().getSimpleName());
        }
        return getSettings(userId);
    }

    /** Falso para vazio e para o valor mascarado que a própria tela devolve. */
    static boolean isRealApiKey(String candidate) {
        return candidate != null && !candidate.isBlank() && !candidate.contains("•");
    }

    // ------------------------------------------------------------------ modelos

    /**
     * Modelos que o provedor ativo realmente oferece, consultando a API dele.
     *
     * <p>Lista escrita no código envelhece e mostra modelo que talvez não exista na conta — o
     * usuário escolhe um nome que a API vai recusar, sem entender por quê.
     */
    public JsonNode listAvailableModels(UUID userId) {
        ProviderKind kind = activeKind(userId);
        List<String> names = modelCatalog == null
                ? List.of()
                : modelCatalog.listModels(kind, activeBaseUrl(userId), rawApiKey(userId));

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode data = root.putArray("data");
        for (String name : names) {
            data.add(createModelNode(name, name));
        }
        if (data.isEmpty()) {
            // Sem resposta do provedor, oferece ao menos o modelo configurado — e diz que nao foi
            // confirmado, para o usuario perceber que a consulta falhou em vez de achar que nao ha
            // modelos.
            String configured = activeModelName(userId);
            if (configured != null && !configured.isBlank()) {
                data.add(createModelNode(configured, configured + " (nao confirmado pelo provedor)"));
            }
        }
        return root;
    }

    /** Modelos de imagem do provedor ativo. Vazio no modo local, onde quem responde e o ComfyUI. */
    public List<String> listImageModelNames(UUID userId) {
        if (modelCatalog == null) {
            return List.of();
        }
        return modelCatalog.listImageModels(activeKind(userId), activeBaseUrl(userId), rawApiKey(userId));
    }

    /**
     * Contexto que o provedor declara para o modelo ativo, em tokens. Zero quando nao se sabe.
     *
     * <p>Cache simples por (tipo, modelo): a resposta nao muda enquanto a escolha nao muda, e sem
     * isso seria uma chamada de rede por rodada.
     */
    public int activeContextLimit(UUID userId) {
        if (modelCatalog == null) {
            return 0;
        }
        ProviderKind kind = activeKind(userId);
        String model = activeModelName(userId);
        String cacheKey = kind + ":" + model;
        Integer cached = contextLimitCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        int limit = modelCatalog.contextLimit(kind, activeBaseUrl(userId), rawApiKey(userId), model);
        contextLimitCache.put(cacheKey, limit);
        return limit;
    }

    // ------------------------------------------------------------------ conexao

    /**
     * Testa a configuração listando modelos no provedor — se a listagem funciona, endereço, formato
     * e chave estão certos.
     *
     * <p>A versão anterior mandava a chave na query string ({@code ?key=...}), que entra em log de
     * proxy e de servidor. O catálogo usa cabeçalho.
     */
    public ProviderTestResponse testConnection(ProviderTestRequest request) {
        long start = System.currentTimeMillis();
        if (request == null) {
            return new ProviderTestResponse(false, "Parametros de teste invalidos.", 0);
        }
        ProviderKind kind = ProviderKind.from(request.serverType());
        String baseUrl = request.serverUrl() == null || request.serverUrl().isBlank()
                ? kind.defaultBaseUrl()
                : request.serverUrl().trim();

        if (kind.requiresApiKey()
                && (request.apiKey() == null || request.apiKey().isBlank())) {
            return new ProviderTestResponse(false, "Chave de API nao informada para " + kind + ".", 0);
        }

        List<String> models =
                modelCatalog == null ? List.of() : modelCatalog.listModels(kind, baseUrl, request.apiKey());
        long latency = System.currentTimeMillis() - start;
        if (models.isEmpty()) {
            return new ProviderTestResponse(
                    false, "Nao foi possivel listar modelos em " + kind + ". Verifique endereco e chave.", latency);
        }
        return new ProviderTestResponse(
                true, "Conectado a " + kind + ": " + models.size() + " modelo(s) (" + latency + "ms)", latency, models);
    }

    // --------------------------------------------------- compatibilidade herdada

    public boolean cloudProviderSelected(UUID userId) {
        return remoteProviderReady(userId);
    }

    public String selectedCloudProviderName(UUID userId) {
        return remoteProviderReady(userId) ? activeProviderLabel(userId) : "";
    }

    public String rawCloudApiKey(UUID userId) {
        return rawApiKey(userId);
    }

    public String cloudModelName(UUID userId) {
        return activeModelName(userId);
    }

    public String resolveActiveModelUrl(UUID userId) {
        return activeBaseUrl(userId);
    }

    public String resolveActiveModelName(UUID userId) {
        return activeModelName(userId);
    }

    // ------------------------------------------------------------------ internos

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

    /** Deriva o tipo do campo antigo, que só distinguia nuvem de local. */
    private String legacyKind(ProviderSettingsUpdateRequest request) {
        if (Boolean.TRUE.equals(request.usePersonalCloud()) && request.personalCloudProvider() != null) {
            return request.personalCloudProvider();
        }
        if (Boolean.FALSE.equals(request.usePersonalCloud())) {
            return ProviderKind.OLLAMA.name();
        }
        return null;
    }

    private static String firstPresent(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
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
