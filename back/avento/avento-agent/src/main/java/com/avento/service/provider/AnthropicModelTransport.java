package com.avento.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Transporte do Anthropic (Claude): traduz a requisição canônica do agente e devolve a resposta no
 * formato interno, ferramentas incluídas.
 *
 * <p>O agente continua com o loop de rodadas intacto — ferramentas, RAG, memória e sandbox chegam
 * ao Claude pela mesma estrutura que chega ao Gemini e ao Ollama.
 *
 * <p>Diferenças traduzidas em relação ao formato canônico do Avento:
 *
 * <ul>
 *   <li>endpoint: {@code POST /v1/messages};
 *   <li>autenticação: {@code x-api-key} + {@code anthropic-version: 2023-06-01};
 *   <li>prompt de sistema: sai das mensagens e vai para campo {@code system} separado;
 *   <li>ferramentas (declaração): {@code parameters} renomeado para {@code input_schema};
 *   <li>resultado de ferramenta: {@code role=tool} vira {@code {role:user, content:[{type:tool_result}]}};
 *   <li>chamada de ferramenta (saída): eventos SSE tipados com acumulação de {@code partial_json};
 *   <li>{@code max_tokens} é obrigatório — a API recusa a requisição sem ele.
 * </ul>
 */
@Service
public class AnthropicModelTransport implements ModelTransport {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicModelTransport.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    /**
     * Máximo de tokens de saída padrão. A API recusa sem esse campo; o valor cobre a maioria dos
     * modelos Claude atuais sem truncar respostas comuns.
     */
    private static final int DEFAULT_MAX_TOKENS = 8192;

    private final ObjectMapper mapper;
    private final WebClient webClient;
    private final Duration timeout;

    public AnthropicModelTransport(
            ObjectMapper mapper, @Value("${avento.provider.anthropic.timeout-seconds:300}") long timeoutSeconds) {
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.webClient = ProviderWebClients.streaming();
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.ANTHROPIC;
    }

