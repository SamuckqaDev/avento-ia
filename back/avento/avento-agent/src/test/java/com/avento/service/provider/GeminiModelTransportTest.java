package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * O agente é quem tem as ferramentas; o modelo é processamento. A primeira versão errou nisso — o
 * caminho de nuvem desviava do laço de rodadas, e o Gemini ficava sem ferramenta, sem RAG e sem
 * memória.
 *
 * <p>Agora o provedor é transporte: a requisição canônica entra, o dialeto do provedor sai, e a
 * resposta volta no formato que o agente já consome. Estes testes prendem a tradução nos dois
 * sentidos, que é onde o erro seria silencioso.
 */
class GeminiModelTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode canonical(String toolsJson, String... roleAndContent) throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "gemini-2.0-flash");
        ArrayNode messages = request.putArray("messages");
        for (int i = 0; i < roleAndContent.length; i += 2) {
            messages.addObject().put("role", roleAndContent[i]).put("content", roleAndContent[i + 1]);
        }
        request.set("tools", toolsJson == null ? MAPPER.createArrayNode() : (ArrayNode) MAPPER.readTree(toolsJson));
        return request;
    }

    // Sem isto o Gemini nao recebe ferramenta nenhuma e o agente perde arquivo, terminal, MCP e RAG.
    @Test
    void translatesToolsIntoFunctionDeclarations() throws Exception {
        String tools = "[{\"type\":\"function\",\"function\":{\"name\":\"read_file\","
                + "\"description\":\"Le um arquivo\",\"parameters\":{\"type\":\"object\","
                + "\"properties\":{\"path\":{\"type\":\"string\"}}}}}]";

        ObjectNode request = GeminiModelTransport.toGeminiRequest(canonical(tools, "user", "le o pom"), MAPPER);

        JsonNode declarations = request.path("tools").path(0).path("functionDeclarations");
        assertThat(declarations).hasSize(1);
        assertThat(declarations.get(0).path("name").asText()).isEqualTo("read_file");
        assertThat(declarations.get(0).path("parameters").path("properties").has("path"))
                .isTrue();
    }

    /**
     * O Gemini recusa o schema INTEIRO ao encontrar campo desconhecido — um {@code $schema} perdido
     * derrubaria todas as ferramentas da rodada, não só a que o trouxe.
     */
    @Test
    void stripsSchemaFieldsGeminiRejects() throws Exception {
        String tools = "[{\"type\":\"function\",\"function\":{\"name\":\"x\",\"parameters\":{"
                + "\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\","
                + "\"additionalProperties\":false,\"properties\":{\"a\":{\"type\":\"string\",\"default\":\"z\"}}}}}]";

        ObjectNode request = GeminiModelTransport.toGeminiRequest(canonical(tools, "user", "oi"), MAPPER);

        JsonNode parameters = request.path("tools")
                .path(0)
                .path("functionDeclarations")
                .path(0)
                .path("parameters");
        assertThat(parameters.has("$schema")).isFalse();
        assertThat(parameters.has("additionalProperties")).isFalse();
        assertThat(parameters.path("properties").path("a").has("default")).isFalse();
        assertThat(parameters.path("type").asText()).isEqualTo("object");
    }

    /**
     * O caso real que derrubou uma rodada inteira com HTTP 400: exclusiveMinimum/exclusiveMaximum,
     * que a lista de proibidos anterior nao previa. Lista de proibidos nunca fecha — o JSON Schema
     * tem dezenas de palavras que o Gemini nao conhece, e uma so invalida TODAS as ferramentas.
     */
    @Test
    void dropsJsonSchemaFieldsGeminiDoesNotKnow() throws Exception {
        String tools = "[{\"type\":\"function\",\"function\":{\"name\":\"x\",\"parameters\":{"
                + "\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"number\","
                + "\"exclusiveMinimum\":0,\"exclusiveMaximum\":100,\"multipleOf\":2,"
                + "\"description\":\"quantidade\",\"minimum\":1}}}}}]";

        ObjectNode request = GeminiModelTransport.toGeminiRequest(canonical(tools, "user", "oi"), MAPPER);

        JsonNode n = request.path("tools")
                .path(0)
                .path("functionDeclarations")
                .path(0)
                .path("parameters")
                .path("properties")
                .path("n");
        assertThat(n.has("exclusiveMinimum")).isFalse();
        assertThat(n.has("exclusiveMaximum")).isFalse();
        assertThat(n.has("multipleOf")).isFalse();
        // O que o Gemini entende continua.
        assertThat(n.path("type").asText()).isEqualTo("number");
        assertThat(n.path("description").asText()).isEqualTo("quantidade");
        assertThat(n.path("minimum").asInt()).isEqualTo(1);
    }

    // Nome de propriedade e livre: nao pode ser filtrado como se fosse palavra de schema.
    @Test
    void keepsPropertyNamesEvenWhenTheyLookLikeSchemaWords() throws Exception {
        String tools = "[{\"type\":\"function\",\"function\":{\"name\":\"x\",\"parameters\":{"
                + "\"type\":\"object\",\"properties\":{\"default\":{\"type\":\"string\"},"
                + "\"multipleOf\":{\"type\":\"string\"}}}}}]";

        ObjectNode request = GeminiModelTransport.toGeminiRequest(canonical(tools, "user", "oi"), MAPPER);

        JsonNode properties = request.path("tools")
                .path(0)
                .path("functionDeclarations")
                .path(0)
                .path("parameters")
                .path("properties");
        assertThat(properties.has("default")).isTrue();
        assertThat(properties.has("multipleOf")).isTrue();
    }

    @Test
    void renamesAssistantToModelAndLiftsSystemPrompt() throws Exception {
        ObjectNode request = GeminiModelTransport.toGeminiRequest(
                canonical(null, "system", "Voce e o Avento.", "user", "oi", "assistant", "ola"), MAPPER);

        assertThat(request.path("systemInstruction")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo("Voce e o Avento.");
        assertThat(request.path("contents")).hasSize(2);
        assertThat(request.path("contents").get(1).path("role").asText()).isEqualTo("model");
    }

    // Resultado de ferramenta precisa voltar como functionResponse; texto solto o modelo ignora.
    @Test
    void sendsToolResultsAsFunctionResponse() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "gemini-2.0-flash");
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "user").put("content", "que horas sao?");
        messages.addObject().put("role", "tool").put("name", "get_time").put("content", "{\"hora\":\"14:32\"}");
        request.putArray("tools");

        ObjectNode gemini = GeminiModelTransport.toGeminiRequest(request, MAPPER);

        JsonNode response = gemini.path("contents").get(1).path("parts").get(0).path("functionResponse");
        assertThat(response.path("name").asText()).isEqualTo("get_time");
        assertThat(response.path("response").path("result").asText()).contains("14:32");
    }

    // A chamada que o assistente fez tem de voltar no historico, senao o modelo repete a ferramenta.
    @Test
    void keepsTheAssistantToolCallInHistory() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "gemini-2.0-flash");
        ArrayNode messages = request.putArray("messages");
        ObjectNode assistant = messages.addObject();
        assistant.put("role", "assistant").put("content", "");
        assistant
                .putArray("tool_calls")
                .addObject()
                .putObject("function")
                .put("name", "read_file")
                .put("arguments", "{\"path\":\"pom.xml\"}");
        request.putArray("tools");

        ObjectNode gemini = GeminiModelTransport.toGeminiRequest(request, MAPPER);

        JsonNode call = gemini.path("contents").get(0).path("parts").get(0).path("functionCall");
        assertThat(call.path("name").asText()).isEqualTo("read_file");
        assertThat(call.path("args").path("path").asText()).isEqualTo("pom.xml");
    }

    // ------------------------------------------------------------------ volta

    @Test
    void convertsTextEventIntoTheInternalShape() throws Exception {
        String event = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Ola\"}]}}]}";

        JsonNode line = MAPPER.readTree(GeminiModelTransport.toInternalChunk(event, MAPPER));

        assertThat(line.path("message").path("content").asText()).isEqualTo("Ola");
    }

    // O agente le tool_calls[].function.arguments como STRING JSON — mesmo contrato do Ollama.
    @Test
    void convertsFunctionCallIntoToolCalls() throws Exception {
        String event = "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":"
                + "{\"name\":\"read_file\",\"args\":{\"path\":\"pom.xml\"}}}]}}]}";

        JsonNode line = MAPPER.readTree(GeminiModelTransport.toInternalChunk(event, MAPPER));

        JsonNode call = line.path("message").path("tool_calls").get(0).path("function");
        assertThat(call.path("name").asText()).isEqualTo("read_file");
        assertThat(call.path("arguments").isTextual()).isTrue();
        assertThat(MAPPER.readTree(call.path("arguments").asText()).path("path").asText())
                .isEqualTo("pom.xml");
    }

    @Test
    void ignoresEventsWithoutTextOrCall() {
        assertThat(GeminiModelTransport.toInternalChunk("{\"usageMetadata\":{\"totalTokenCount\":7}}", MAPPER))
                .isNull();
        assertThat(GeminiModelTransport.toInternalChunk("[DONE]", MAPPER)).isNull();
        assertThat(GeminiModelTransport.toInternalChunk("{\"candidates\":[{\"cont", MAPPER))
                .isNull();
    }

    /**
     * A imagem anexada chega no formato do Ollama ({@code images} com base64 puro). Sem tradução
     * para {@code inlineData} ela era descartada em silêncio: a tela mostrava a miniatura enviada e
     * o modelo respondia que não tinha recebido imagem nenhuma.
     */
    @Test
    void translatesAttachedImagesIntoInlineData() throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", "gemini-2.0-flash");
        ObjectNode user = request.putArray("messages").addObject();
        user.put("role", "user").put("content", "o que tem nesta foto?");
        user.putArray("images").add("iVBORw0KGgoAAAANSUhEUg==");
        request.putArray("tools");

        ObjectNode geminiRequest = GeminiModelTransport.toGeminiRequest(request, MAPPER);

        JsonNode parts = geminiRequest.path("contents").get(0).path("parts");
        assertThat(parts.get(0).path("inlineData").path("mimeType").asText()).isEqualTo("image/png");
        assertThat(parts.get(0).path("inlineData").path("data").asText()).isEqualTo("iVBORw0KGgoAAAANSUhEUg==");
        assertThat(parts.get(1).path("text").asText()).isEqualTo("o que tem nesta foto?");
    }

    /**
     * O uso vem ACUMULADO em todo evento do stream. Contabilizar cada um multiplicaria os tokens da
     * rodada, então só o evento que fecha a resposta conta — e ele volta no formato do Ollama, que é
     * o que o agente sabe ler.
     */
    @Test
    void reportsTokenUsageOnlyOnTheFinalEvent() throws Exception {
        String partial = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Ola\"}]}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":1}}";
        JsonNode intermediate = MAPPER.readTree(GeminiModelTransport.toInternalChunk(partial, MAPPER));
        assertThat(intermediate.path("done").asBoolean()).isFalse();
        assertThat(intermediate.has("eval_count")).isFalse();

        String last = "{\"modelVersion\":\"gemini-2.0-flash\",\"candidates\":[{\"finishReason\":\"STOP\","
                + "\"content\":{\"parts\":[{\"text\":\"!\"}]}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":42}}";
        JsonNode line = MAPPER.readTree(GeminiModelTransport.toInternalChunk(last, MAPPER));

        assertThat(line.path("message").path("content").asText()).isEqualTo("!");
        assertThat(line.path("done").asBoolean()).isTrue();
        assertThat(line.path("prompt_eval_count").asInt()).isEqualTo(10);
        assertThat(line.path("eval_count").asInt()).isEqualTo(42);
        assertThat(line.path("model").asText()).isEqualTo("gemini-2.0-flash");
    }

    // O evento que fecha pode não trazer texto nenhum; a contagem não pode se perder com ele.
    @Test
    void reportsTokenUsageWhenTheFinalEventCarriesNoText() throws Exception {
        String last = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[]}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":3}}";

        JsonNode line = MAPPER.readTree(GeminiModelTransport.toInternalChunk(last, MAPPER));

        assertThat(line.path("done").asBoolean()).isTrue();
        assertThat(line.path("eval_count").asInt()).isEqualTo(3);
    }
}
