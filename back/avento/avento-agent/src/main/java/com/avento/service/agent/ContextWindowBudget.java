package com.avento.service.agent;

/**
 * Divide a janela que o modelo recebeu entre o prompt e a geração da rodada.
 *
 * <p>Ollama contabiliza texto visível, chamadas de ferramenta e o raciocínio interno do modo
 * Thinking no mesmo {@code num_ctx}. Reservar somente uma resposta "normal" permite que o
 * modelo gaste a sobra pensando e termine a mensagem no meio. Por isso a reserva é todo o
 * {@code num_predict} efetivamente enviado ao provedor.
 */
record ContextWindowBudget(int contextTokens, int generationTokens, int promptTokens) {

    private static final int MINIMUM_CONTEXT_TOKENS = 2_048;
    private static final int MINIMUM_PROMPT_TOKENS = 256;
    private static final int MINIMUM_GENERATION_TOKENS = 256;
    private static final int SAFETY_MARGIN_TOKENS = 256;

    static ContextWindowBudget forWindow(int requestedContextTokens, int requestedGenerationTokens) {
        int contextTokens = Math.max(MINIMUM_CONTEXT_TOKENS, requestedContextTokens);
        int generationTokens = Math.max(
                MINIMUM_GENERATION_TOKENS,
                Math.min(
                        requestedGenerationTokens,
                        contextTokens - MINIMUM_PROMPT_TOKENS - SAFETY_MARGIN_TOKENS));
        int promptTokens = contextTokens - generationTokens - SAFETY_MARGIN_TOKENS;
        return new ContextWindowBudget(contextTokens, generationTokens, promptTokens);
    }
}