    @Override
    public Flux<String> stream(ObjectNode canonicalRequest, String baseUrl, String apiKey) {
        ObjectNode body = toAnthropicRequest(canonicalRequest, mapper);
        String url = (baseUrl == null || baseUrl.isBlank() ? ProviderKind.ANTHROPIC.defaultBaseUrl() : baseUrl)
                        .replaceAll("/+$", "")
                + "/v1/messages";

        // O acumulador mantém o estado entre eventos SSE da MESMA chamada, e por isso nasce dentro
        // do defer: criado fora, uma reinscrição no Flux herdaria blocos da chamada anterior e
        // emitiria uma ferramenta com argumentos de duas conversas grudados.
        return Flux.defer(() -> {
            AnthropicAccumulator accumulator = new AnthropicAccumulator();
            return webClient
                    .post()
                    .uri(url)
                    // Chave em header, nunca em query string: URL entra em log de proxy e de servidor.
                    .header("x-api-key", apiKey == null ? "" : apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(timeout)
                    .mapNotNull(event -> toInternalChunk(event, mapper, accumulator));
        });
    }

    // ------------------------------------------------------------------ ida

    /**
     * Converte a requisição canônica do Avento para o corpo da API Anthropic.
     *
     * <p>Estático e sem rede de propósito: é a parte que erra em silêncio, então precisa ser
     * testável sem chave e sem chamada externa.
     */
    static ObjectNode toAnthropicRequest(ObjectNode canonical, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", canonical.path("model").asText(""));
        body.put("stream", true);
        // Obrigatório aqui, ao contrário dos outros dialetos. O teto da rodada vem do agente quando
        // ele o define; o padrão só entra quando não há escolha.
        int requestedMaxTokens = canonical.path("options").path("num_predict").asInt(0);
        body.put("max_tokens", requestedMaxTokens > 0 ? requestedMaxTokens : DEFAULT_MAX_TOKENS);

        StringBuilder systemText = new StringBuilder();
        ArrayNode messages = body.putArray("messages");

        for (JsonNode message : canonical.path("messages")) {
            String role = message.path("role").asText("");

            switch (role) {
                case "system" -> {
                    // Prompt de sistema: sai das mensagens e vai para campo separado.
                    String content = message.path("content").asText("");
                    if (!content.isBlank()) {
                        if (systemText.length() > 0) {
                            systemText.append("\n\n");
                        }
                        systemText.append(content);
                    }
                }
                case "tool" -> {
                    // Resultado de ferramenta: {role:user, content:[{type:tool_result,...}]}
                    ObjectNode out = messages.addObject();
                    out.put("role", "user");
                    ObjectNode toolResult = out.putArray("content").addObject();
                    toolResult.put("type", "tool_result");
                    toolResult.put("tool_use_id", message.path("tool_call_id").asText(""));
                    toolResult.put("content", message.path("content").asText(""));
                }
                case "assistant" -> {
                    // Mensagem do assistente: texto e/ou chamadas de ferramenta.
                    ArrayNode parts = mapper.createArrayNode();
                    String content = message.path("content").asText("");
                    if (!content.isBlank()) {
                        parts.addObject().put("type", "text").put("text", content);
                    }
                    for (JsonNode call : message.path("tool_calls")) {
                        JsonNode function = call.path("function");
                        String name = function.path("name").asText("");
                        if (name.isBlank()) {
                            continue;
                        }
                        ObjectNode toolUse = parts.addObject();
                        toolUse.put("type", "tool_use");
                        // id é necessário para vincular ao tool_result; usa o índice quando ausente.
                        toolUse.put("id", call.path("id").asText("call_" + parts.size()));
                        toolUse.put("name", name);
                        toolUse.set("input", parseArguments(function.path("arguments"), mapper));
                    }
                    if (!parts.isEmpty()) {
                        ObjectNode out = messages.addObject();
                        out.put("role", "assistant");
                        out.set("content", parts);
                    }
                }
                case "user" -> {
                    String content = message.path("content").asText("");
                    JsonNode images = message.path("images");
                    boolean hasImages = images.isArray() && !images.isEmpty();
                    if (content.isBlank() && !hasImages) {
                        continue;
                    }
                    ObjectNode out = messages.addObject();
                    out.put("role", "user");
                    if (!hasImages) {
                        out.put("content", content);
                        continue;
                    }
                    // Imagem anexada vira bloco próprio, com o tipo declarado: a API não aceita
                    // base64 solto e o anexo sumiria sem aviso nenhum.
                    ArrayNode parts = out.putArray("content");
                    for (JsonNode image : images) {
                        String raw = image.asText("");
                        ObjectNode part = parts.addObject();
                        part.put("type", "image");
                        ObjectNode source = part.putObject("source");
                        source.put("type", "base64");
                        source.put("media_type", Base64Images.mediaType(raw));
                        source.put("data", Base64Images.payload(raw));
                    }
                    if (!content.isBlank()) {
                        parts.addObject().put("type", "text").put("text", content);
                    }
                }
                // Papel que este dialeto não conhece não vira mensagem: mandá-lo faz a API recusar a
                // requisição inteira, e o turno morre por causa de uma linha de histórico.
                default -> {}
            }
        }

        if (systemText.length() > 0) {
            body.put("system", systemText.toString());
        }

        // Ferramentas: parameters → input_schema (renomeação obrigatória pela API Anthropic).
        JsonNode tools = canonical.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            ArrayNode outTools = body.putArray("tools");
            for (JsonNode tool : tools) {
                JsonNode function = tool.path("function");
                String name = function.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                ObjectNode outTool = outTools.addObject();
                outTool.put("name", name);
                outTool.put("description", function.path("description").asText(""));
                // Anthropic usa "input_schema" onde o formato canônico usa "parameters".
                JsonNode parameters = function.path("parameters");
                outTool.set("input_schema", parameters.isObject() ? parameters : mapper.createObjectNode());
            }
        }

        return body;
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
     * Converte um evento SSE do Anthropic numa linha no formato interno.
     *
     * <p>A Anthropic usa eventos tipados: {@code content_block_start} para abrir um bloco (texto ou
     * ferramenta), {@code content_block_delta} para acumulação incremental e {@code
     * content_block_stop} para fechar o bloco e emitir o resultado. O acumulador mantém esse estado
     * entre chamadas.
     *
     * <p>Devolve null nos eventos de controle para o agente não processar linha vazia.
     */
    static String toInternalChunk(String rawEvent, ObjectMapper mapper, AnthropicAccumulator accumulator) {
        if (rawEvent == null || rawEvent.isBlank()) {
            return null;
        }

        // SSE pode chegar como "event: <tipo>\ndata: {...}" ou apenas "data: {...}"
        String eventType = null;
        String dataLine = null;
        for (String line : rawEvent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("event:")) {
                eventType = trimmed.substring(6).trim();
            } else if (trimmed.startsWith("data:")) {
                dataLine = trimmed.substring(5).trim();
            }
        }

        if (dataLine == null || dataLine.isBlank() || "[DONE]".equals(dataLine)) {
            return null;
        }

        try {
            JsonNode root = mapper.readTree(dataLine);
            // Quando não há campo "type" separado, usa o campo no próprio JSON de dados.
            String type = eventType != null ? eventType : root.path("type").asText("");

            return switch (type) {
                // O uso vem em dois eventos: a entrada abre a mensagem, a saída fecha. Guardar o
                // primeiro é o que permite entregar o par completo no formato que o agente lê.
                case "message_start" -> {
                    accumulator.rememberPromptTokens(root.path("message")
                            .path("usage")
                            .path("input_tokens")
                            .asInt(0));
                    accumulator.rememberModel(root.path("message").path("model").asText(""));
                    yield null;
                }
                case "message_delta" -> {
                    JsonNode usage = root.path("usage");
                    if (!usage.hasNonNull("output_tokens")) {
                        yield null;
                    }
                    // Mesmo formato do Ollama: sem done+eval_count, a contagem da rodada fica zerada.
                    ObjectNode line = mapper.createObjectNode();
                    line.put("done", true);
                    line.put("model", accumulator.model());
                    line.put("prompt_eval_count", accumulator.promptTokens());
                    line.put("eval_count", usage.path("output_tokens").asInt(0));
                    yield line.toString();
                }
                case "content_block_start" -> {
                    JsonNode block = root.path("content_block");
                    int index = root.path("index").asInt(0);
                    String blockType = block.path("type").asText("");
                    if ("tool_use".equals(blockType)) {
                        accumulator.startToolBlock(
                                index,
                                block.path("id").asText(""),
                                block.path("name").asText(""));
                    } else {
                        accumulator.startTextBlock(index);
                    }
                    yield null;
                }
                case "content_block_delta" -> {
                    int index = root.path("index").asInt(0);
                    JsonNode delta = root.path("delta");
                    String deltaType = delta.path("type").asText("");
                    if ("text_delta".equals(deltaType)) {
                        String text = delta.path("text").asText("");
                        if (text.isBlank()) {
                            yield null;
                        }
                        // Emite chunk de texto imediatamente — sem acumulação.
                        ObjectNode line = mapper.createObjectNode();
                        ObjectNode message = line.putObject("message");
                        message.put("role", "assistant");
                        message.put("content", text);
                        line.put("done", false);
                        yield line.toString();
                    } else if ("thinking_delta".equals(deltaType)) {
                        String thinking = delta.path("thinking").asText("");
                        if (thinking.isEmpty()) {
                            yield null;
                        }
                        // O agente já sabe reembrulhar isto como <think> para a interface.
                        ObjectNode line = mapper.createObjectNode();
                        ObjectNode message = line.putObject("message");
                        message.put("role", "assistant");
                        message.put("thinking", thinking);
                        line.put("done", false);
                        yield line.toString();
                    } else if ("input_json_delta".equals(deltaType)) {
                        // JSON parcial dos argumentos da ferramenta — acumula até content_block_stop.
                        accumulator.appendJson(index, delta.path("partial_json").asText(""));
                        yield null;
                    }
                    yield null;
                }
                case "content_block_stop" -> {
                    int index = root.path("index").asInt(0);
                    AnthropicAccumulator.ToolBlock toolBlock = accumulator.finishBlock(index);
                    if (toolBlock == null) {
                        yield null;
                    }
                    // Emite o tool_call completo com os argumentos acumulados.
                    ObjectNode line = mapper.createObjectNode();
                    ObjectNode message = line.putObject("message");
                    message.put("role", "assistant");
                    message.put("content", "");
                    ArrayNode toolCalls = message.putArray("tool_calls");
                    ObjectNode call = toolCalls.addObject();
                    call.put("id", toolBlock.id());
                    ObjectNode function = call.putObject("function");
                    function.put("name", toolBlock.name());
                    // O agente lê arguments como STRING JSON — mesmo contrato do Ollama e do Gemini.
                    function.put("arguments", toolBlock.accumulatedJson());
                    line.put("done", false);
                    yield line.toString();
                }
                default -> null;
            };
        } catch (Exception exception) {
            logger.debug("Evento Anthropic ignorado: {}", exception.getClass().getSimpleName());
            return null;
        }
    }

