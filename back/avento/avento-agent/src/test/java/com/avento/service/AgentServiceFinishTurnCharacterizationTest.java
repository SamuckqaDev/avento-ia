package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Caracteriza {@code AgentService.finishTurn} — 241 linhas, zero testes até 08/08/2026.
 *
 * <p>É o método que decide o que acontece no fim de cada rodada: repetir, executar ferramenta,
 * parar, ou fechar. Quase todo ramo aqui nasceu de um defeito de produção, e o javadoc de cada
 * teste registra qual — se um assert incomodar no futuro, leia o motivo antes de afrouxá-lo.
 *
 * <p><b>Cuidado com a recursão.</b> {@code finishTurn} reentra em {@code runTurn} em quatro pontos.
 * Os testes que não querem a recursão desligam-na pelos flags do {@code AgentRunState}
 * ({@code retriedEmptyTurn}, {@code retriedWithFullToolset}, {@code finalSynthesis}). Os que
 * exercitam o próprio retry deixam acontecer: o provedor do harness aponta para porta fechada, então
 * a rodada seguinte falha rápido em vez de chamar modelo de verdade.
 */
class AgentServiceFinishTurnCharacterizationTest extends AgentServiceCharacterizationHarness {

    private Method finishTurn() throws Exception {
        return privateMethod(
                "finishTurn", String.class, ArrayNode.class, stateClass(), int.class, FluxSink.class, captureClass());
    }

    /** Roda finishTurn dentro de um Flux real e devolve tudo o que foi emitido. */
    private String emissionsOf(ArrayNode messages, Object state, int round, Object capture) throws Exception {
        Method method = finishTurn();
        List<String> emitted = Flux.<String>create(sink -> {
                    try {
                        method.invoke(service, "qwen3:8b", messages, state, round, sink, capture);
                        sink.complete();
                    } catch (Exception exception) {
                        sink.error(exception);
                    }
                })
                .collectList()
                .block(Duration.ofSeconds(20));
        return emitted == null ? "" : String.join("", emitted);
    }

    private Object baseState() throws Exception {
        Object state = newRunState();
        set(state, "runId", "run_finish_" + UUID.randomUUID());
        set(state, "userId", UUID.randomUUID());
        return state;
    }

    /** Estado que NÃO recursa: os dois retries já foram gastos. */
    private Object settledState() throws Exception {
        Object state = baseState();
        set(state, "retriedEmptyTurn", true);
        set(state, "retriedWithFullToolset", true);
        return state;
    }

    private Object captureWithText(String text) throws Exception {
        Object capture = newCapture();
        appendAssistantText(capture, text);
        return capture;
    }

    /** Monta uma chamada nativa de ferramenta no formato que {@code detectToolCalls} espera. */
    private Object captureWithToolCall(String toolName, String argumentsJson) throws Exception {
        Object capture = newCapture();
        ObjectNode call = mapper.createObjectNode();
        call.put("id", "call_" + UUID.randomUUID());
        ObjectNode function = call.putObject("function");
        function.put("name", toolName);
        function.put("arguments", argumentsJson);

        @SuppressWarnings("unchecked")
        List<ObjectNode> nativeCalls = (List<ObjectNode>) get(capture, "nativeToolCalls");
        List<ObjectNode> replacement = new ArrayList<>(nativeCalls);
        replacement.add(call);
        set(capture, "nativeToolCalls", replacement);
        return capture;
    }

    // ── Ramo 1: sem chamada de ferramenta ──────────────────────────────────────────────────────

    /**
     * Turno completamente vazio na primeira vez: o modelo gastou a rodada no thinking e terminou sem
     * texto E sem ferramenta (comportamento observado do qwen3). Sem esta guarda a run completava em
     * silêncio absoluto.
     */
    @Test
    void emptyTurnRetriesOnceWithAnExplicitNudge() throws Exception {
        String emissions = emissionsOf(userMessages("lista os arquivos do projeto"), baseState(), 1, newCapture());

        assertThat(emissions).contains("agent.round.retry").contains("Resposta vazia");
    }

    /**
     * Anunciar sem executar falha igual ao turno vazio, e para o usuário é pior: parece que algo
     * está acontecendo. A guarda antiga exigia texto em branco e deixava este caso passar.
     */
    @Test
    void announcingAnActionWithoutCallingAnyToolAlsoRetries() throws Exception {
        Object state = baseState();
        set(state, "availableToolNames", java.util.Set.of("read_file", "write_file", "terminal_run"));

        String emissions = emissionsOf(
                userMessages("cria o arquivo README.md"),
                state,
                1,
                captureWithText("Vou criar o arquivo README.md para você agora."));

        assertThat(emissions).contains("agent.round.retry");
    }

