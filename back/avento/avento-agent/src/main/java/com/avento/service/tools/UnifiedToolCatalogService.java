package com.avento.service.tools;

import com.avento.dto.ServerDescriptor;
import com.avento.dto.ToolDefinition;
import com.avento.dto.UnifiedToolCatalog;
import com.avento.dto.UnifiedToolCatalogEntry;
import com.avento.service.mcp.McpClientManager;
import com.avento.service.mcp.McpServerCatalogService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Consolida as capacidades nativas, os servidores MCP instalados no host e as ferramentas que o
 * Docker MCP Gateway realmente anunciou para o escopo atual.
 *
 * <p>A leitura não conecta servidores, não sobe containers e não executa ferramentas. Para o
 * Docker, as ferramentas só entram como {@code READY} após o handshake MCP e o {@code tools/list};
 * antes disso o catálogo mostra o gateway como servidor disponível, sem inventar schemas.
 */
@Service
public class UnifiedToolCatalogService {

    private static final String DOCKER_GATEWAY_SERVER_ID = "docker-gateway";

    private final ToolCapabilityRegistry localTools;
    private final McpServerCatalogService mcpServerCatalogService;
    private final McpClientManager mcpClientManager;
    private final ToolExecutionContext toolExecutionContext;

    public UnifiedToolCatalogService(
            ToolCapabilityRegistry localTools,
            McpServerCatalogService mcpServerCatalogService,
            McpClientManager mcpClientManager,
            ToolExecutionContext toolExecutionContext) {
        this.localTools = localTools;
        this.mcpServerCatalogService = mcpServerCatalogService;
        this.mcpClientManager = mcpClientManager;
        this.toolExecutionContext = toolExecutionContext;
    }

    public UnifiedToolCatalog catalog(List<String> workspaceRoots) {
        List<UnifiedToolCatalogEntry> entries = new ArrayList<>();
        addNativeTools(entries);

        String scope = toolExecutionContext.current().scopeKey();
        List<ToolDefinition> connectedTools = mcpClientManager.listTools(scope);
        Set<String> connectedServerIds = connectedTools.stream()
                .map(ToolDefinition::serverName)
                .collect(Collectors.toSet());

        addConnectedMcpTools(entries, connectedTools);
        addInstalledMcpServers(entries, workspaceRoots, connectedServerIds);

        entries.sort(Comparator.comparingInt(this::sourceOrder)
                .thenComparing(UnifiedToolCatalogEntry::entryType)
                .thenComparing(UnifiedToolCatalogEntry::name, String.CASE_INSENSITIVE_ORDER));
        return new UnifiedToolCatalog(List.copyOf(entries));
    }

    private void addNativeTools(List<UnifiedToolCatalogEntry> entries) {
        localTools.all().stream()
                .sorted(Comparator.comparing(ToolCapability::name))
                .forEach(tool -> entries.add(new UnifiedToolCatalogEntry(
                        "avento:" + tool.name(),
                        UnifiedToolCatalogEntry.ENTRY_TYPE_TOOL,
                        tool.name(),
                        UnifiedToolCatalogEntry.SOURCE_AVENTO_NATIVE,
                        "avento",
                        nullToEmpty(tool.summary()),
                        tool.category() == null ? "" : tool.category().name(),
                        tool.riskLevel() == null ? "" : tool.riskLevel().name(),
                        UnifiedToolCatalogEntry.AVAILABILITY_READY,
                        false,
                        "")));
    }

    private void addConnectedMcpTools(List<UnifiedToolCatalogEntry> entries, List<ToolDefinition> connectedTools) {
        for (ToolDefinition tool : connectedTools) {
            String source = DOCKER_GATEWAY_SERVER_ID.equals(tool.serverName())
                    ? UnifiedToolCatalogEntry.SOURCE_DOCKER_MCP
                    : UnifiedToolCatalogEntry.SOURCE_LOCAL_MCP;
            entries.add(new UnifiedToolCatalogEntry(
                    source.toLowerCase() + ":" + tool.serverName() + ":" + tool.exposedName(),
                    UnifiedToolCatalogEntry.ENTRY_TYPE_TOOL,
                    tool.exposedName(),
                    source,
                    tool.serverName(),
                    nullToEmpty(tool.description()),
                    "",
                    "",
                    UnifiedToolCatalogEntry.AVAILABILITY_READY,
                    false,
                    ""));
        }
    }

