package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Prende a tradução nos dois sentidos do transporte OpenAI-Compatible.
 *
 * <p>O agente é quem tem as ferramentas; o transporte só traduz o dialeto da chamada. Estes testes
 * cobrem os métodos estáticos testáveis sem rede — que é onde o erro seria silencioso.
 */
class OpenAiCompatibleModelTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode canonical(String toolsJson, String... roleAndContent) throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "gpt-4o");
        ArrayNode messages = request.putArray("messages");
        for (int i = 0; i < roleAndContent.length; i += 2) {
            messages.addObject().put("role", roleAndContent[i]).put("content", roleAndContent[i + 1]);
        }
        request.set("tools", toolsJson == null ? MAPPER.createArrayNode() : (ArrayNode) MAPPER.readTree(toolsJson));
        return request;
    }

    // Papéis do protocolo OpenAI são iguais ao canônico — nenhum deve ser renomeado.
    @Test
    void keepsRolesUnchanged() throws Exception {
        ObjectNode body = OpenAiCompatibleModelTransport.toOpenAiRequest(
                canonical(null, "system", "Voce e o Avento.", "user", "oi", "assistant", "ola"), MAPPER);

        JsonNode messages = body.path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("assistant");
    }

    // stream:true é obrigatório para o servidor enviar SSE em vez de resposta completa.
    @Test
    void alwaysSetsStreamTrue() throws Exception {
        ObjectNode body = OpenAiCompatibleModelTransport.toOpenAiRequest(canonical(null, "user", "oi"), MAPPER);
        assertThat(body.path("stream").asBoolean()).isTrue();
    }

    // Ferramentas chegam no formato canônico e devem ser repassadas sem alteração.
    @Test
    void passesToolsUnchanged() throws Exception {
        String tools = "[{\"type\":\"function\",\"function\":{\"name\":\"read_file\","
                + "\"description\":\"Le um arquivo\",\"parameters\":{\"type\":\"object\","
                + "\"properties\":{\"path\":{\"type\":\"string\"}}}}}]";

        ObjectNode body = OpenAiCompatibleModelTransport.toOpenAiRequest(canonical(tools, "user", "le o pom"), MAPPER);

        JsonNode outTools = body.path("tools");
        assertThat(outTools).hasSize(1);
        assertThat(outTools.get(0).path("function").path("name").asText()).isEqualTo("read_file");
    }

    // Resultado de ferramenta precisa ter tool_call_id (vazio quando ausente é aceito pelo protocolo).
    @Test
    void includesToolCallIdInToolMessage() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "gpt-4o");
        request.putArray("messages")
                .addObject()
                .put("role", "tool")
                .put("content", "{\"result\":\"ok\"}")
                .put("tool_call_id", "call_abc");
        request.putArray("tools");

        ObjectNode body = OpenAiCompatibleModelTransport.toOpenAiRequest(request, MAPPER);

        JsonNode toolMsg = body.path("messages").get(0);
        assertThat(toolMsg.path("role").asText()).isEqualTo("tool");
        assertThat(toolMsg.path("tool_call_id").asText()).isEqualTo("call_abc");
        assertThat(toolMsg.path("content").asText()).isEqualTo("{\"result\":\"ok\"}");
    }

    // Assistente com chamada de ferramenta no histórico: arguments precisa ser String JSON para OpenAI.
    @Test
    void serializesAssistantToolCallArgumentsToString() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "qwen3.5:9b");
        ArrayNode messages = request.putArray("messages");
        ObjectNode assistant = messages.addObject();
        assistant.put("role", "assistant").put("content", "");
        ObjectNode call = assistant.putArray("tool_calls").addObject();
        call.put("id", "call_1");
        ObjectNode func = call.putObject("function");
        func.put("name", "get_weather");
        func.putObject("arguments").put("location", "São Paulo");
        request.putArray("tools");

        ObjectNode body = OpenAiCompatibleModelTransport.toOpenAiRequest(request, MAPPER);

        JsonNode assistantMsg = body.path("messages").get(0);
        JsonNode outCall = assistantMsg.path("tool_calls").get(0);
        assertThat(outCall.path("type").asText()).isEqualTo("function");
        JsonNode args = outCall.path("function").path("arguments");
        assertThat(args.isTextual()).isTrue();
        assertThat(args.asText()).contains("São Paulo");
    }

    // Imagem anexada chega no formato do Ollama (base64 puro em "images"). Sem a tradução para
    // partes com data URI, o anexo some e o modelo responde que não recebeu imagem nenhuma.
    @Test
    void translatesAttachedImagesIntoContentParts() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "gpt-4o");
        ObjectNode user = request.putArray("messages").addObject();
        user.put("role", "user").put("content", "o que tem aqui?");
        user.putArray("images").add("/9j/4AAQSkZJRgABAQ==");
        request.putArray("tools");

        ObjectNode body = OpenAiCompatibleModelTransport.toOpenAiRequest(request, MAPPER);

        JsonNode parts = body.path("messages").get(0).path("content");
        assertThat(parts.isArray()).isTrue();
        assertThat(parts.get(0).path("type").asText()).isEqualTo("text");
        assertThat(parts.get(1).path("type").asText()).isEqualTo("image_url");
        // O tipo sai da assinatura do próprio base64: "/9j/" é JPEG, não PNG.
        assertThat(parts.get(1).path("image_url").path("url").asText()).startsWith("data:image/jpeg;base64,");
    }

    // ---------------------------------------------------------------- volta

    private OpenAiCompatibleModelTransport.StreamAssembler assembler() {
        return new OpenAiCompatibleModelTransport.StreamAssembler(MAPPER);
    }

    @Test
    void convertsTextDeltaIntoInternalShape() throws Exception {
        String event = "data: {\"choices\":[{\"delta\":{\"content\":\"Ola\"}}]}";

        JsonNode line = MAPPER.readTree(assembler().consume(event));

        assertThat(line.path("message").path("content").asText()).isEqualTo("Ola");
        assertThat(line.path("done").asBoolean()).isFalse();
    }

    /**
     * O caso que importa: a OpenAI manda o nome num evento e os argumentos picados nos seguintes.
     *
     * <p>Emitir cada pedaço como se fosse uma chamada faz o agente executar a ferramenta com
     * argumento vazio — o primeiro fragmento vira uma chamada sem argumentos e os outros são
     * descartados por não trazerem nome. Nada disso aparece no log como erro.
     */
    @Test
    void assemblesToolCallSplitAcrossEvents() throws Exception {
        var assembler = assembler();

        assertThat(assembler.consume("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}"))
                .isNull();
        assertThat(assembler.consume("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"path\\\":\"}}]}}]}"))
                .isNull();
        assertThat(assembler.consume("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"pom.xml\\\"}\"}}]}}]}"))
                .isNull();

        String emitted = assembler.consume("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");

        JsonNode call =
                MAPPER.readTree(emitted).path("message").path("tool_calls").get(0);
        assertThat(call.path("id").asText()).isEqualTo("call_1");
        assertThat(call.path("function").path("name").asText()).isEqualTo("read_file");
        // O agente lê arguments como STRING JSON — mesmo contrato do Gemini e do Ollama.
        JsonNode arguments = call.path("function").path("arguments");
        assertThat(arguments.isTextual()).isTrue();
        assertThat(MAPPER.readTree(arguments.asText()).path("path").asText()).isEqualTo("pom.xml");
    }

    // Duas ferramentas na mesma rodada vêm intercaladas, separadas só pelo índice.
    @Test
    void keepsParallelToolCallsApartByIndex() throws Exception {
        var assembler = assembler();
        assembler.consume("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"a\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}}]}}]}");
        assembler.consume("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"b\","
                + "\"function\":{\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\\\"b\\\"}\"}}]}}]}");

        JsonNode calls = MAPPER.readTree(assembler.flush()).path("message").path("tool_calls");

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).path("function").path("name").asText()).isEqualTo("read_file");
        assertThat(calls.get(1).path("function").path("name").asText()).isEqualTo("list_dir");
    }

    // Servidor compatível nem sempre manda finish_reason; sem o flush a ferramenta seria descartada.
    @Test
    void flushEmitsToolCallLeftOverAtEndOfStream() throws Exception {
        var assembler = assembler();
        assembler.consume("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\","
                + "\"function\":{\"name\":\"list_dir\",\"arguments\":\"{}\"}}]}}]}");

        JsonNode line = MAPPER.readTree(assembler.flush());

        assertThat(line.path("message")
                        .path("tool_calls")
                        .get(0)
                        .path("function")
                        .path("name")
                        .asText())
                .isEqualTo("list_dir");
        // Emitido uma única vez: um segundo flush não pode repetir a mesma chamada.
        assertThat(assembler.flush()).isNull();
    }

    /**
     * O uso da rodada volta no formato que o agente já lê ({@code done} + {@code eval_count}). Sem
     * isso, a contagem de tokens de todo provedor remoto fica zerada na interface e no histórico.
     */
    @Test
    void reportsTokenUsageInTheOllamaShape() throws Exception {
        var assembler = assembler();
        assembler.consume("data: {\"model\":\"gpt-4o\",\"choices\":[{\"delta\":{\"content\":\"oi\"}}]}");

        JsonNode line = MAPPER.readTree(
                assembler.consume("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":34}}"));

        assertThat(line.path("done").asBoolean()).isTrue();
        assertThat(line.path("prompt_eval_count").asInt()).isEqualTo(120);
        assertThat(line.path("eval_count").asInt()).isEqualTo(34);
        assertThat(line.path("model").asText()).isEqualTo("gpt-4o");
    }

    // Modelo de raciocínio manda o pensamento num campo próprio; o agente já sabe exibi-lo.
    @Test
    void mapsReasoningContentIntoThinking() throws Exception {
        String event = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"deixa eu ver\"}}]}";

        JsonNode line = MAPPER.readTree(assembler().consume(event));

        assertThat(line.path("message").path("thinking").asText()).isEqualTo("deixa eu ver");
    }

    @Test
    void ignoresDoneEvent() {
        assertThat(assembler().consume("data: [DONE]")).isNull();
    }

    @Test
    void ignoresEmptyDelta() {
        assertThat(assembler().consume("data: {\"choices\":[{\"delta\":{}}]}")).isNull();
    }

    @Test
    void ignoresKeepAlive() {
        assertThat(assembler().consume(": keep-alive")).isNull();
        assertThat(assembler().consume("")).isNull();
    }

    // Sem prefixo "data:" também deve funcionar (alguns servidores omitem).
    @Test
    void handlesEventWithoutDataPrefix() throws Exception {
        String event = "{\"choices\":[{\"delta\":{\"content\":\"Oi\"}}]}";

        JsonNode line = MAPPER.readTree(assembler().consume(event));

        assertThat(line.path("message").path("content").asText()).isEqualTo("Oi");
    }
}
