package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Prende a tradução nos dois sentidos do transporte Anthropic.
 *
 * <p>O agente é quem tem as ferramentas; o transporte só traduz o dialeto da chamada. Estes testes
 * cobrem os métodos estáticos testáveis sem rede — que é onde o erro seria silencioso.
 */
class AnthropicModelTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode canonical(String toolsJson, String... roleAndContent) throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "claude-sonnet-4-5");
        ArrayNode messages = request.putArray("messages");
        for (int i = 0; i < roleAndContent.length; i += 2) {
            messages.addObject().put("role", roleAndContent[i]).put("content", roleAndContent[i + 1]);
        }
        request.set("tools", toolsJson == null ? MAPPER.createArrayNode() : (ArrayNode) MAPPER.readTree(toolsJson));
        return request;
    }

    // Prompt de sistema nao pode ficar em "messages" — a API rejeita a requisicao.
    @Test
    void liftsSystemMessageToTopLevelField() throws Exception {
        ObjectNode body = AnthropicModelTransport.toAnthropicRequest(
                canonical(null, "system", "Voce e o Avento.", "user", "oi"), MAPPER);

        assertThat(body.path("system").asText()).isEqualTo("Voce e o Avento.");
        // system nao deve aparecer em messages.
        for (JsonNode msg : body.path("messages")) {
            assertThat(msg.path("role").asText()).isNotEqualTo("system");
        }
    }

    // max_tokens ausente faz a API retornar erro imediatamente.
    @Test
    void alwaysIncludesMaxTokens() throws Exception {
        ObjectNode body = AnthropicModelTransport.toAnthropicRequest(canonical(null, "user", "oi"), MAPPER);
        assertThat(body.has("max_tokens")).isTrue();
        assertThat(body.path("max_tokens").asInt()).isGreaterThan(0);
    }

    // Declaracao de ferramenta: "parameters" vira "input_schema" (nome obrigatorio pela API).
    @Test
    void renamesParametersToInputSchema() throws Exception {
        String tools = "[{\"type\":\"function\",\"function\":{\"name\":\"read_file\","
                + "\"description\":\"Le um arquivo\",\"parameters\":{\"type\":\"object\","
                + "\"properties\":{\"path\":{\"type\":\"string\"}}}}}]";

        ObjectNode body = AnthropicModelTransport.toAnthropicRequest(canonical(tools, "user", "le o pom"), MAPPER);

        JsonNode outTool = body.path("tools").get(0);
        assertThat(outTool.path("name").asText()).isEqualTo("read_file");
        assertThat(outTool.has("input_schema")).isTrue();
        assertThat(outTool.has("parameters")).isFalse();
        assertThat(outTool.path("input_schema").path("properties").has("path")).isTrue();
    }

    // Resultado de ferramenta nao pode ser role=tool — a API nao conhece esse papel.
    @Test
    void convertsToolResultToUserWithToolResultContent() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "claude-sonnet-4-5");
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "tool").put("tool_call_id", "toolu_01").put("content", "{\"result\":\"ok\"}");
        request.putArray("tools");

        ObjectNode body = AnthropicModelTransport.toAnthropicRequest(request, MAPPER);

        JsonNode msg = body.path("messages").get(0);
        assertThat(msg.path("role").asText()).isEqualTo("user");
        JsonNode toolResult = msg.path("content").get(0);
        assertThat(toolResult.path("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.path("tool_use_id").asText()).isEqualTo("toolu_01");
        assertThat(toolResult.path("content").asText()).isEqualTo("{\"result\":\"ok\"}");
    }

    // ---------------------------------------------------------------- volta (SSE)

    @Test
    void convertsTextDeltaIntoInternalShape() throws Exception {
        String event = "event: content_block_delta\ndata: {\"index\":0,\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Ola\"}}";

        AnthropicModelTransport.AnthropicAccumulator acc = new AnthropicModelTransport.AnthropicAccumulator();
        acc.startTextBlock(0);
        JsonNode line = MAPPER.readTree(AnthropicModelTransport.toInternalChunk(event, MAPPER, acc));

        assertThat(line.path("message").path("content").asText()).isEqualTo("Ola");
        assertThat(line.path("done").asBoolean()).isFalse();
    }

    /**
     * Tool calling: a Anthropic acumula partial_json em vários eventos; o resultado completo só
     * aparece no content_block_stop. O agente lê arguments como STRING JSON.
     */
    @Test
    void accumulatesPartialJsonAndEmitsToolCallOnStop() throws Exception {
        AnthropicModelTransport.AnthropicAccumulator acc = new AnthropicModelTransport.AnthropicAccumulator();

        // 1. content_block_start → registra o bloco de ferramenta
        String start = "event: content_block_start\ndata: {\"index\":0,\"type\":\"content_block_start\","
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"read_file\"}}";
        assertThat(AnthropicModelTransport.toInternalChunk(start, MAPPER, acc)).isNull();

        // 2. content_block_delta → acumula JSON parcial
        String delta1 = "event: content_block_delta\ndata: {\"index\":0,\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"po\"}}";
        assertThat(AnthropicModelTransport.toInternalChunk(delta1, MAPPER, acc)).isNull();

        String delta2 = "event: content_block_delta\ndata: {\"index\":0,\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"m.xml\\\"}}\"}}";
        assertThat(AnthropicModelTransport.toInternalChunk(delta2, MAPPER, acc)).isNull();

        // 3. content_block_stop → emite o tool_call completo
        String stop = "event: content_block_stop\ndata: {\"index\":0,\"type\":\"content_block_stop\"}";
        JsonNode line = MAPPER.readTree(AnthropicModelTransport.toInternalChunk(stop, MAPPER, acc));

        JsonNode call = line.path("message").path("tool_calls").get(0);
        assertThat(call.path("id").asText()).isEqualTo("toolu_01");
        JsonNode function = call.path("function");
        assertThat(function.path("name").asText()).isEqualTo("read_file");
        assertThat(function.path("arguments").isTextual()).isTrue();
        assertThat(MAPPER.readTree(function.path("arguments").asText())
                        .path("path")
                        .asText())
                .isEqualTo("pom.xml");
    }

    @Test
    void ignoresMessageStopEvent() {
        AnthropicModelTransport.AnthropicAccumulator acc = new AnthropicModelTransport.AnthropicAccumulator();
        String event = "event: message_stop\ndata: {\"type\":\"message_stop\"}";
        assertThat(AnthropicModelTransport.toInternalChunk(event, MAPPER, acc)).isNull();
    }

    @Test
    void ignoresEmptyOrBlankEvent() {
        AnthropicModelTransport.AnthropicAccumulator acc = new AnthropicModelTransport.AnthropicAccumulator();
        assertThat(AnthropicModelTransport.toInternalChunk("", MAPPER, acc)).isNull();
        assertThat(AnthropicModelTransport.toInternalChunk("   ", MAPPER, acc)).isNull();
    }

    /**
     * {@code max_tokens} é obrigatório neste dialeto. Fixá-lo no código ignoraria o teto que o
     * agente define para a rodada — o padrão só vale quando não há escolha.
     */
    @Test
    void honoursTheRequestedOutputLimit() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "claude-sonnet-4-5");
        request.putArray("messages").addObject().put("role", "user").put("content", "oi");
        request.putArray("tools");
        request.putObject("options").put("num_predict", 512);

        ObjectNode body = AnthropicModelTransport.toAnthropicRequest(request, MAPPER);

        assertThat(body.path("max_tokens").asInt()).isEqualTo(512);
    }

    @Test
    void fallsBackToTheDefaultOutputLimit() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "claude-sonnet-4-5");
        request.putArray("messages").addObject().put("role", "user").put("content", "oi");
        request.putArray("tools");

        ObjectNode body = AnthropicModelTransport.toAnthropicRequest(request, MAPPER);

        assertThat(body.path("max_tokens").asInt()).isEqualTo(8192);
    }

    // Base64 solto não é aceito: a imagem vira bloco próprio, com o tipo declarado.
    @Test
    void translatesAttachedImagesIntoImageBlocks() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "claude-sonnet-4-5");
        ObjectNode user = request.putArray("messages").addObject();
        user.put("role", "user").put("content", "o que tem aqui?");
        user.putArray("images").add("/9j/4AAQSkZJRgABAQ==");
        request.putArray("tools");

        ObjectNode body = AnthropicModelTransport.toAnthropicRequest(request, MAPPER);

        JsonNode parts = body.path("messages").get(0).path("content");
        assertThat(parts.get(0).path("type").asText()).isEqualTo("image");
        assertThat(parts.get(0).path("source").path("media_type").asText()).isEqualTo("image/jpeg");
        assertThat(parts.get(0).path("source").path("data").asText()).isEqualTo("/9j/4AAQSkZJRgABAQ==");
        assertThat(parts.get(1).path("text").asText()).isEqualTo("o que tem aqui?");
    }

    /**
     * O uso chega partido: a entrada abre a mensagem, a saída fecha. Guardar a primeira metade é o
     * que permite entregar o par completo no formato que o agente lê ({@code done} +
     * {@code eval_count}) — sem isso a contagem de tokens do Claude fica zerada.
     */
    @Test
    void reportsTokenUsageAcrossTheTwoEventsThatCarryIt() throws Exception {
        AnthropicModelTransport.AnthropicAccumulator acc = new AnthropicModelTransport.AnthropicAccumulator();

        String start = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":"
                + "{\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":250}}}";
        assertThat(AnthropicModelTransport.toInternalChunk(start, MAPPER, acc)).isNull();

        String end = "event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":77}}";
        JsonNode line = MAPPER.readTree(AnthropicModelTransport.toInternalChunk(end, MAPPER, acc));

        assertThat(line.path("done").asBoolean()).isTrue();
        assertThat(line.path("prompt_eval_count").asInt()).isEqualTo(250);
        assertThat(line.path("eval_count").asInt()).isEqualTo(77);
        assertThat(line.path("model").asText()).isEqualTo("claude-sonnet-4-5");
    }

    // O pensamento tem campo próprio; o agente já sabe reembrulhá-lo como <think> para a interface.
    @Test
    void mapsThinkingDeltaIntoThinking() throws Exception {
        AnthropicModelTransport.AnthropicAccumulator acc = new AnthropicModelTransport.AnthropicAccumulator();
        String event = "event: content_block_delta\ndata: {\"index\":0,\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"deixa eu ver\"}}";

        JsonNode line = MAPPER.readTree(AnthropicModelTransport.toInternalChunk(event, MAPPER, acc));

        assertThat(line.path("message").path("thinking").asText()).isEqualTo("deixa eu ver");
    }
}
