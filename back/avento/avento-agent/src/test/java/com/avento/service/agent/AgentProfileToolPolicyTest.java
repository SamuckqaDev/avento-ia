package com.avento.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.service.tools.RunToolPolicyRegistry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;

/**
 * O perfil do agente decide QUAIS ferramentas existem para a run — e decide ANTES da seleção.
 *
 * <p>Antes desta mudança a allow-list era um filtro aplicado DEPOIS da seleção por intenção. Isso a
 * deixava capaz apenas de subtrair: uma ferramenta que o perfil pedisse mas que a seleção não
 * escolhesse simplesmente não aparecia, e o perfil não conseguia acrescentar nada. Estes testes
 * travam a inversão.
 */
class AgentProfileToolPolicyTest extends AgentServiceCharacterizationHarness {

    private RunToolPolicyRegistry registryWith(String runId, Set<String> allowed) throws Exception {
        RunToolPolicyRegistry registry = new RunToolPolicyRegistry();
        registry.allow(runId, allowed);
        Field field = com.avento.service.agent.AgentService.class.getDeclaredField("toolPolicyRegistry");
        field.setAccessible(true);
        field.set(service, registry);
        return registry;
    }

    private ArrayNode applyPolicy(ArrayNode tools, Object state) throws Exception {
        var method = privateMethod("applyAgentToolPolicy", ArrayNode.class, stateClass());
        return (ArrayNode) method.invoke(service, tools, state);
    }

    private Object stateForRun(String runId) throws Exception {
        Object state = newRunState();
        set(state, "runId", runId);
        return state;
    }

    /**
     * <b>O teste que justifica a inversão.</b> A ferramenta escolhida no perfil sobrevive mesmo que a
     * heurística de intenção jamais a escolheria para esta mensagem — porque agora o perfil define o
     * universo, e a seleção acontece dentro dele.
     */
    @Test
    void theProfileDecidesWhatIsEligibleBeforeAnyIntentSelection() throws Exception {
        registryWith("run_perfil", Set.of("generate_video", "read_file"));

        ArrayNode eligible = applyPolicy(
                toolsNamed("read_file", "write_file", "terminal_run", "generate_video"), stateForRun("run_perfil"));

        assertThat(namesOf(eligible)).containsExactlyInAnyOrder("read_file", "generate_video");
    }

    /** O que o perfil não pediu não é elegível, por mais central que seja no catálogo. */
    @Test
    void aToolOutsideTheProfileIsNotEligible() throws Exception {
        registryWith("run_restrito", Set.of("read_file"));

        ArrayNode eligible =
                applyPolicy(toolsNamed("read_file", "terminal_run", "delete_file"), stateForRun("run_restrito"));

        assertThat(namesOf(eligible)).containsExactly("read_file");
        assertThat(namesOf(eligible)).doesNotContain("terminal_run", "delete_file");
    }

    /**
     * Nenhuma ferramenta do perfil disponível: o conjunto fica VAZIO, e não cai no catálogo inteiro.
     *
     * <p>As duas saídas óbvias estão erradas. Cair para tudo escala privilégio por configuração
     * errada, e {@code docs/agent-corrections-plan.md:157} proíbe nominalmente ("never falls back to
     * all"). Este teste é o que impede alguém de "consertar" a rodada vazia pelo caminho fácil.
     */
    @Test
    void neverFallsBackToTheWholeCatalogWhenNothingFromTheProfileIsAvailable() throws Exception {
        registryWith("run_offline", Set.of("fetch", "get_current_time"));

        ArrayNode eligible =
                applyPolicy(toolsNamed("read_file", "write_file", "terminal_run"), stateForRun("run_offline"));

        assertThat(eligible).isEmpty();
        assertThat(namesOf(eligible)).doesNotContain("read_file", "terminal_run");
    }

    /** Sem allow-list, nada muda — quem nunca escolheu ferramenta continua vendo o catálogo inteiro. */
    @Test
    void leavesTheCatalogUntouchedForAProfileWithoutChosenTools() throws Exception {
        registryWith("run_outro", Set.of("read_file"));

        ArrayNode eligible = applyPolicy(toolsNamed("read_file", "write_file"), stateForRun("run_sem_politica"));

        assertThat(namesOf(eligible)).containsExactly("read_file", "write_file");
    }

    /**
     * A ordem do catálogo sobrevive ao recorte.
     *
     * <p>O payload de ferramentas entra no prefixo do prompt; reordenar aqui invalidaria o cache do
     * llama.cpp entre mensagens da mesma conversa.
     */
    @Test
    void keepsTheCatalogOrderSoThePromptPrefixStaysStable() throws Exception {
        registryWith("run_ordem", Set.of("terminal_run", "read_file"));

        ArrayNode eligible =
                applyPolicy(toolsNamed("read_file", "write_file", "terminal_run"), stateForRun("run_ordem"));

        assertThat(namesOf(eligible)).isEqualTo(List.of("read_file", "terminal_run"));
    }
}
