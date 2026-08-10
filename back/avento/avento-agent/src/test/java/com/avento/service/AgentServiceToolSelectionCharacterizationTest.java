package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.service.tools.AgentToolSelector;
import tools.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Caracteriza {@code AgentService.selectToolsForCurrentRequest} — 107 linhas, zero testes até
 * 08/08/2026.
 *
 * <p>Este método decide QUAIS ferramentas o modelo enxerga em cada rodada, e essa decisão custa
 * caro dos dois lados: ferramenta de menos e o turno termina vazio (o modelo "planeja" no thinking
 * e não age); ferramenta demais e o custo de {@code prompt_eval} estoura o timeout da run — medido
 * ao vivo, 23 ferramentas levaram uma rodada a passar de 6 minutos sem sinal algum.
 *
 * <p><b>Os asserts abaixo descrevem o comportamento ATUAL, não o ideal.</b> Vários deles travam
 * decisões que existem por causa de defeito de produção, e o comentário de cada teste diz qual.
 */
class AgentServiceToolSelectionCharacterizationTest extends AgentServiceCharacterizationHarness {

    // Bate direto no AgentToolSelector: a selecao saiu do AgentService e nao precisa mais de
    // reflexao. As assercoes abaixo nao mudaram desde antes da extracao — o que muda e so por onde
    // se entra. O seletor e lido do proprio AgentService para garantir a MESMA configuracao
    // (teto, kit fixo, exposeAllTools) que o harness monta.
    private AgentToolSelector selector() throws Exception {
        java.lang.reflect.Field field = AgentService.class.getDeclaredField("toolSelector");
        field.setAccessible(true);
        return (AgentToolSelector) field.get(service);
    }