    // --------------------------------------------------------------- acumulador

    /**
     * Estado de acumulação de um streaming Anthropic.
     *
     * <p>Cada bloco tem um índice: blocos de texto são emitidos incrementalmente; blocos de
     * ferramenta acumulam {@code partial_json} até o {@code content_block_stop}.
     */
    static final class AnthropicAccumulator {

        record ToolBlock(String id, String name, String accumulatedJson) {}

        private final Map<Integer, ToolBlock> toolBlocks = new HashMap<>();
        private final Map<Integer, StringBuilder> jsonBuffers = new HashMap<>();
        private int promptTokens;
        private String model = "";

        void startTextBlock(int index) {
            // Nada a registrar: texto é emitido incrementalmente.
        }

        void rememberPromptTokens(int tokens) {
            this.promptTokens = tokens;
        }

        int promptTokens() {
            return promptTokens;
        }

        void rememberModel(String model) {
            if (model != null && !model.isBlank()) {
                this.model = model;
            }
        }

        String model() {
            return model;
        }

        void startToolBlock(int index, String id, String name) {
            jsonBuffers.put(index, new StringBuilder());
            toolBlocks.put(index, new ToolBlock(id, name, ""));
        }

        void appendJson(int index, String partial) {
            StringBuilder buf = jsonBuffers.get(index);
            if (buf != null) {
                buf.append(partial);
            }
        }

        /**
         * Fecha o bloco e devolve o {@link ToolBlock} completo se for ferramenta, ou null se for
         * texto.
         */
        ToolBlock finishBlock(int index) {
            ToolBlock partial = toolBlocks.remove(index);
            if (partial == null) {
                return null;
            }
            StringBuilder buf = jsonBuffers.remove(index);
            String json = buf != null ? buf.toString() : "{}";
            return new ToolBlock(partial.id(), partial.name(), json.isBlank() ? "{}" : json);
        }
    }
}
