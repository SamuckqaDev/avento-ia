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
                    // Vem como "models/gemini-2.5-flash"; o resto da API espera o nome puro.
                    addIfPresent(models, model.path("name").asText("").replaceFirst("^models/", ""));
                }
            }
        }
        return models;
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
