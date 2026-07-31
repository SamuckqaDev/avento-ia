package com.avento.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import java.util.Set;

/**
 * Transporte do Gemini: traduz a requisição canônica do agente e devolve a resposta no formato
 * interno, ferramentas incluídas.
 *
 * <p>É o que permite o Gemini usar arquivo, terminal, MCP e RAG: o laço de rodadas continua o mesmo,
 * montando toolset, pedindo aprovação e executando — só o dialeto da chamada muda aqui.
 *
 * <p>Diferenças traduzidas:
 *
 * <ul>
 *   <li>papel {@code assistant} → {@code model};
 *   <li>prompt de sistema sai das mensagens e vai para {@code systemInstruction};
 *   <li>ferramentas: {@code tools[].function} → {@code tools[0].functionDeclarations[]};
 *   <li>resultado de ferramenta: mensagem {@code role=tool} → {@code functionResponse};
 *   <li>chamada de ferramenta: {@code functionCall} em {@code parts} → {@code tool_calls}.
 * </ul>
 */
@Service
public class GeminiModelTransport implements ModelTransport {

    private static final Logger logger = LoggerFactory.getLogger(GeminiModelTransport.class);

    private final ObjectMapper mapper;
    private final WebClient webClient;
    private final Duration timeout;

    public GeminiModelTransport(
            ObjectMapper mapper, @Value("${avento.provider.gemini.timeout-seconds:300}") long timeoutSeconds) {
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.webClient = ProviderWebClients.streaming();
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.GEMINI;
    }

    @Override
    public Flux<String> stream(ObjectNode canonicalRequest, String baseUrl, String apiKey) {
        String model = canonicalRequest.path("model").asText("");
        ObjectNode geminiRequest = toGeminiRequest(canonicalRequest, mapper);
        String url = (baseUrl == null || baseUrl.isBlank() ? ProviderKind.GEMINI.defaultBaseUrl() : baseUrl)
                        .replaceAll("/+$", "")
                + "/v1beta/models/" + model.trim() + ":streamGenerateContent?alt=sse";

        return webClient
                .post()
                .uri(url)
                // Chave em HEADER, nunca em query string: URL entra em log de proxy e de servidor.
                .header("x-goog-api-key", apiKey == null ? "" : apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(geminiRequest.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(timeout)
                .mapNotNull(event -> toInternalChunk(event, mapper));
    }

    // ------------------------------------------------------------------ ida

    /** Converte a requisição canônica do Avento para o corpo do Gemini. */
    static ObjectNode toGeminiRequest(ObjectNode canonical, ObjectMapper mapper) {
        ObjectNode request = mapper.createObjectNode();
        ArrayNode contents = request.putArray("contents");
        StringBuilder systemText = new StringBuilder();

        for (JsonNode message : canonical.path("messages")) {
            String role = message.path("role").asText("");
            String content = message.path("content").asText("");
            switch (role) {
                case "system" -> {
                    if (!content.isBlank()) {
                        if (systemText.length() > 0) {
                            systemText.append("\n\n");
                        }
                        systemText.append(content);
                    }
                }
                case "user" -> addUserContent(contents, message, content, mapper);
                case "assistant" -> addAssistantContent(contents, message, content, mapper);
                // Resultado de ferramenta: o Gemini espera functionResponse, nao texto solto.
                case "tool" -> addFunctionResponse(contents, message, mapper);
                default -> {}
            }
        }

        if (systemText.length() > 0) {
            request.putObject("systemInstruction").putArray("parts").addObject().put("text", systemText.toString());
        }

        ArrayNode declarations = toFunctionDeclarations(canonical.path("tools"), mapper);
        if (!declarations.isEmpty()) {
            request.putArray("tools").addObject().set("functionDeclarations", declarations);
        }

        JsonNode options = canonical.path("options");
        if (options.isObject()) {
            ObjectNode generation = request.putObject("generationConfig");
            if (options.has("temperature"))
                generation.put("temperature", options.path("temperature").asDouble());
            if (options.has("top_p"))
                generation.put("topP", options.path("top_p").asDouble());
            if (options.has("top_k"))
                generation.put("topK", options.path("top_k").asInt());
            if (options.has("num_predict")) {
                generation.put("maxOutputTokens", options.path("num_predict").asInt());
            }
        }
        return request;
    }

    /** {@code tools[].function} do formato interno vira {@code functionDeclarations} do Gemini. */
    static ArrayNode toFunctionDeclarations(JsonNode tools, ObjectMapper mapper) {
        ArrayNode declarations = mapper.createArrayNode();
        for (JsonNode tool : tools) {
            JsonNode function = tool.path("function");
            String name = function.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            ObjectNode declaration = declarations.addObject();
            declaration.put("name", name);
            declaration.put("description", function.path("description").asText(""));
            JsonNode parameters = function.path("parameters");
            if (parameters.isObject()) {
                declaration.set("parameters", sanitizeSchema(parameters, mapper));
            }
        }
        return declarations;
    }

    /**
     * Campos de schema que o Gemini aceita. O resto e removido.
     *
     * <p>A versao anterior usava lista de PROIBIDOS ({@code $schema}, {@code additionalProperties},
     * {@code default}) e quebrou no primeiro campo que eu nao tinha previsto: {@code
     * exclusiveMinimum} e {@code exclusiveMaximum} derrubaram a rodada inteira com HTTP 400. Lista
     * de proibidos nunca fecha — o JSON Schema tem dezenas de palavras que a API do Google nao
     * conhece, e basta uma para invalidar TODAS as ferramentas, nao so a que a trouxe.
     */
    private static final Set<String> GEMINI_SCHEMA_FIELDS = Set.of(
            "type",
            "format",
            "title",
            "description",
            "nullable",
            "enum",
            "items",
            "properties",
            "required",
            "minimum",
            "maximum",
            "minItems",
            "maxItems",
            "minLength",
            "maxLength",
            "pattern",
            "anyOf",
            "example",
            "propertyOrdering");

    /** Mantem so o que o Gemini entende; ver {@link #GEMINI_SCHEMA_FIELDS}. */
    static JsonNode sanitizeSchema(JsonNode schema, ObjectMapper mapper) {
        if (schema.isArray()) {
            ArrayNode cleaned = mapper.createArrayNode();
            schema.forEach(item -> cleaned.add(sanitizeSchema(item, mapper)));
            return cleaned;
        }
        if (!schema.isObject()) {
            return schema;
        }
        ObjectNode cleaned = mapper.createObjectNode();
        schema.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            if (!GEMINI_SCHEMA_FIELDS.contains(field)) {
                return;
            }
            // "properties" e um mapa de nome -> schema: os nomes sao livres e nao passam pelo filtro.
            if ("properties".equals(field) && entry.getValue().isObject()) {
                ObjectNode properties = mapper.createObjectNode();
                entry.getValue()
                        .fields()
                        .forEachRemaining(property ->
                                properties.set(property.getKey(), sanitizeSchema(property.getValue(), mapper)));
                cleaned.set(field, properties);
                return;
            }
            cleaned.set(field, sanitizeSchema(entry.getValue(), mapper));
        });
        return cleaned;
    }

