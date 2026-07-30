package com.avento.service.support;

/**
 * O que é guardado do texto de uma rodada quando ele volta para o histórico do modelo.
 *
 * <p>As duas regras aqui existem por causa do mesmo problema: o histórico é reenviado inteiro a cada
 * rodada, então tudo que entra nele é pago de novo em contexto — e, pior, é lido pelo modelo como
 * exemplo do que fazer em seguida.
 */
public final class HistoryText {

    /**
     * O preâmbulo antes de uma chamada de ferramenta ("Vou continuar a análise...") voltava inteiro
     * para o histórico. Na rodada seguinte o modelo via a própria frase, copiava, e na outra via duas
     * cópias — eco que enche o contexto e produz a resposta repetida que o usuário vê.
     *
     * <p>O que o modelo precisa reter da rodada é o tool_call e o resultado, não o floreio. Guarda-se
     * um trecho curto porque às vezes vem um raciocínio junto que vale manter.
     */
    private static final int MAX_NARRATION_CHARS_KEPT = 240;

    private HistoryText() {}

    public static String narrationForHistory(String assistantText, boolean roundCalledATool) {
        if (!roundCalledATool || assistantText.length() <= MAX_NARRATION_CHARS_KEPT) {
            return assistantText;
        }
        return assistantText.substring(0, MAX_NARRATION_CHARS_KEPT).stripTrailing() + "…";
    }

    /**
     * Corta o resultado da ferramenta, dizendo ao modelo o que fazer em vez de deixá-lo supor.
     *
     * <p>Um corte silencioso faz o modelo tratar o pedaço como o todo e inventar o resto. A nota
     * final é a diferença entre "isto está incompleto, refine a busca" e uma resposta confiante
     * baseada em metade dos dados.
     *
     * <p>O teto acompanha a janela do modelo: com 1M de contexto, cortar em 4000 chars joga fora
     * pesquisa que caberia; com 8k, 4000 já é demais.
     */
    public static String truncateToolResultForHistory(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars)
                + "\n\n[...truncado pelo Avento: o resultado tinha " + content.length()
                + " caracteres. Se precisar do trecho que faltou, chame a ferramenta de novo com um"
                + " filtro mais específico em vez de supor o conteúdo.]";
    }
}
