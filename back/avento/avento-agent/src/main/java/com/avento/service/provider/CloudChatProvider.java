package com.avento.service.provider;

import com.fasterxml.jackson.databind.node.ArrayNode;
import reactor.core.publisher.Flux;

/**
 * Provedor de chat em nuvem.
 *
 * <p>Existe porque trocar a URL do Ollama não bastaria: o corpo da requisição do fluxo local é
 * nativo do Ollama ({@code messages}/{@code tools}/{@code options.num_ctx}), e cada provedor de
 * nuvem tem o seu formato. A tradução vive na implementação, e o resto do Avento continua vendo o
 * mesmo formato de chunk que já consome.
 *
 * <p>Escopo desta primeira etapa: <b>conversa apenas, sem ferramentas</b>. Tool calling é onde as
 * diferenças entre provedores mais machucam (o Gemini devolve {@code functionCall} dentro de
 * {@code parts}, não {@code tool_calls} num delta), e vale construir sobre uma base já validada.
 */
public interface CloudChatProvider {

    /** Nome do provedor como gravado na configuração, ex.: {@code GEMINI}. */
    String providerName();

    /**
     * Envia a conversa e devolve chunks no formato que o Avento já consome
     * ({@code {"choices":[{"delta":{"content":"..."}}]}}).
     *
     * @param messages histórico no formato interno (role/content)
     * @param model nome do modelo do provedor, ex.: {@code gemini-2.5-flash}
     * @param apiKey chave do usuário; nunca vai para log nem para URL
     */
    Flux<String> streamChat(ArrayNode messages, String model, String apiKey);
}