    private static void addTextContent(ArrayNode contents, String role, String text, ObjectMapper mapper) {
        if (text == null || text.isBlank()) {
            return;
        }
        ObjectNode entry = contents.addObject();
        entry.put("role", role);
        entry.putArray("parts").addObject().put("text", text);
    }

    /**
     * Mensagem do usuário, com a imagem anexada quando houver uma.
     *
     * <p>O formato interno herdou do Ollama a lista {@code images} com base64 puro. O Gemini quer
     * {@code inlineData} com o tipo declarado — e sem esta tradução o anexo era simplesmente
     * descartado: o modelo respondia que não tinha recebido imagem nenhuma, e a tela mostrava a
     * miniatura ao lado, como se tivesse enviado.
     */
    private static void addUserContent(ArrayNode contents, JsonNode message, String content, ObjectMapper mapper) {
        JsonNode images = message.path("images");
        if (!images.isArray() || images.isEmpty()) {
            addTextContent(contents, "user", content, mapper);
            return;
        }
        ArrayNode parts = mapper.createArrayNode();
        for (JsonNode image : images) {
            String raw = image.asText("");
            if (raw.isBlank()) {
                continue;
            }
            ObjectNode inlineData = parts.addObject().putObject("inlineData");
            inlineData.put("mimeType", Base64Images.mediaType(raw));
            inlineData.put("data", Base64Images.payload(raw));
        }
        if (content != null && !content.isBlank()) {
            parts.addObject().put("text", content);
        }
        if (parts.isEmpty()) {
            return;
        }
        ObjectNode entry = contents.addObject();
        entry.put("role", "user");
        entry.set("parts", parts);
    }

