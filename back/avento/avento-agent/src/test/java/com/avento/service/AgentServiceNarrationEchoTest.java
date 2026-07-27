package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * O preâmbulo que o modelo escreve antes de chamar uma ferramenta voltava inteiro para o histórico.
 * Na rodada seguinte ele lia a própria frase, copiava, e na outra lia duas cópias — foi assim que
 * uma análise de projeto virou quinze vezes "Vou continuar a análise do projeto! 📋" e nenhuma
 * conclusão, em 310 segundos.
 */
class AgentServiceNarrationEchoTest {

    @Test
    void truncatesLongNarrationWhenTheRoundCalledATool() {
        String narration = "Vou continuar a análise do projeto! ".repeat(30);

        String stored = AgentService.narrationForHistory(narration, true);

        assertThat(stored).hasSizeLessThan(narration.length());
        assertThat(stored).endsWith("…");
    }

    @Test
    void keepsShortNarrationIntact() {
        String narration = "Vou ler o package.json para entender as dependências.";

        assertThat(AgentService.narrationForHistory(narration, true)).isEqualTo(narration);
    }

    // Sem tool call o texto É a resposta final: truncar aqui apagaria conteúdo real do usuário.
    @Test
    void neverTruncatesTheFinalAnswerText() {
        String answer = "Análise completa do monorepo. ".repeat(30);

        assertThat(AgentService.narrationForHistory(answer, false)).isEqualTo(answer);
    }

    @Test
    void handlesEmptyNarration() {
        assertThat(AgentService.narrationForHistory("", true)).isEmpty();
    }
}