    private void addInstalledMcpServers(
            List<UnifiedToolCatalogEntry> entries, List<String> workspaceRoots, Set<String> connectedServerIds) {
        Set<String> representedTools = new HashSet<>();
        entries.stream()
                .filter(entry -> UnifiedToolCatalogEntry.ENTRY_TYPE_TOOL.equals(entry.entryType()))
                .forEach(entry -> representedTools.add(entry.serverId() + ":" + entry.name()));

        for (ServerDescriptor server : mcpServerCatalogService.catalog(workspaceRoots)) {
            if (connectedServerIds.contains(server.id())) {
                continue;
            }

            List<ToolDefinition> cachedTools = mcpServerCatalogService.knownTools(server.id());
            if (!cachedTools.isEmpty()) {
                addCachedTools(entries, representedTools, server, cachedTools);
                continue;
            }
            entries.add(serverEntry(server));
        }
    }

    private void addCachedTools(
            List<UnifiedToolCatalogEntry> entries,
            Set<String> representedTools,
            ServerDescriptor server,
            List<ToolDefinition> cachedTools) {
        String source = sourceFor(server.id());
        String availability = server.available()
                ? UnifiedToolCatalogEntry.AVAILABILITY_AVAILABLE
                : UnifiedToolCatalogEntry.AVAILABILITY_UNAVAILABLE;
        for (ToolDefinition tool : cachedTools) {
            if (!representedTools.add(server.id() + ":" + tool.exposedName())) {
                continue;
            }
            entries.add(new UnifiedToolCatalogEntry(
                    source.toLowerCase() + ":" + server.id() + ":" + tool.exposedName(),
                    UnifiedToolCatalogEntry.ENTRY_TYPE_TOOL,
                    tool.exposedName(),
                    source,
                    server.id(),
                    nullToEmpty(tool.description()),
                    server.profile(),
                    "",
                    availability,
                    server.available(),
                    server.available() ? "" : nullToEmpty(server.unavailableReason())));
        }
    }

    private UnifiedToolCatalogEntry serverEntry(ServerDescriptor server) {
        boolean available = server.available();
        boolean connectedWithoutTools = available && server.connected();
        return new UnifiedToolCatalogEntry(
                "server:" + server.id(),
                UnifiedToolCatalogEntry.ENTRY_TYPE_SERVER,
                server.name(),
                sourceFor(server.id()),
                server.id(),
                nullToEmpty(server.description()),
                server.profile(),
                "",
                !available
                        ? UnifiedToolCatalogEntry.AVAILABILITY_UNAVAILABLE
                        : connectedWithoutTools
                                ? UnifiedToolCatalogEntry.AVAILABILITY_DEGRADED
                                : UnifiedToolCatalogEntry.AVAILABILITY_AVAILABLE,
                available && !connectedWithoutTools,
                !available
                        ? nullToEmpty(server.unavailableReason())
                        : connectedWithoutTools
                                ? "Conectado, mas não anunciou nenhuma ferramenta no tools/list."
                                : "");
    }

    private String sourceFor(String serverId) {
        return DOCKER_GATEWAY_SERVER_ID.equals(serverId)
                ? UnifiedToolCatalogEntry.SOURCE_DOCKER_MCP
                : UnifiedToolCatalogEntry.SOURCE_LOCAL_MCP;
    }

    private int sourceOrder(UnifiedToolCatalogEntry entry) {
        return switch (entry.source()) {
            case UnifiedToolCatalogEntry.SOURCE_AVENTO_NATIVE -> 0;
            case UnifiedToolCatalogEntry.SOURCE_LOCAL_MCP -> 1;
            case UnifiedToolCatalogEntry.SOURCE_DOCKER_MCP -> 2;
            default -> 3;
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
