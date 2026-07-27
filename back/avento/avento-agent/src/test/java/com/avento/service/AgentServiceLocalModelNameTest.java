package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Com provedor remoto ativo, o modelo escolhido no chat era descartado em favor do gravado. O log
 * mostrava o problema em duas linhas seguidas:
 *
 * <pre>
 * Agent run starting for chat 34 with model gemini-3.1-pro-preview
 * Agent round 1 starting build for model gemini-2.5-flash
 * </pre>
 *
 * <p>Trocar de modelo no chat não tinha efeito nenhum. O gravado só deve entrar quando o pedido vem
 * sem modelo, ou traz um nome local que o provedor remoto não conhece — mandar {@code qwen3.5:9b}
 * para o Gemini foi o que gerou o primeiro 404.
 */
class AgentServiceLocalModelNameTest {

    // Modelo do Ollama usa familia:tag; o de nuvem, nao.
    @Test
    void recognisesOllamaStyleNames() {
        assertThat(AgentService.isLocalModelName("qwen3.5:9b")).isTrue();
        assertThat(AgentService.isLocalModelName("granite4.1:8b")).isTrue();
        assertThat(AgentService.isLocalModelName("qwen2.5vl:7b")).isTrue();
    }

    @Test
    void treatsCloudNamesAsRemote() {
        assertThat(AgentService.isLocalModelName("gemini-3.1-pro-preview")).isFalse();
        assertThat(AgentService.isLocalModelName("gemini-2.0-flash")).isFalse();
        assertThat(AgentService.isLocalModelName("gpt-4o-mini")).isFalse();
        assertThat(AgentService.isLocalModelName("claude-sonnet-4")).isFalse();
    }

    @Test
    void handlesEmptyAndNull() {
        assertThat(AgentService.isLocalModelName(null)).isFalse();
        assertThat(AgentService.isLocalModelName("")).isFalse();
    }
}
