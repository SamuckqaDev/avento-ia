package com.avento.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Transporte para qualquer servidor que fale o protocolo OpenAI: vLLM, LM Studio, DGX, TGI e a
 * própria OpenAI. Um transporte cobre todos porque o protocolo é o mesmo — só o endereço muda.
 *
 * <p>Diferenças traduzidas em relação ao formato canônico do Avento:
 *
 * <ul>
 *   <li>corpo da requisição: {@code {model, messages, tools, stream:true, temperature?, max_tokens?}};
 *   <li>histórico do assistente: {@code function.arguments} é STRING JSON, não objeto;
 *   <li>resultado de ferramenta: papel {@code tool} com {@code tool_call_id};
 *   <li>imagem anexada: {@code content} em partes, com data URI;
 *   <li>resposta: SSE com {@code choices[0].delta}, e a chamada de ferramenta chega FATIADA.
 * </ul>
 *
 * <p>Papéis de mensagem ({@code system}, {@code user}, {@code assistant}, {@code tool}) não
 * precisam de tradução — o protocolo OpenAI usa os mesmos nomes.
 */
@Service
public class OpenAiCompatibleModelTransport implements ModelTransport {

    private final ObjectMapper mapper;
    private final WebClient webClient;
    private final Duration timeout;

    public OpenAiCompatibleModelTransport(
            ObjectMapper mapper,
            @Value("${avento.provider.openai-compatible.timeout-seconds:300}") long timeoutSeconds) {
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.webClient = ProviderWebClients.streaming();
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.OPENAI_COMPATIBLE;
    }

    @Override
    public Flux<String> stream(ObjectNode canonicalRequest, String baseUrl, String apiKey) {
        ObjectNode body = toOpenAiRequest(canonicalRequest, mapper);
        String url = (baseUrl == null || baseUrl.isBlank() ? ProviderKind.OPENAI_COMPATIBLE.defaultBaseUrl() : baseUrl)
                        .replaceAll("/+$", "")
                + "/v1/chat/completions";

        // Um remontador por assinatura, criado dentro do defer: ele guarda os pedaços da chamada de
        // ferramenta, e esse estado nao pode sobreviver de uma conversa para a seguinte.
        return Flux.defer(() -> {
            StreamAssembler assembler = new StreamAssembler(mapper);
            WebClient.RequestBodySpec post = webClient.post().uri(url).contentType(MediaType.APPLICATION_JSON);

            // Chave em header, nunca em query string: URL entra em log de proxy e de servidor.
            // Opcional aqui, ao contrario do Gemini e do Anthropic: servidor da propria rede (vLLM,
            // LM Studio, DGX) normalmente nao exige nenhuma.
            if (apiKey != null && !apiKey.isBlank()) {
                post = post.header("Authorization", "Bearer " + apiKey);
            }

            return post.bodyValue(body.toString())
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(timeout)
                    .mapNotNull(assembler::consume)
                    .concatWith(Flux.defer(() -> {
                        String pending = assembler.flush();
                        return pending == null ? Flux.<String>empty() : Flux.just(pending);
                    }));
        });
    }

    // ------------------------------------------------------------------ ida

    /**
     * Converte a requisição canônica do Avento para o corpo da API OpenAI.
     *
     * <p>Estático e sem rede de propósito: é a parte que erra em silêncio, então precisa ser
     * testável sem chave e sem chamada externa.
     */
    static ObjectNode toOpenAiRequest(ObjectNode canonical, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", canonical.path("model").asText(""));
        body.put("stream", true);
        // Sem isto o evento final nao traz "usage", e a rodada inteira fica sem contagem de tokens.
        body.putObject("stream_options").put("include_usage", true);

        ArrayNode messages = body.putArray("messages");
        for (JsonNode message : canonical.path("messages")) {
            String role = message.path("role").asText("");
            String content = message.path("content").asText("");

            if ("tool".equals(role)) {
                // tool_call_id: o agente guarda o id quando disponível; sem ele, string vazia.
                ObjectNode out = messages.addObject();
                out.put("role", "tool");
                out.put("tool_call_id", message.path("tool_call_id").asText(""));
                out.put("content", content);
            } else if ("assistant".equals(role)) {
                messages.add(toAssistantMessage(message, content, mapper));
            } else {
                messages.add(withImages(role, content, message.path("images"), mapper));
            }
        }

        // Ferramentas: formato idêntico ao canônico — nenhuma tradução necessária.
        JsonNode tools = canonical.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            body.set("tools", tools.deepCopy());
        }

