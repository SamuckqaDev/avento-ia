package com.avento.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.dto.SelectableTool;
import com.avento.dto.ServerDescriptor;
import com.avento.dto.ToolDefinition;
import com.avento.service.mcp.McpServerCatalogService;
import com.avento.service.tools.ToolCapabilityRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * O catálogo que a tela de criação de agente consome.
 *
 * <p>Os testes travam as três coisas que decidem se a tela é usável: mostrar container desligado,
 * não subir container para montar a lista, e não fingir que sabe o risco do que não classificou.
 */
class SelectableToolCatalogTest {

    private final ToolCapabilityRegistry localTools = new ToolCapabilityRegistry();
    private final McpServerCatalogService mcpCatalog = mock(McpServerCatalogService.class);

    @SuppressWarnings("unchecked")
    private SelectableToolCatalog catalogWith(McpServerCatalogService mcp) {
        ObjectProvider<McpServerCatalogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mcp);
        return new SelectableToolCatalog(localTools, provider);
    }

    private ServerDescriptor descriptor(String id, boolean connected) {
        return new ServerDescriptor(id, id, "servidor " + id, "", true, false, false, true, connected, "");
    }

    @Test
    void listsTheLocalToolsWithTheirCategoryAndRisk() {
        List<SelectableTool> tools = catalogWith(null).all();

        assertThat(tools).isNotEmpty();
        assertThat(tools).allSatisfy(tool -> assertThat(tool.source()).isEqualTo(SelectableTool.SOURCE_LOCAL));
        assertThat(tools).anySatisfy(tool -> {
            assertThat(tool.name()).isEqualTo("read_file");
            assertThat(tool.category()).isNotBlank();
            assertThat(tool.requiresConnection()).isFalse();
        });
    }

    /**
     * <b>O teste que justifica o endpoint existir.</b> {@code GET /api/mcp/tools} lista o que está
     * conectado; se a tela usasse aquilo, toda ferramenta de container desligado sumiria — e é
     * exatamente ela que a pessoa mais quer poder escolher.
     */
    @Test
    void offersToolsFromAContainerServerThatIsNotConnected() {
        when(mcpCatalog.catalog(List.of())).thenReturn(List.of(descriptor("fetch", false)));
        when(mcpCatalog.knownTools("fetch"))
                .thenReturn(List.of(new ToolDefinition("fetch", "fetch", "fetch", "Baixa uma URL", Map.of())));

        List<SelectableTool> tools = catalogWith(mcpCatalog).all();

        assertThat(tools).anySatisfy(tool -> {
            assertThat(tool.name()).isEqualTo("fetch");
            assertThat(tool.source()).isEqualTo(SelectableTool.SOURCE_CONTAINER);
            assertThat(tool.serverId()).isEqualTo("fetch");
            assertThat(tool.requiresConnection())
                    .as("servidor desligado: escolher implica conectar depois")
                    .isTrue();
        });
    }

    /** Servidor já conectado não pede conexão — a tela não deve prometer trabalho que não existe. */
    @Test
    void doesNotAskForAConnectionThatAlreadyExists() {
        when(mcpCatalog.catalog(List.of())).thenReturn(List.of(descriptor("time", true)));
        when(mcpCatalog.knownTools("time"))
                .thenReturn(
                        List.of(new ToolDefinition("get_current_time", "get_current_time", "time", "Hora", Map.of())));

        List<SelectableTool> tools = catalogWith(mcpCatalog).all();

        assertThat(tools)
                .filteredOn(tool -> tool.name().equals("get_current_time"))
                .singleElement()
                .satisfies(tool -> assertThat(tool.requiresConnection()).isFalse());
    }

    /**
     * Risco em branco para o que veio de fora é honestidade, não lacuna: o Avento não classifica
     * ferramenta externa, e dizer "baixo" sobre o que não se sabe seria pior que não dizer nada.
     */
    @Test
    void leavesTheRiskBlankForToolsItDidNotClassify() {
        when(mcpCatalog.catalog(List.of())).thenReturn(List.of(descriptor("fetch", false)));
        when(mcpCatalog.knownTools("fetch"))
                .thenReturn(List.of(new ToolDefinition("fetch", "fetch", "fetch", "Baixa uma URL", Map.of())));

        SelectableTool external = catalogWith(mcpCatalog).all().stream()
                .filter(tool -> tool.name().equals("fetch"))
                .findFirst()
                .orElseThrow();

        assertThat(external.riskLevel()).isEmpty();
    }

    /** Uma ferramenta aparece uma vez só, mesmo que dois servidores digam ter o mesmo nome. */
    @Test
    void neverOffersTheSameToolTwice() {
        when(mcpCatalog.catalog(List.of()))
                .thenReturn(List.of(descriptor("fetch", false), descriptor("duckduckgo", false)));
        ToolDefinition repetida = new ToolDefinition("fetch", "fetch", "fetch", "Baixa uma URL", Map.of());
        when(mcpCatalog.knownTools("fetch")).thenReturn(List.of(repetida));
        when(mcpCatalog.knownTools("duckduckgo")).thenReturn(List.of(repetida));

        List<SelectableTool> tools = catalogWith(mcpCatalog).all();

        assertThat(tools).filteredOn(tool -> tool.name().equals("fetch")).hasSize(1);
    }

    /**
     * A ordem é estável entre chamadas.
     *
     * <p>Lista que embaralha faz a tela pular sob o cursor de quem está escolhendo — e a pessoa
     * marca a ferramenta errada.
     */
    @Test
    void keepsTheOrderStableBetweenCalls() {
        when(mcpCatalog.catalog(List.of())).thenReturn(List.of(descriptor("fetch", false)));
        when(mcpCatalog.knownTools("fetch"))
                .thenReturn(List.of(new ToolDefinition("fetch", "fetch", "fetch", "Baixa", Map.of())));

        SelectableToolCatalog catalog = catalogWith(mcpCatalog);

        assertThat(catalog.all().stream().map(SelectableTool::name).toList())
                .isEqualTo(catalog.all().stream().map(SelectableTool::name).toList());
    }
}
