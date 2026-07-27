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

/**
 * Conversa com a API do Gemini, traduzindo nos dois sentidos.
 *
 * <p>Diferenças que obrigam a tradução, e não apenas uma troca de URL:
 *
 * <ul>
 *   <li>o papel do assistente chama-se {@code model}, não {@code assistant};
 *   <li>o prompt de sistema não é uma mensagem — vai em {@code systemInstruction};
 *   <li>o texto fica em {@code parts[].text} dentro de {@code contents}, não em {@code content};
 *   <li>o streaming é SSE com um JSON por evento, não NDJSON.
 * </ul>
 *
 * <p>Sem ferramentas nesta etapa: mensagens {@code role=tool} são descartadas na tradução, porque
 * enviá-las sem as declarações correspondentes confundiria o modelo.
 */
@Service
public class GeminiChatProvider implements CloudChatProvider {

    private static final Logger logger = LoggerFactory.getLogger(GeminiChatProvider.class);
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public GeminiChatProvider(
            ObjectMapper mapper,
            @Value("${avento.provider.gemini.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${avento.provider.gemini.timeout-seconds:120}") long timeoutSeconds) {
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                // Respostas longas estouram o buffer padrão de 256KB do WebClient.
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    @Override
    public String providerName() {
        return "GEMINI";
    }

    @Override
    public Flux<String> streamChat(ArrayNode messages, String model, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just(contentChunk("\n> ❌ Nenhuma chave de API configurada para o Gemini.\n"));
        }

        ObjectNode request = toGeminiRequest(messages, mapper);
        String path = "/v1beta/models/" + model.trim() + ":streamGenerateContent?alt=sse";

        return webClient
                .post()
                .uri(path)
                // Chave em HEADER, nunca em query string: URL entra em log de proxy e de servidor.
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(timeout)
                .mapNotNull(event -> textFromStreamEvent(event, mapper))
                .map(this::contentChunk)
                .onErrorResume(error -> {
                    // A mensagem do Google pode conter eco do payload; loga só o tipo e o resumo.
                    logger.warn("Falha ao falar com o Gemini ({}): {}", model, error.getMessage());
                    return Flux.just(contentChunk("\n> ❌ Falha ao falar com o Gemini: " + shortReason(error) + "\n"));
                });
    }

    /**
     * Converte o histórico interno para o corpo do Gemini.
     *
     * <p>Estático e sem rede de propósito: é a parte que erra em silêncio, então precisa ser
     * testável sem chave e sem chamada externa.
     */
    static ObjectNode toGeminiRequest(ArrayNode messages, ObjectMapper mapper) {
        ObjectNode request = mapper.createObjectNode();
        ArrayNode contents = request.putArray("contents");
        StringBuilder systemText = new StringBuilder();

        for (JsonNode message : messages) {
            String role = message.path("role").asText("");
            String content = message.path("content").asText("");
            if (content.isBlank()) {
                continue;
            }
            switch (role) {
                case "system" -> {
                    if (systemText.length() > 0) {
                        systemText.append("\n\n");
                    }
                    systemText.append(content);
                }
                case "user" -> addContent(contents, "user", content, mapper);
                case "assistant" -> addContent(contents, "model", content, mapper);
                // "tool" fica de fora: sem functionDeclarations o resultado solto confunde o modelo.
                default -> {}
            }
        }

        if (systemText.length() > 0) {
            ObjectNode instruction = request.putObject("systemInstruction");
            instruction.putArray("parts").addObject().put("text", systemText.toString());
        }
        return request;
    }

    private static void addContent(ArrayNode contents, String role, String text, ObjectMapper mapper) {
        ObjectNode entry = contents.addObject();
        entry.put("role", role);
        entry.putArray("parts").addObject().put("text", text);
    }

    /**
     * Extrai o texto de um evento SSE do Gemini. Devolve null quando o evento não carrega texto
     * (keep-alive, metadados de segurança, contagem de tokens) para o chunk não virar linha vazia.
     */
    static String textFromStreamEvent(String rawEvent, ObjectMapper mapper) {
        if (rawEvent == null || rawEvent.isBlank() || "[DONE]".equals(rawEvent.trim())) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(rawEvent);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) {
                text.append(part.path("text").asText(""));
            }
            String joined = text.toString();
            return joined.isEmpty() ? null : joined;
        } catch (Exception exception) {
            return null;
        }
    }

    private String shortReason(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    private String contentChunk(String content) {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("choices").addObject().putObject("delta").put("content", content);
        return root.toString();
    }
}
