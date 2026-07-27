package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * O 429 do Gemini chega como um muro de JSON: violações repetidas quatro vezes, links de
 * documentação e metadados de quota. Enterrado ali está o que importa — se a cota é ZERO, esperar
 * não resolve nada, porque o modelo simplesmente não existe no plano da conta.
 */
class AgentServiceQuotaHintTest {

    private static final String LIMITE_ZERO =
            "O provedor retornou HTTP 429: { \"error\": { \"code\": 429, \"message\": \"You exceeded your"
                    + " current quota... * Quota exceeded for metric:"
                    + " generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0,"
                    + " model: gemini-3.1-pro\", \"status\": \"RESOURCE_EXHAUSTED\","
                    + " \"details\": [{ \"retryDelay\": \"51s\" }] } }";

    private static final String LIMITE_TEMPORARIO =
            "O provedor retornou HTTP 429: { \"error\": { \"code\": 429, \"message\": \"Rate limit\","
                    + " \"details\": [{ \"retryDelay\": \"20s\" }] } }";

    // Cota zero e outra coisa: nao adianta esperar, o modelo esta fora do plano.
    @Test
    void explainsThatAZeroQuotaMeansTheModelIsNotInThePlan() {
        String hint = AgentService.quotaHint(LIMITE_ZERO, "gemini-3.1-pro-preview");

        assertThat(hint).contains("gemini-3.1-pro-preview");
        assertThat(hint).contains("zero");
        assertThat(hint).contains("faturamento");
        // O criterio pratico separa os dois grupos: pro exige faturamento, flash e do tier livre.
        assertThat(hint).contains("flash");
        assertThat(hint).doesNotContain("tente de novo");
    }

    @Test
    void tellsHowLongToWaitOnARealRateLimit() {
        String hint = AgentService.quotaHint(LIMITE_TEMPORARIO, "gemini-2.0-flash");

        assertThat(hint).contains("20s");
        assertThat(hint).doesNotContain("faturamento");
    }

    @Test
    void survivesA429WithoutRetryDelay() {
        String hint = AgentService.quotaHint("O provedor retornou HTTP 429: sem corpo", "gemini-2.0-flash");

        assertThat(hint).contains("Limite de uso");
    }
}