        // Parâmetros de geração opcionais. top_k e repeat_penalty ficam de fora de propósito: não
        // existem neste protocolo, e a OpenAI recusa a requisição inteira ao ver campo que não
        // conhece — perder o ajuste é melhor do que perder a conversa.
        JsonNode options = canonical.path("options");
        if (options.isObject()) {
            if (options.has("temperature")) {
                body.put("temperature", options.path("temperature").asDouble());
            }
            if (options.has("num_predict") && options.path("num_predict").asInt(0) > 0) {
                body.put("max_tokens", options.path("num_predict").asInt());
            }
            if (options.has("top_p")) {
                body.put("top_p", options.path("top_p").asDouble());
            }
        }

        return body;
    }

    /**
     * Mensagem do assistente, com as chamadas de ferramenta da rodada anterior.
     *
     * <p>O histórico interno guarda {@code arguments} como OBJETO, porque é assim que o Ollama o
     * aceita de volta. Aqui é o contrário: a OpenAI espera STRING e recusa o objeto. É a inversão
     * exata — sem ela, toda segunda rodada com ferramenta falha.
     */
    private static ObjectNode toAssistantMessage(JsonNode message, String content, ObjectMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        out.put("role", "assistant");
        out.put("content", content);

        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            return out;
        }
        ArrayNode outToolCalls = out.putArray("tool_calls");
        for (JsonNode call : toolCalls) {
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            ObjectNode outCall = outToolCalls.addObject();
            String id = call.path("id").asText("");
            outCall.put("id", id.isBlank() ? "call_" + outToolCalls.size() : id);
            outCall.put("type", "function");
            ObjectNode outFunction = outCall.putObject("function");
            outFunction.put("name", name);
            JsonNode arguments = function.path("arguments");
            outFunction.put("arguments", arguments.isTextual() ? arguments.asText("{}") : arguments.toString());
        }
        return out;
    }

    /**
     * Texto simples quando não há anexo; partes quando há.
     *
     * <p>Mandar sempre em partes seria mais uniforme e quebraria servidor compatível mais antigo,
     * que só aceita {@code content} como string.
     */
    private static ObjectNode withImages(String role, String content, JsonNode images, ObjectMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        out.put("role", role);
        if (!images.isArray() || images.isEmpty()) {
            out.put("content", content);
            return out;
        }
        ArrayNode parts = out.putArray("content");
        if (!content.isBlank()) {
            parts.addObject().put("type", "text").put("text", content);
        }
        for (JsonNode image : images) {
            ObjectNode part = parts.addObject();
            part.put("type", "image_url");
            part.putObject("image_url").put("url", Base64Images.dataUri(image.asText("")));
        }
        return out;
    }

    // ---------------------------------------------------------------- volta

    /**
     * Remonta o streaming da OpenAI no formato interno.
     *
     * <p>Tem estado porque o protocolo obriga: o nome da ferramenta vem num evento e os argumentos
     * chegam picados nos seguintes ({@code {"pa} … {th":"pom} … {.xml"}}). O agente, do outro lado,
     * espera a chamada INTEIRA de uma vez — é assim que o Ollama entrega, e é o que
     * {@code captureNativeToolCalls} sabe ler.
     *
     * <p>Repassar pedaço por pedaço não dá "quase certo": o primeiro fragmento vira uma chamada com
     * argumentos vazios e os demais são descartados por não trazerem nome. A ferramenta executa com
     * argumento nenhum, e o erro aparece longe daqui.
     */
    static final class StreamAssembler {

        private static final Logger logger = LoggerFactory.getLogger(StreamAssembler.class);

        private final ObjectMapper mapper;
        // Ordenado: é a ordem em que o modelo pediu as ferramentas, e ela importa para quem lê a
        // aprovação na sequência certa.
        private final Map<Integer, ObjectNode> pendingCalls = new LinkedHashMap<>();
        private String model = "";

        StreamAssembler(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        /** Linha no formato interno, ou null quando o evento não carrega nada aproveitável. */
        String consume(String rawEvent) {
            if (rawEvent == null || rawEvent.isBlank()) {
                return null;
            }
            // SSE chega como "data: {...}" ou "data: [DONE]". O decodificador do Spring já costuma
            // entregar só o payload, mas servidor compatível nem sempre segue o padrão.
            String data = rawEvent.trim();
            if (data.startsWith("data:")) {
                data = data.substring(5).trim();
            }
            if (data.isBlank() || "[DONE]".equals(data) || data.startsWith(":")) {
                return null;
            }

            try {
                JsonNode root = mapper.readTree(data);
                if (root.hasNonNull("model")) {
                    model = root.path("model").asText(model);
                }

                JsonNode choice = root.path("choices").path(0);
                JsonNode delta = choice.path("delta");
                collectToolCallFragments(delta.path("tool_calls"));

                ObjectNode line = mapper.createObjectNode();
                ObjectNode message = line.putObject("message");
                message.put("role", "assistant");
                boolean carries = false;

                // Modelo de raciocínio (DeepSeek, QwQ servidos por vLLM) manda o pensamento num campo
                // próprio. O agente já sabe reembrulhar isso como <think> para a interface.
                String reasoning = delta.path("reasoning_content").asText("");
                if (!reasoning.isEmpty()) {
                    message.put("thinking", reasoning);
                    carries = true;
                }
                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    message.put("content", content);
                    carries = true;
                }

                // A ferramenta só sai daqui quando o modelo declara que terminou de pedi-la.
                JsonNode finishReason = choice.path("finish_reason");
                if (!finishReason.isMissingNode()
                        && !finishReason.isNull()
                        && !finishReason.asText("").isBlank()) {
                    ArrayNode completed = drainToolCalls();
                    if (completed != null) {
                        message.set("tool_calls", completed);
                        carries = true;
                    }
                }

                if (appendUsage(line, root.path("usage"))) {
                    return line.toString();
                }
                if (!carries) {
                    return null;
                }
                line.put("done", false);
                return line.toString();
            } catch (Exception exception) {
                logger.debug("Evento OpenAI ignorado: {}", exception.getClass().getSimpleName());
                return null;
            }
        }

        /**
         * Chamada que sobrou ao fim do stream.
         *
         * <p>Servidor compatível nem sempre manda {@code finish_reason}. Sem esta rede de segurança
         * a ferramenta pedida seria descartada e o turno terminaria em silêncio absoluto.
         */
        String flush() {
            ArrayNode completed = drainToolCalls();
            if (completed == null) {
                return null;
            }
            ObjectNode line = mapper.createObjectNode();
            ObjectNode message = line.putObject("message");
            message.put("role", "assistant");
            message.put("content", "");
            message.set("tool_calls", completed);
            line.put("done", false);
            return line.toString();
        }

        private void collectToolCallFragments(JsonNode fragments) {
            if (!fragments.isArray()) {
                return;
            }
            for (JsonNode fragment : fragments) {
                int index = fragment.path("index").asInt(0);
                ObjectNode pending = pendingCalls.computeIfAbsent(index, key -> {
                    ObjectNode call = mapper.createObjectNode();
                    call.putObject("function").put("name", "").put("arguments", "");
                    return call;
                });
                if (fragment.hasNonNull("id")) {
                    pending.put("id", fragment.path("id").asText());
                }
                JsonNode function = fragment.path("function");
                ObjectNode pendingFunction = (ObjectNode) pending.path("function");
                if (function.hasNonNull("name")) {
                    pendingFunction.put("name", function.path("name").asText());
                }
                if (function.hasNonNull("arguments")) {
                    pendingFunction.put(
                            "arguments",
                            pendingFunction.path("arguments").asText("")
                                    + function.path("arguments").asText());
                }
            }
        }

        /** Entrega as chamadas remontadas e esvazia o acumulador, para não emitir duas vezes. */
        private ArrayNode drainToolCalls() {
            if (pendingCalls.isEmpty()) {
                return null;
            }
            ArrayNode calls = mapper.createArrayNode();
            for (ObjectNode pending : pendingCalls.values()) {
                if (pending.path("function").path("name").asText("").isBlank()) {
                    continue;
                }
                // O agente lê arguments como STRING JSON — mesmo contrato do Ollama e do Gemini.
                ObjectNode function = (ObjectNode) pending.path("function");
                if (function.path("arguments").asText("").isBlank()) {
                    function.put("arguments", "{}");
                }
                calls.add(pending);
            }
            pendingCalls.clear();
            return calls.isEmpty() ? null : calls;
        }

        /** O uso da rodada volta no mesmo formato do Ollama: {@code done} + {@code eval_count}. */
        private boolean appendUsage(ObjectNode line, JsonNode usage) {
            if (!usage.isObject()) {
                return false;
            }
            line.put("done", true);
            line.put("model", model);
            line.put("prompt_eval_count", usage.path("prompt_tokens").asInt(0));
            line.put("eval_count", usage.path("completion_tokens").asInt(0));
            return true;
        }
    }
}
