package com.avento.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ArrayNode;

/**
 * Caracteriza {@code AgentService.runTurn} — 64 linhas, zero testes até 08/08/2026.
 *
 * <p>{@code runTurn} é o laço do agente: monta o request, abre o stream do modelo e, ao fim,
 * entrega para {@code finishTurn} decidir se continua. Ele também é o dono do ciclo de vida das
 * subscriptions — e é aí que mora o defeito mais caro que já passou por aqui: sem o composite em
 * {@code state.subscriptions}, cancelar uma run matava só a rodada 1 e a requisição HTTP da rodada
 * seguinte continuava viva no Ollama ocupando a GPU (zumbi observado em produção).
 *
 * <p><b>Nota de método:</b> o {@code ollamaBaseUrl} do harness aponta para uma porta fechada, então
 * todo teste aqui exercita o caminho de ERRO do provedor. Isso é de propósito: é o único caminho
 * observável sem subir infraestrutura, e é justamente o que precisa ter fim garantido — um Flux
 * pendurado aqui é uma run travada para o usuário.
 */
class AgentServiceRunTurnCharacterizationTest extends AgentServiceCharacterizationHarness {

    private Method runTurn() throws Exception {
        return privateMethod("runTurn", String.class, ArrayNode.class, stateClass(), int.class);
    }

    @SuppressWarnings("unchecked")
    private Flux<String> invokeRunTurn(ArrayNode messages, Object state, int round) throws Exception {
        return (Flux<String>) runTurn().invoke(service, "qwen3:8b", messages, state, round);
    }

    private Object stateWithRun() throws Exception {
        Object state = newRunState();
        set(state, "runId", "run_characterization");
        set(state, "userId", UUID.randomUUID());
        return state;
    }

    /**
     * O primeiro evento da rodada é sempre {@code agent.round.started}, com o número da rodada e o
     * modelo no texto.
     *
     * <p>Isso não é decoração: o log e o evento existem porque uma rodada que trava ANTES de chamar
     * o Ollama (por exemplo na montagem do request, que dispara embedding síncrono) ficava
     * indistinguível de uma rodada que nunca começou. Já se perdeu tempo de diagnóstico nessa
     * lacuna.
     */
    @Test
    void firstEmissionAnnouncesTheRoundHasStarted() throws Exception {
        String first = invokeRunTurn(userMessages("lê o arquivo pom.xml"), stateWithRun(), 1)
                .blockFirst(Duration.ofSeconds(10));

        assertThat(first).contains("agent.round.started").contains("Rodada 1").contains("qwen3:8b");
    }

    /** O número da rodada emitido acompanha o argumento, e não um contador interno. */
    @Test
    void announcedRoundNumberFollowsTheArgument() throws Exception {
        String first = invokeRunTurn(userMessages("lê o arquivo pom.xml"), stateWithRun(), 4)
                .blockFirst(Duration.ofSeconds(10));

        assertThat(first).contains("Rodada 4");
    }

    /**
     * Com o provedor inalcançável o Flux TERMINA. É o assert mais importante deste arquivo: um
     * stream pendurado aqui é uma run que nunca fecha, sem erro e sem aviso — exatamente o sintoma
     * que levou ao timeout por ausência de sinal dentro de {@code runTurn}.
     */
    @Test
    void terminatesInsteadOfHangingWhenTheProviderIsUnreachable() throws Exception {
        List<String> emitted = invokeRunTurn(userMessages("lê o arquivo pom.xml"), stateWithRun(), 1)
                .collectList()
                .block(Duration.ofSeconds(20));

        assertThat(emitted).isNotNull().isNotEmpty();
    }

    /**
     * Cancelar o Flux descarta também {@code state.subscriptions} — o composite que guarda as
     * rodadas seguintes. Sem ele, cancelar a run deixava a requisição da próxima rodada viva.
     *
     * <p>O que dá para observar sem mexer em produção é o efeito no composite: depois do cancelamento
     * ele fica descartado. Se um dia o {@code cleanup} deixar de descartá-lo, este teste falha.
     */
    @Test
    void cancellingTheRoundDisposesTheCompositeThatHoldsLaterRounds() throws Exception {
        Object state = stateWithRun();
        reactor.core.Disposable.Composite subscriptions =
                (reactor.core.Disposable.Composite) get(state, "subscriptions");

        assertThat(subscriptions.isDisposed()).isFalse();

        invokeRunTurn(userMessages("lê o arquivo pom.xml"), state, 1).take(1).blockLast(Duration.ofSeconds(10));

        assertThat(subscriptions.isDisposed()).isTrue();
    }
}
