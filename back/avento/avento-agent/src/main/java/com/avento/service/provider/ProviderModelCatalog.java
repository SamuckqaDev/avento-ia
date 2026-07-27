package com.avento.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lista os modelos consultando o provedor de verdade, em vez de manter nomes escritos no código.
 *
 * <p>Lista chumbada envelhece e mostra modelo que talvez não exista na conta de quem usa — o
 * usuário escolhe um nome que a API vai recusar. Cada tipo de provedor tem o seu endpoint e o seu
 * formato de resposta, e é isso que este componente resolve.
 */
@Component
public class ProviderModelCatalog {

    private static final Logger logger = LoggerFactory.getLogger(ProviderModelCatalog.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public ProviderModelCatalog(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Nomes de modelo oferecidos pelo provedor. Lista vazia quando não dá para consultar — quem
     * chama decide o que mostrar, em vez de receber um nome inventado.
     */
    public List<String> listModels(ProviderKind kind, String baseUrl, String apiKey) {
        String base = (baseUrl == null || baseUrl.isBlank() ? kind.defaultBaseUrl() : baseUrl).replaceAll("/+$", "");
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(base + kind.modelsPath()))
                    .GET()
                    .timeout(Duration.ofSeconds(8));
            applyAuth(request, kind, apiKey);

            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("Provedor {} respondeu {} ao listar modelos", kind, response.statusCode());
                return List.of();
            }
            return parseModels(kind, mapper.readTree(response.body()));
        } catch (Exception exception) {
            // Nunca inclui a chave: a mensagem pode ecoar cabecalhos.
            logger.warn(
                    "Falha ao listar modelos do provedor {}: {}",
                    kind,
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * Modelos de GERACAO DE IMAGEM do provedor.
     *
     * <p>Separado da listagem de chat porque o seletor de imagem e outro: com o Gemini ativo, listar
     * os checkpoints do ComfyUI local seria o mesmo erro do seletor de chat — oferecer um nome que
     * o provedor ativo nao conhece.
     */
    public List<String> listImageModels(ProviderKind kind, String baseUrl, String apiKey) {
        if (kind == ProviderKind.OLLAMA) {
            // Imagem local nao sai do Ollama: quem responde e o ComfyUI, tratado noutro caminho.
            return List.of();
        }
        String base = (baseUrl == null || baseUrl.isBlank() ? kind.defaultBaseUrl() : baseUrl).replaceAll("/+$", "");
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(base + kind.modelsPath()))
                    .GET()
                    .timeout(Duration.ofSeconds(8));
            applyAuth(request, kind, apiKey);
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            return parseImageModels(kind, mapper.readTree(response.body()));
        } catch (Exception exception) {
            logger.warn(
                    "Falha ao listar modelos de imagem do provedor {}: {}",
                    kind,
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Imagem tem marcador proprio em cada provedor. */
    static List<String> parseImageModels(ProviderKind kind, JsonNode body) {
        List<String> models = new ArrayList<>();
        if (kind == ProviderKind.GEMINI) {
            for (JsonNode model : body.path("models")) {
                String name = model.path("name").asText("").replaceFirst("^models/", "");
                // Imagen expoe "predict"; os de conversa expoem generateContent.
                if (name.toLowerCase(java.util.Locale.ROOT).contains("imagen") || supportsPredict(model)) {
                    addIfPresent(models, name);
                }
            }
        } else if (kind == ProviderKind.OPENAI_COMPATIBLE) {
            for (JsonNode model : body.path("data")) {
                String id = model.path("id").asText("");
                String lower = id.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("dall-e") || lower.contains("image") || lower.contains("flux")) {
                    addIfPresent(models, id);
                }
            }
        }
        return models;
    }

    private static boolean supportsPredict(JsonNode model) {
        for (JsonNode method : model.path("supportedGenerationMethods")) {
            if ("predict".equals(method.asText(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Limite de contexto que o PROVEDOR declara para o modelo, em tokens. Zero quando nao da para
     * descobrir.
     *
     * <p>Chutar esse numero e o que fazia o Avento truncar demais num modelo de janela grande e
     * estourar num de janela pequena. Os dois lados informam: o Ollama em {@code /api/show}
     * ({@code model_info.*.context_length}) e o Gemini em {@code inputTokenLimit} da propria
     * listagem de modelos.
     */
    public int contextLimit(ProviderKind kind, String baseUrl, String apiKey, String model) {
        if (model == null || model.isBlank()) {
            return 0;
        }
        String base = (baseUrl == null || baseUrl.isBlank() ? kind.defaultBaseUrl() : baseUrl).replaceAll("/+$", "");
        try {
            if (kind == ProviderKind.OLLAMA) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/api/show"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + model + "\"}"))
                        .timeout(Duration.ofSeconds(8))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() / 100 == 2 ? ollamaContextLength(mapper.readTree(response.body())) : 0;
            }

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(base + kind.modelsPath()))
                    .GET()
                    .timeout(Duration.ofSeconds(8));
            applyAuth(request, kind, apiKey);
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2 ? declaredInputLimit(mapper.readTree(response.body()), model) : 0;
        } catch (Exception exception) {
            logger.warn(
                    "Falha ao descobrir o contexto de {} em {}: {}",
                    model,
                    kind,
                    exception.getClass().getSimpleName());
            return 0;
        }
    }

    /** O Ollama prefixa a chave com a familia do modelo (ex.: {@code qwen35.context_length}). */
    static int ollamaContextLength(JsonNode body) {
        JsonNode info = body.path("model_info");
        var fields = info.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().endsWith(".context_length") || "context_length".equals(entry.getKey())) {
                return entry.getValue().asInt(0);
            }
        }
        return 0;
    }

    /** Gemini expoe inputTokenLimit por modelo na mesma listagem. */
    static int declaredInputLimit(JsonNode body, String model) {
        for (JsonNode entry : body.path("models")) {
            String name = entry.path("name").asText("").replaceFirst("^models/", "");
            if (name.equalsIgnoreCase(model)) {
                return entry.path("inputTokenLimit").asInt(0);
            }
        }
        for (JsonNode entry : body.path("data")) {
            if (entry.path("id").asText("").equalsIgnoreCase(model)) {
                return entry.path("context_length")
                        .asInt(entry.path("max_context_length").asInt(0));
            }
        }
        return 0;
    }

    private void applyAuth(HttpRequest.Builder request, ProviderKind kind, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        switch (kind) {
            // Chave em header, nunca em query string: URL entra em log de proxy e de servidor.
            case GEMINI -> request.header("x-goog-api-key", apiKey);
            case ANTHROPIC -> request.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            case OPENAI_COMPATIBLE -> request.header("Authorization", "Bearer " + apiKey);
            case OLLAMA -> {}
        }
    }

    /** Cada provedor devolve o nome num lugar diferente. */
    static List<String> parseModels(ProviderKind kind, JsonNode body) {
        List<String> models = new ArrayList<>();
        switch (kind) {
            case OLLAMA -> {
                for (JsonNode model : body.path("models")) {
                    addIfPresent(models, model.path("name").asText(""));
                }
            }
            case OPENAI_COMPATIBLE, ANTHROPIC -> {
                for (JsonNode model : body.path("data")) {
                    addIfPresent(models, model.path("id").asText(""));
                }
            }
            case GEMINI -> {
                for (JsonNode model : body.path("models")) {
                    // Só os que geram conteúdo: a mesma lista traz modelos de embedding, que não
                    // servem para conversa e apareceriam como opção quebrada no seletor.
                    if (!supportsGeneration(model)) {
                        continue;
                    }
                    // A listagem inclui modelo que a API recusa na hora de usar: o gemini-2.5-flash
                    // aparece aqui e devolve 404 dizendo "no longer available to new users". Oferecer
                    // no seletor um nome que so falha depois e pior que nao oferecer.
                    if (isRetired(model)) {
                        continue;
                    }
                    // Vem como "models/gemini-2.5-flash"; o resto da API espera o nome puro.
                    addIfPresent(models, model.path("name").asText("").replaceFirst("^models/", ""));
                }
            }
        }
        return models;
    }

    /** O Google marca o modelo aposentado na propria descricao antes de recusa-lo. */
    static boolean isRetired(JsonNode model) {
        String description = model.path("description").asText("").toLowerCase(java.util.Locale.ROOT);
        return description.contains("no longer available")
                || description.contains("deprecated")
                || description.contains("has been retired");
    }

    private static boolean supportsGeneration(JsonNode model) {
        JsonNode methods = model.path("supportedGenerationMethods");
        if (!methods.isArray() || methods.isEmpty()) {
            return true; // Sem a informacao, nao filtra.
        }
        for (JsonNode method : methods) {
            String name = method.asText("");
            if ("generateContent".equals(name) || "streamGenerateContent".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void addIfPresent(List<String> models, String name) {
        if (name != null && !name.isBlank()) {
            models.add(name);
        }
    }
}
