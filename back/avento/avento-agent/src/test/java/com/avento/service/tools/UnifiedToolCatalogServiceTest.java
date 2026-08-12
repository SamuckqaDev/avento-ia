package com.avento.service.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.dto.ServerDescriptor;
import com.avento.dto.ToolDefinition;
import com.avento.dto.UnifiedToolCatalog;
import com.avento.dto.UnifiedToolCatalogEntry;
import com.avento.service.mcp.McpClientManager;
import com.avento.service.mcp.McpServerCatalogService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnifiedToolCatalogServiceTest {

    private final ToolCapabilityRegistry localTools = new ToolCapabilityRegistry();
    private final McpServerCatalogService mcpServerCatalogService = mock(McpServerCatalogService.class);
    private final McpClientManager mcpClientManager = mock(McpClientManager.class);
    private final ToolExecutionContext toolExecutionContext = new ToolExecutionContext();

    private UnifiedToolCatalogService service() {
        return new UnifiedToolCatalogService(
                localTools, mcpServerCatalogService, mcpClientManager, toolExecutionContext);
    }

    @Test
    void includesNativeAventoToolsAsReady() {
        when(mcpServerCatalogService.catalog(List.of())).thenReturn(List.of());
        when(mcpClientManager.listTools("local")).thenReturn(List.of());

        UnifiedToolCatalog catalog = service().catalog(List.of());

        assertThat(catalog.entries()).anySatisfy(entry -> {
            assertThat(entry.name()).isEqualTo("read_file");
            assertThat(entry.source()).isEqualTo(UnifiedToolCatalogEntry.SOURCE_AVENTO_NATIVE);
            assertThat(entry.availability()).isEqualTo(UnifiedToolCatalogEntry.AVAILABILITY_READY);
            assertThat(entry.requiresConnection()).isFalse();
        });
    }

    @Test
    void keepsAnAvailableDockerGatewayVisibleBeforeItsToolsAreDiscovered() {
        when(mcpServerCatalogService.catalog(List.of())).thenReturn(List.of(server("docker-gateway", true, false, "")));
        when(mcpClientManager.listTools("local")).thenReturn(List.of());
        when(mcpServerCatalogService.knownTools("docker-gateway")).thenReturn(List.of());

        UnifiedToolCatalog catalog = service().catalog(List.of());

        assertThat(catalog.entries()).anySatisfy(entry -> {
            assertThat(entry.entryType()).isEqualTo(UnifiedToolCatalogEntry.ENTRY_TYPE_SERVER);
            assertThat(entry.serverId()).isEqualTo("docker-gateway");
            assertThat(entry.source()).isEqualTo(UnifiedToolCatalogEntry.SOURCE_DOCKER_MCP);
            assertThat(entry.availability()).isEqualTo(UnifiedToolCatalogEntry.AVAILABILITY_AVAILABLE);
            assertThat(entry.requiresConnection()).isTrue();
        });
    }

    @Test
    void showsToolsActuallyAnnouncedByTheDockerGatewayAsReady() {
        when(mcpServerCatalogService.catalog(List.of())).thenReturn(List.of(server("docker-gateway", true, true, "")));
        when(mcpClientManager.listTools("local"))
                .thenReturn(List.of(new ToolDefinition(
                        "browser_navigate",
                        "browser_navigate",
                        "docker-gateway",
                        "Navega para uma pagina.",
                        Map.of())));

        UnifiedToolCatalog catalog = service().catalog(List.of());

        assertThat(catalog.entries()).anySatisfy(entry -> {
            assertThat(entry.name()).isEqualTo("browser_navigate");
            assertThat(entry.source()).isEqualTo(UnifiedToolCatalogEntry.SOURCE_DOCKER_MCP);
            assertThat(entry.availability()).isEqualTo(UnifiedToolCatalogEntry.AVAILABILITY_READY);
            assertThat(entry.requiresConnection()).isFalse();
        });
    }

    @Test
    void reportsAConnectedGatewayThatAnnouncedNoToolsAsDegraded() {
        when(mcpServerCatalogService.catalog(List.of())).thenReturn(List.of(server("docker-gateway", true, true, "")));
        when(mcpClientManager.listTools("local")).thenReturn(List.of());
        when(mcpServerCatalogService.knownTools("docker-gateway")).thenReturn(List.of());

        UnifiedToolCatalog catalog = service().catalog(List.of());

        assertThat(catalog.entries()).anySatisfy(entry -> {
            assertThat(entry.serverId()).isEqualTo("docker-gateway");
            assertThat(entry.availability()).isEqualTo(UnifiedToolCatalogEntry.AVAILABILITY_DEGRADED);
            assertThat(entry.reason()).contains("tools/list");
        });
    }

    @Test
    void preservesTheReasonWhenAHostMcpCannotStart() {
        when(mcpServerCatalogService.catalog(List.of()))
                .thenReturn(List.of(server("macos-automator", false, false, "Disponivel somente no macOS.")));
        when(mcpClientManager.listTools("local")).thenReturn(List.of());
        when(mcpServerCatalogService.knownTools("macos-automator")).thenReturn(List.of());

        UnifiedToolCatalog catalog = service().catalog(List.of());

        assertThat(catalog.entries()).anySatisfy(entry -> {
            assertThat(entry.serverId()).isEqualTo("macos-automator");
            assertThat(entry.availability()).isEqualTo(UnifiedToolCatalogEntry.AVAILABILITY_UNAVAILABLE);
            assertThat(entry.reason()).contains("macOS");
        });
    }

    private ServerDescriptor server(String id, boolean available, boolean connected, String reason) {
        return new ServerDescriptor(id, id, "Servidor " + id, "core", true, false, false, available, connected, reason);
    }
}