    /** Mensagem do assistente pode carregar texto E a chamada de ferramenta que ele fez. */
    private static void addAssistantContent(ArrayNode contents, JsonNode message, String content, ObjectMapper mapper) {
        ArrayNode parts = mapper.createArrayNode();
        if (content != null && !content.isBlank()) {
            parts.addObject().put("text", content);
        }
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            ObjectNode functionCall = parts.addObject().putObject("functionCall");
            functionCall.put("name", name);
            functionCall.set("args", parseArguments(function.path("arguments"), mapper));
        }
        if (parts.isEmpty()) {
            return;
        }
        ObjectNode entry = contents.addObject();
        entry.put("role", "model");
        entry.set("parts", parts);
    }

    private static void addFunctionResponse(ArrayNode contents, JsonNode message, ObjectMapper mapper) {
        String name = message.path("name").asText("");
        if (name.isBlank()) {
            return;
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("name", name);
        ObjectNode payload = response.putObject("response");
        // O conteudo interno e string; o Gemini espera objeto.
        payload.put("result", message.path("content").asText(""));

        ObjectNode entry = contents.addObject();
        entry.put("role", "user");
        entry.putArray("parts").addObject().set("functionResponse", response);
    }

    private static JsonNode parseArguments(JsonNode arguments, ObjectMapper mapper) {
        if (arguments.isObject()) {
            return arguments;
        }
        try {
            return mapper.readTree(arguments.asText("{}"));
        } catch (Exception exception) {
            return mapper.createObjectNode();
        }
    }

    // ---------------------------------------------------------------- volta

    /**
     * Converte um evento SSE do Gemini numa linha no formato interno.
     *
     * <p>Devolve null quando o evento nao carrega nem texto nem chamada (keep-alive, metadados de
     * seguranca, contagem de tokens), para o agente nao processar linha vazia.
     */
    static String toInternalChunk(String rawEvent, ObjectMapper mapper) {
        if (rawEvent == null || rawEvent.isBlank() || "[DONE]".equals(rawEvent.trim())) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(rawEvent);
            JsonNode candidate = root.path("candidates").path(0);
            // O uso vem repetido e ACUMULADO em todo evento; contabilizar cada um multiplicaria os
            // tokens da rodada. Só o evento que fecha a resposta (o que traz finishReason) conta.
            boolean isFinalEvent = candidate.hasNonNull("finishReason")
                    && !candidate.path("finishReason").asText("").isBlank();
            JsonNode usage = root.path("usageMetadata");

            JsonNode parts = candidate.path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                return isFinalEvent ? usageLine(usage, root, mapper) : null;
            }

            StringBuilder text = new StringBuilder();
            ArrayNode toolCalls = mapper.createArrayNode();
            for (JsonNode part : parts) {
                if (part.has("text")) {
                    text.append(part.path("text").asText(""));
                }
                JsonNode functionCall = part.path("functionCall");
                if (functionCall.isObject() && functionCall.has("name")) {
                    ObjectNode call = toolCalls.addObject();
                    ObjectNode function = call.putObject("function");
                    function.put("name", functionCall.path("name").asText());
                    // O agente le arguments como STRING JSON (mesmo contrato do Ollama).
                    function.put("arguments", functionCall.path("args").toString());
                }
            }

            if (text.length() == 0 && toolCalls.isEmpty()) {
                return isFinalEvent ? usageLine(usage, root, mapper) : null;
            }

            ObjectNode line = mapper.createObjectNode();
            ObjectNode message = line.putObject("message");
            message.put("role", "assistant");
            message.put("content", text.toString());
            if (!toolCalls.isEmpty()) {
                message.set("tool_calls", toolCalls);
            }
            // Texto e uso podem vir no mesmo evento: o agente lê os dois campos de forma
            // independente, então a linha carrega ambos em vez de duplicar o chunk.
            if (isFinalEvent && usage.isObject()) {
                appendUsage(line, usage, root);
            } else {
                line.put("done", false);
            }
            return line.toString();
        } catch (Exception exception) {
            logger.debug("Evento do Gemini ignorado: {}", exception.getClass().getSimpleName());
            return null;
        }
    }

    /** Linha só com a contagem, para o evento final que não trouxe texto nem chamada. */
    private static String usageLine(JsonNode usage, JsonNode root, ObjectMapper mapper) {
        if (!usage.isObject()) {
            return null;
        }
        ObjectNode line = mapper.createObjectNode();
        appendUsage(line, usage, root);
        return line.toString();
    }

    /** O agente lê o uso da rodada no formato do Ollama: {@code done} + {@code eval_count}. */
    private static void appendUsage(ObjectNode line, JsonNode usage, JsonNode root) {
        line.put("done", true);
        line.put("model", root.path("modelVersion").asText(""));
        line.put("prompt_eval_count", usage.path("promptTokenCount").asInt(0));
        line.put("eval_count", usage.path("candidatesTokenCount").asInt(0));
    }
}
