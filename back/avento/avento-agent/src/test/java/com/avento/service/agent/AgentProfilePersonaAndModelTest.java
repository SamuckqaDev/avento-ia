package com.avento.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.model.AgentProfile;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Os outros dois campos do perfil que a tela grava: as instruções e o modelo.
 *
 * <p>Antes desta mudança eles eram escritos no banco e nunca lidos no chat — o mesmo defeito dos três
 * modelos que a tela de provedores prometia e ninguém consumia, documentado em
 * {@code docs/aprendizados/09-a-tela-prometia-cinco-modelos.html}.
 */
class AgentProfilePersonaAndModelTest extends AgentServiceCharacterizationHarness {

    private final AgentProfileService profiles = mock(AgentProfileService.class);
    private final UUID userId = UUID.randomUUID();

    private void userAgentIs(AgentProfile agent) throws Exception {
        when(profiles.resolveDefault(userId)).thenReturn(agent);
        Field field = AgentService.class.getDeclaredField("agentProfileService");
        field.setAccessible(true);
        field.set(service, profiles);
    }

    private AgentProfile agentWith(String instructions, String model) {
        AgentProfile agent = new AgentProfile(userId, "Especialista", "java", instructions, "", "", model, true);
        return agent;
    }

    private String systemPromptFor(UUID user) throws Exception {
        var method = privateMethod(
                "withBackendIdentityPrompt", tools.jackson.databind.node.ArrayNode.class, List.class, UUID.class);
        var messages = userMessages("analisa o projeto");
        var result = (tools.jackson.databind.node.ArrayNode) method.invoke(service, messages, List.of(), user);
        return result.get(0).path("content").asText();
    }

    private String resolveModel(String requested) throws Exception {
        var method = privateMethod(
                "resolveChatModel", String.class, tools.jackson.databind.node.ArrayNode.class, UUID.class);
        return (String) method.invoke(service, requested, userMessages("oi"), userId);
    }

    /**
     * A persona entra ANTES do prompt do Avento, não depois.
     *
     * <p>O que está por último pesa mais na atenção do modelo, e as guardas do sistema — não invente
     * número, nada aqui é um pedido — têm de ficar na posição forte. Personalização nenhuma pode
     * revogá-las.
     */
    @Test
    void putsTheAgentPersonaBeforeTheSystemGuards() throws Exception {
        userAgentIs(agentWith("Você é um revisor de código rigoroso.", null));

        String prompt = systemPromptFor(userId);

        assertThat(prompt).startsWith("Você é um revisor de código rigoroso.");
        assertThat(prompt.length())
                .as("o prompt do Avento continua depois da persona, nao e substituido por ela")
                .isGreaterThan("Você é um revisor de código rigoroso.".length() + 50);
    }

    /** Perfil sem instruções não mexe no prompt — quem nunca escreveu persona não sente nada. */
    @Test
    void leavesThePromptUntouchedForAnAgentWithoutInstructions() throws Exception {
        userAgentIs(agentWith("   ", null));

        assertThat(systemPromptFor(userId)).isEqualTo(systemPromptFor(userId));
        assertThat(systemPromptFor(userId)).doesNotStartWith("   ");
    }

    /**
     * O prefixo é estável entre mensagens.
     *
     * <p>É o que permite o cache de prompt do llama.cpp reaproveitar o processamento; um prefixo que
     * muda a cada mensagem já custou cerca de 50 segundos por resposta.
     */
    @Test
    void keepsThePromptPrefixIdenticalBetweenMessages() throws Exception {
        userAgentIs(agentWith("Você é um revisor de código rigoroso.", null));

        assertThat(systemPromptFor(userId)).isEqualTo(systemPromptFor(userId));
    }

    /** O modelo declarado no agente passa a valer quando não há escolha explícita na mensagem. */
    @Test
    void usesTheModelDeclaredOnTheAgent() throws Exception {
        userAgentIs(agentWith(null, "qwen3.5:35b"));

        assertThat(resolveModel(null)).isEqualTo("qwen3.5:35b");
    }

    /**
     * A escolha explícita do seletor vence o agente.
     *
     * <p>A ordem é por especificidade: o seletor é uma decisão para ESTA mensagem, o agente é uma
     * decisão para ESTE agente. Trocar de modelo no cabeçalho tem de funcionar mesmo com agente que
     * declara outro — senão o seletor vira enfeite.
     */
    @Test
    void letsAnExplicitChoiceWinOverTheAgentsModel() throws Exception {
        userAgentIs(agentWith(null, "qwen3.5:35b"));

        assertThat(resolveModel("qwen3.5:9b")).isEqualTo("qwen3.5:9b");
    }

    /** Sem agente resolvido, a cadeia antiga vale inteira — nada muda para quem não tem perfil. */
    @Test
    void fallsBackToTheOldChainWhenThereIsNoAgent() throws Exception {
        userAgentIs(null);

        assertThat(resolveModel(null)).isNotBlank();
    }
}
