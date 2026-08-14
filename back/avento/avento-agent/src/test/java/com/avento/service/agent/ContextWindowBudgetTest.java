package com.avento.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContextWindowBudgetTest {

    @Test
    void reservaTodaAGeracaoParaResponderMesmoComThinkingLigado() {
        ContextWindowBudget budget = ContextWindowBudget.forWindow(16_384, 4_096);

        assertThat(budget.contextTokens()).isEqualTo(16_384);
        assertThat(budget.generationTokens()).isEqualTo(4_096);
        assertThat(budget.promptTokens()).isEqualTo(12_032);
    }

    @Test
    void reduzAGeracaoQuandoOModeloTemUmaJanelaMenor() {
        ContextWindowBudget budget = ContextWindowBudget.forWindow(2_048, 4_096);

        assertThat(budget.generationTokens()).isEqualTo(1_536);
        assertThat(budget.promptTokens()).isEqualTo(256);
    }
}