    /** O retry é UMA vez. Gasto o flag, o turno vazio vira aviso ao usuário e a run fecha. */
    @Test
    void emptyTurnAfterTheRetryWarnsTheUserInsteadOfLoopingForever() throws Exception {
        String emissions = emissionsOf(userMessages("lista os arquivos do projeto"), settledState(), 1, newCapture());

        assertThat(emissions).contains("encerrou o turno sem produzir resposta");
        assertThat(emissions).doesNotContain("agent.round.retry");
    }

    /**
     * O modelo pode descrever uma ação como concluída em texto, com checkmark e "criado com
     * sucesso", sem ter chamado ferramenta nenhuma. Em vez de tentar detectar a mentira no texto
     * (frágil), o Avento avisa sempre que o pedido era acionável e o turno inteiro executou zero
     * ferramentas.
     */
    @Test
    void warnsWhenAnActionableRequestFinishedWithoutExecutingAnyTool() throws Exception {
        Object state = settledState();
        set(state, "availableToolNames", java.util.Set.of("write_file"));

        String emissions = emissionsOf(
                userMessages("cria o arquivo README.md no projeto"),
                state,
                1,
                captureWithText("Pronto! ✅ Arquivo README.md criado com sucesso."));

        assertThat(emissions).contains("agent.no_tool_warning");
    }

    /** Sem ferramenta pedida e sem nada de estranho, a rodada fecha anunciando resposta final. */
    @Test
    void closesTheRoundWhenTheModelSimplyAnswered() throws Exception {
        String emissions = emissionsOf(
                userMessages("me explica para que serve o Redis"),
                settledState(),
                1,
                captureWithText("O Redis é um banco de dados em memória."));

        assertThat(emissions).contains("agent.round.completed");
    }

    // ── Ramo 2: mensagem casual ────────────────────────────────────────────────────────────────

    /** Um "oi" não aciona ferramenta, mesmo que o modelo tenha pedido uma. */
    @Test
    void casualMessageIgnoresToolCallsAndGreetsBack() throws Exception {
        String emissions = emissionsOf(
                userMessages("oi"), settledState(), 1, captureWithToolCall("read_file", "{\"path\":\"/tmp\"}"));

        assertThat(emissions).contains("tool.ignored");
        assertThat(emissions).contains("Estou por aqui");
    }

    // ── Ramo 3: limite de rodadas ──────────────────────────────────────────────────────────────

    /**
     * Ao bater o limite, o corte cai sobre as FERRAMENTAS, não sobre a resposta.
     *
     * <p>Antes daqui saía só "Limite atingido" e {@code sink.complete()}: minutos de leitura de
     * arquivo eram descartados e o usuário ficava com a narração e nenhuma análise. Agora há uma
     * última rodada sem toolset pedindo o fechamento com o que já foi coletado.
     */
    @Test
    void reachingTheRoundLimitAsksForAFinalSynthesisInsteadOfDroppingTheWork() throws Exception {
        String emissions = emissionsOf(
                userMessages("analisa o projeto inteiro"),
                baseState(),
                99,
                captureWithToolCall("read_file", "{\"path\":\"/tmp/a.txt\"}"));

        assertThat(emissions).contains("agent.limit.reached");
    }

    /** O flag garante que a síntese final acontece UMA vez — senão o próprio fecho vira outro laço. */
    @Test
    void theFinalSynthesisHappensOnlyOnce() throws Exception {
        Object state = baseState();
        set(state, "finalSynthesis", true);

        String emissions = emissionsOf(
                userMessages("analisa o projeto inteiro"),
                state,
                99,
                captureWithToolCall("read_file", "{\"path\":\"/tmp/a.txt\"}"));

        assertThat(emissions).contains("Limite de ferramentas atingido");
    }

    // ── Ramo 4: segurança ──────────────────────────────────────────────────────────────────────

    /**
     * <b>O teste mais importante deste arquivo.</b> Uma ferramenta que tenta usar {@code /} como
     * caminho é rejeitada e a run PARA — não é filtrada nem corrigida em silêncio.
     */
    @Test
    void rejectsAnyToolCallThatTriesToUseTheFilesystemRoot() throws Exception {
        String emissions = emissionsOf(
                userMessages("lê todos os arquivos"),
                settledState(),
                1,
                captureWithToolCall("directory_tree", "{\"path\":\"/\"}"));

        assertThat(emissions).contains("tool.rejected");
        assertThat(emissions).contains("Não vou usar");
    }

    // ── Ramo 5: guardas de repetição ───────────────────────────────────────────────────────────

