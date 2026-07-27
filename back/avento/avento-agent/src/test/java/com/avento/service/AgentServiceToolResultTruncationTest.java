package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * O resultado da ferramenta ia inteiro para o histórico. Um {@code fetch} devolve a página web
 * completa, e o prompt cresce a cada rodada porque tudo é relido. Medido nesta máquina: ler custa
 * ~4,4ms por token, então uma pesquisa com três páginas chegava a quase um minuto de silêncio antes
 * do primeiro caractere — o que parecia "pensando muito" era releitura.
 */
class AgentServiceToolResultTruncationTest {

    private static final int LIMIT = 1000;

    @Test
    void keepsShortResultsUntouched() {
        String small = "{\"ok\":true,\"body\":\"resposta curta\"}";

        assertThat(AgentService.truncateToolResultForHistory(small, LIMIT)).isEqualTo(small);
    }

    @Test
    void cutsLongResultsToTheConfiguredLimit() {
        String page = "conteudo da pagina ".repeat(500);

        String truncated = AgentService.truncateToolResultForHistory(page, LIMIT);

        assertThat(truncated).hasSizeLessThan(page.length());
        assertThat(truncated).startsWith("conteudo da pagina");
    }

    // Sem o marcador, o modelo trata o trecho cortado como o documento inteiro e responde com
    // confianca sobre o que nao leu — foi assim que uma pesquisa virou supercomputador inventado.
    @Test
    void tellsTheModelThatSomethingWasCut() {
        String page = "x".repeat(5000);

        String truncated = AgentService.truncateToolResultForHistory(page, LIMIT);

        assertThat(truncated).contains("truncado pelo Avento");
        assertThat(truncated).contains("5000 caracteres");
        assertThat(truncated).contains("chame a ferramenta de novo");
    }

    @Test
    void handlesNullResult() {
        assertThat(AgentService.truncateToolResultForHistory(null, LIMIT)).isNull();
    }

    @Test
    void keepsResultExactlyAtTheLimit() {
        String exact = "y".repeat(LIMIT);

        assertThat(AgentService.truncateToolResultForHistory(exact, LIMIT)).isEqualTo(exact);
    }
}
