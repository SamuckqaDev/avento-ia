package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * O modelo escreveu "Vou pesquisar agora!" quatro vezes seguidas sem chamar ferramenta nenhuma — as
 * duas últimas repetindo o mesmo parágrafo palavra por palavra, com o `fetch` disponível na rodada.
 *
 * <p>A guarda de turno vazio não pegava: ela exige texto EM BRANCO, e aqui há texto. Para o usuário
 * é pior que o silêncio, porque parece que alguma coisa está acontecendo.
 */
class AgentServiceAnnouncedActionTest {

    @Test
    void detectsAPromiseWithNoToolCall() {
        assertThat(AgentService.announcedActionWithoutCalling("Vou pesquisar agora!", false, true))
                .isTrue();
        assertThat(AgentService.announcedActionWithoutCalling(
                        "Vou buscar informações atualizadas sobre hardware de IA da NVIDIA!", false, true))
                .isTrue();
        assertThat(AgentService.announcedActionWithoutCalling("Deixa eu procurar isso pra você.", false, true))
                .isTrue();
        assertThat(AgentService.announcedActionWithoutCalling("Estou acessando a página agora.", false, true))
                .isTrue();
    }

    // Se a ferramenta FOI chamada, anunciar antes é comportamento normal.
    @Test
    void ignoresThePromiseWhenTheRoundActuallyCalledATool() {
        assertThat(AgentService.announcedActionWithoutCalling("Vou pesquisar agora!", true, true))
                .isFalse();
    }

    // Sem toolset, prometer é a única saída — não é falha do modelo.
    @Test
    void ignoresThePromiseWhenNoToolWasAvailable() {
        assertThat(AgentService.announcedActionWithoutCalling("Vou pesquisar agora!", false, false))
                .isFalse();
    }

    // "vou explicar" se cumpre em texto: não é ação de ferramenta e não pode disparar retry.
    @Test
    void doesNotFlagPromisesThatAreFulfilledInText() {
        assertThat(AgentService.announcedActionWithoutCalling(
                        "Vou explicar como isso funciona: o parser lê o JSON.", false, true))
                .isFalse();
        assertThat(AgentService.announcedActionWithoutCalling("Vou resumir os pontos principais.", false, true))
                .isFalse();
    }

    // Resposta longa já é a entrega, mesmo que mencione uma ação de passagem.
    @Test
    void doesNotFlagALongAnswerThatMentionsAnAction() {
        String longAnswer =
                "Vou pesquisar isso, mas antes segue o panorama completo. " + "Detalhe relevante. ".repeat(60);

        assertThat(AgentService.announcedActionWithoutCalling(longAnswer, false, true))
                .isFalse();
    }

    @Test
    void handlesEmptyAndNullText() {
        assertThat(AgentService.announcedActionWithoutCalling("", false, true)).isFalse();
        assertThat(AgentService.announcedActionWithoutCalling(null, false, true))
                .isFalse();
    }
}