    /**
     * Se a MESMA ferramenta falhou duas vezes seguidas ({@code REPEATED_TOOL_FAILURE_LIMIT} = 2), o
     * agente para e explica em vez de insistir. Cada rodada custa cerca de 50s no modelo local: um
     * "não deu" rápido e claro vale mais que ficar batendo numa ferramenta indisponível.
     *
     * <p><b>O contador não pode ser plantado de fora.</b> {@code recordToolOutcome} o recalcula
     * DENTRO do laço de execução, e só incrementa quando o nome bate com {@code lastFailedTool} —
     * ferramenta diferente zera para 1. Por isso este teste arma o estado como "read_file já falhou
     * uma vez" e deixa a execução real levar a contagem a 2. Uma tentativa anterior de setar
     * {@code consecutiveToolFailures = 3} direto não alcançava o ramo: o próprio laço sobrescrevia.
     */
    @Test
    void stopsInsteadOfInsistingOnAToolThatKeepsFailing() throws Exception {
        Object state = settledState();
        set(state, "lastFailedTool", "read_file");
        set(state, "consecutiveToolFailures", 1);

        String emissions = emissionsOf(
                userMessages("procura a classe AgentService"),
                state,
                1,
                captureWithToolCall("read_file", "{\"path\":\"/tmp/a.txt\"}"));

        assertThat(emissions).contains("agent.tool.repeated_failure");
        assertThat(emissions).contains("read_file");
    }

    /**
     * A assinatura que detecta chamada repetida é montada DEPOIS de o contexto de execução ser
     * injetado nos argumentos ({@code withExecutionContext} acrescenta {@code _userId} e
     * {@code _runId}). Ela não é, portanto, função apenas do que o modelo pediu.
     *
     * <p>Isso tem duas consequências práticas, e as duas custaram tempo para descobrir:
     *
     * <ul>
     *   <li>Não dá para plantar {@code lastToolCallSignature} de fora com o argumento "puro" — a
     *       assinatura real inclui os campos injetados e nunca casa.
     *   <li>A assinatura só é estável DENTRO de uma run. Se o {@code runId} entrasse na conta entre
     *       runs, a detecção de repetição nunca dispararia. Hoje não entra, porque a comparação é
     *       sempre com a chamada anterior da mesma run.
     * </ul>
     */
    @Test
    void theRepeatedCallSignatureIncludesTheInjectedExecutionContext() throws Exception {
        Object state = settledState();
        set(state, "lastFailedTool", "outra_ferramenta");

        emissionsOf(
                userMessages("lê o arquivo a.txt"),
                state,
                1,
                captureWithToolCall("read_file", "{\"path\":\"/tmp/a.txt\"}"));

        String signature = (String) get(state, "lastToolCallSignature");
        assertThat(signature).startsWith("read_file:").contains("_runId").contains("_userId");
    }

    /**
     * <b>Ramo não alcançado, de propósito — registrado para quem vier depois.</b>
     *
     * <p>A orientação de "não repita a mesma chamada" ({@code consecutiveIdenticalToolCalls >= 2})
     * está DEPOIS da guarda de falha repetida ({@code consecutiveToolFailures >= 2}). Como as duas
     * contagens sobem juntas quando a mesma ferramenta é chamada de novo e falha de novo, uma
     * ferramenta que falha identicamente duas vezes sempre cai na guarda de falha e retorna antes.
     *
     * <p>Alcançar a orientação exige uma ferramenta que tenha SUCESSO duas vezes com argumentos
     * idênticos — e no harness nenhuma ferramenta de arquivo tem sucesso, porque o
     * {@code WorkspaceAccessService} não é injetado. Cobrir este ramo exigiria montar o serviço de
     * workspace inteiro, que é mais infraestrutura do que a rede de segurança pede.
     *
     * <p>Este teste trava apenas a ORDEM das duas guardas, que é o que torna o ramo inalcançável.
     * Se alguém inverter as duas, ele falha e a decisão volta à mesa.
     */
    @Test
    void theRepeatedFailureGuardIsCheckedBeforeTheRepeatedCallGuidance() throws Exception {
        Object state = settledState();
        set(state, "lastFailedTool", "read_file");
        set(state, "consecutiveToolFailures", 1);
        set(state, "consecutiveIdenticalToolCalls", 1);

        ArrayNode messages = userMessages("lê o arquivo a.txt");
        String emissions =
                emissionsOf(messages, state, 1, captureWithToolCall("read_file", "{\"path\":\"/tmp/a.txt\"}"));

        assertThat(emissions).contains("agent.tool.repeated_failure");
        assertThat(messages.toString()).doesNotContain("Aviso de Orientação do Avento");
    }
}