    private ArrayNode select(ArrayNode tools, ArrayNode messages, Object state) throws Exception {
        return selector()
                .select(
                        tools,
                        messages,
                        new AgentToolSelector.SelectionContext(
                                castList(get(state, "workspaceRoots")),
                                castSet(get(state, "requiredToolNames")),
                                (String) get(state, "requiredToolName"),
                                castSet(get(state, "extraExposedToolNames")),
                                (boolean) get(state, "forceFullToolset")));
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private Set<String> castSet(Object value) {
        return (Set<String>) value;
    }

    /** Catálogo grande o suficiente para o teto de 18 do ramo de projeto ser exercitado. */
    private ArrayNode bigCatalog() {
        List<String> names = new ArrayList<>(PROJECT_TOOLKIT);
        for (int i = 0; i < 20; i++) {
            names.add("extra_tool_" + i);
        }
        return toolsNamed(names);
    }

    @Test
    void returnsNothingWhenTheCatalogIsEmpty() throws Exception {
        ArrayNode selected = select(mapper.createArrayNode(), userMessages("lê o arquivo pom.xml"), newRunState());

        assertThat(selected).isEmpty();
    }

    /**
     * Skill ativa que declara {@code Ferramenta:} vence toda heurística. O comentário em produção
     * registra o motivo: as heurísticas de keyword já roubaram um pedido de vídeo para o
     * {@code generate_image}, e a declaração explícita da skill não pode perder para elas.
     */
    @Test
    void requiredToolFromTheActiveSkillWinsOverEveryHeuristic() throws Exception {
        Object state = newRunState();
        set(state, "requiredToolNames", new HashSet<>(Set.of("generate_video")));

        ArrayNode selected = select(
                toolsNamed("generate_video", "generate_image", "read_file"), userMessages("faz uma imagem"), state);

        assertThat(namesOf(selected)).containsExactly("generate_video");
    }

    /** Sem última mensagem de usuário não há o que classificar: devolve o que já tinha. */
    @Test
    void returnsEarlyWhenThereIsNoUserMessage() throws Exception {
        ArrayNode selected = select(toolsNamed("read_file", "write_file"), mapper.createArrayNode(), newRunState());

        assertThat(selected).isEmpty();
    }

    /** Mensagem casual não aciona ferramenta — o custo de expor schema não se justifica num "oi". */
    @Test
    void casualMessageSelectsNoTool() throws Exception {
        ArrayNode selected = select(toolsNamed("read_file", "terminal_run"), userMessages("oi"), newRunState());

        assertThat(selected).isEmpty();
    }

    /**
     * Pedido de mockup devolve conjunto VAZIO, e em particular sem {@code generate_image}.
     *
     * <p>Não é economia: é para o modelo escrever o bloco {@code ui-preview} em HTML. Com o
     * {@code generate_image} exposto ele cai em gerar uma imagem cara e feia no lugar do preview.
     */
    @Test
    void interfaceMockupRequestExposesNoToolAtAll() throws Exception {
        ArrayNode selected = select(
                toolsNamed("generate_image", "read_file", "write_file"),
                userMessages("crie um mockup para a tela de login"),
                newRunState());

        assertThat(namesOf(selected)).doesNotContain("generate_image");
        assertThat(selected).isEmpty();
    }

    /**
     * Com projeto conectado o kit fixo entra INTEIRO. A lista estável é o que mantém o prefixo do
     * prompt idêntico entre mensagens e permite o cache de prompt do llama.cpp reaproveitar o
     * processamento de sistema + schemas.
     */
    @Test
    void connectedProjectAlwaysExposesTheWholeFixedKit() throws Exception {
        Object state = newRunState();
        set(state, "workspaceRoots", List.of("/tmp/projeto"));

        ArrayNode selected = select(bigCatalog(), userMessages("apaga o arquivo velho.txt"), state);

        assertThat(namesOf(selected)).containsAll(PROJECT_TOOLKIT);
    }

    /**
     * O teto do ramo de projeto ({@code maxProjectTools}, 18) corta o que cresceu EM CIMA do kit,
     * nunca o kit.
     *
     * <p>Este ramo não tinha teto nenhum: as ferramentas ativadas por {@code activate_tools} se
     * acumulam em Redis rodada após rodada, e uma run de produção saiu com 22 schemas contra um
     * teto declarado de 12 — cada schema extra custando tempo em TODA rodada seguinte.
     */
    @Test
    void projectKitSurvivesTheCapAndOnlyTheExtrasAreCut() throws Exception {
        Object state = newRunState();
        set(state, "workspaceRoots", List.of("/tmp/projeto"));
        Set<String> extras = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            extras.add("extra_tool_" + i);
        }
        set(state, "extraExposedToolNames", extras);

        ArrayNode selected = select(bigCatalog(), userMessages("lê os arquivos do projeto e resume"), state);

        assertThat(namesOf(selected)).containsAll(PROJECT_TOOLKIT);
        assertThat(selected.size()).isLessThanOrEqualTo(18);
    }

    /**
     * Prioridade NÃO é exclusão. O retorno exclusivo anterior quebrava pedido composto: "gera uma
     * imagem E faz um pdf dela" ficava só com {@code generate_image}.
     */
    @Test
    void imageRequestPrioritisesGenerateImageWithoutDroppingTheRest() throws Exception {
        ArrayNode selected = select(
                toolsNamed("generate_image", "generate_pdf", "read_file"),
                userMessages("gera uma imagem de um gato e depois um pdf com ela"),
                newRunState());

        assertThat(namesOf(selected)).contains("generate_image");
        assertThat(selected.size()).isGreaterThan(1);
    }

    /**
     * A ordem do catálogo é preservada na saída. Isso não é estética: com o mesmo conjunto, o
     * payload de tools fica idêntico entre requisições do mesmo chat, que é o que permite ao cache
     * de prompt reaproveitar o prefixo em vez de reprocessá-lo.
     */
    @Test
    void preservesCatalogOrderSoThePromptPrefixStaysStable() throws Exception {
        Object state = newRunState();
        set(state, "workspaceRoots", List.of("/tmp/projeto"));

        ArrayNode first = select(bigCatalog(), userMessages("lê o arquivo pom.xml"), state);
        ArrayNode second = select(bigCatalog(), userMessages("lê o arquivo pom.xml"), state);

        assertThat(namesOf(second)).isEqualTo(namesOf(first));
    }
}
