package com.avento.service.mcp;

import com.avento.service.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Owns official MCP SDK clients and isolates discovery and routing by chat scope. */
@Service
public class McpClientManager {

    public static final String LOCAL_SCOPE = "local";

    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    private final ObjectMapper mapper;
    private final Duration requestTimeout;
    private final Map<ClientKey, ManagedClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ToolRoute>> routesByScope = new ConcurrentHashMap<>();

    public McpClientManager(
            ObjectMapper mapper, @Value("${avento.mcp.sdk.request-timeout:60s}") Duration requestTimeout) {
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
    }

    public ConnectionResult connect(
            String serverName, List<String> command, Map<String, String> environment, Set<String> reservedToolNames) {
        return connect(LOCAL_SCOPE, serverName, command, environment, reservedToolNames);
    }

    public synchronized ConnectionResult connect(
            String scope,
            String serverName,
            List<String> command,
            Map<String, String> environment,
            Set<String> reservedToolNames) {
        String normalizedScope = normalizeScope(scope);
        if (serverName == null || serverName.isBlank()) {
            return ConnectionResult.failed("Nome do servidor MCP é obrigatório.");
        }
        if (command == null || command.isEmpty() || command.getFirst().isBlank()) {
            return ConnectionResult.failed("Comando do servidor MCP é obrigatório.");
        }

        ClientKey key = new ClientKey(normalizedScope, serverName);
        ManagedClient replacement = null;
        try {
            ServerParameters parameters = ServerParameters.builder(command.getFirst())
                    .args(command.subList(1, command.size()))
                    .env(environment == null ? Map.of() : environment)
                    .build();
            StdioClientTransport transport =
                    new StdioClientTransport(parameters, new JacksonMcpJsonMapper(mapper.copy()));
            transport.setStdErrorHandler(line -> logger.debug("MCP {} [{}]: {}", serverName, normalizedScope, line));

            McpSyncClient client = McpClient.sync(transport)
                    .clientInfo(Implementation.builder("avento", "2.0.0").build())
                    .requestTimeout(requestTimeout)
                    .initializationTimeout(requestTimeout)
                    .build();
            client.initialize();

            replacement = new ManagedClient(key, client, new ArrayList<>());
            List<PreparedTool> prepared =
                    prepareTools(replacement, client.listTools().tools(), reservedToolNames, routes(normalizedScope));

            ManagedClient previous = clients.put(key, replacement);
            if (previous != null) {
                unregisterRoutes(previous);
            }
            registerPreparedTools(replacement, prepared);
            close(previous);

            List<ToolDefinition> discovered =
                    prepared.stream().map(PreparedTool::definition).toList();
            return new ConnectionResult(true, serverName, discovered, "");
        } catch (Exception exception) {
            close(replacement);
            String message = rootMessage(exception);
            logger.warn("Não foi possível conectar ao servidor MCP {} [{}]: {}", serverName, normalizedScope, message);
            logger.debug("Falha completa ao conectar ao servidor MCP " + serverName, exception);
            return ConnectionResult.failedFor(serverName, message);
        }
    }

    public void refreshTools(String serverName, Set<String> reservedToolNames) {
        refreshTools(LOCAL_SCOPE, serverName, reservedToolNames);
    }

    public synchronized void refreshTools(String scope, String serverName, Set<String> reservedToolNames) {
        String normalizedScope = normalizeScope(scope);
        ManagedClient managed = clients.get(new ClientKey(normalizedScope, serverName));
        if (managed == null) {
            return;
        }
        List<PreparedTool> prepared =
                prepareTools(managed, managed.client().listTools().tools(), reservedToolNames, routes(normalizedScope));
        unregisterRoutes(managed);
        managed.tools().clear();
        registerPreparedTools(managed, prepared);
    }

    public List<ToolDefinition> listTools() {
        return listTools(LOCAL_SCOPE);
    }

    public List<ToolDefinition> listTools(String scope) {
        return routes(normalizeScope(scope)).values().stream()
                .map(ToolRoute::definition)
                .sorted((left, right) -> left.exposedName().compareToIgnoreCase(right.exposedName()))
                .toList();
    }

    public boolean hasTool(String exposedName) {
        return hasTool(LOCAL_SCOPE, exposedName);
    }

    public boolean hasTool(String scope, String exposedName) {
        return routes(normalizeScope(scope)).containsKey(exposedName);
    }

    public boolean isConnected(String serverName) {
        return isConnected(LOCAL_SCOPE, serverName);
    }

    public boolean isConnected(String scope, String serverName) {
        return clients.containsKey(new ClientKey(normalizeScope(scope), serverName));
    }

    public JsonNode callTool(String exposedName, Map<String, Object> arguments) {
        return callTool(LOCAL_SCOPE, exposedName, arguments);
    }

    public JsonNode callTool(String scope, String exposedName, Map<String, Object> arguments) {
        ToolRoute route = routes(normalizeScope(scope)).get(exposedName);
        if (route == null) {
            return mapper.createObjectNode().put("error", "Tool not found or server disconnected: " + exposedName);
        }
        try {
            CallToolResult result = route.managedClient()
                    .client()
                    .callTool(new CallToolRequest(route.definition().originalName(), safeArguments(arguments)));
            ObjectNode response = mapper.createObjectNode();
            response.put("server", route.definition().serverName());
            response.put("tool", route.definition().originalName());
            response.put("isError", Boolean.TRUE.equals(result.isError()));
            response.set("content", mapper.valueToTree(result.content()));
            if (result.structuredContent() != null) {
                response.set("structuredContent", mapper.valueToTree(result.structuredContent()));
            }
            if (Boolean.TRUE.equals(result.isError())) {
                response.put("error", extractErrorMessage(result));
            }
            return response;
        } catch (Exception exception) {
            logger.warn("Falha ao executar ferramenta MCP {} [{}]", exposedName, normalizeScope(scope), exception);
            return mapper.createObjectNode().put("error", rootMessage(exception));
        }
    }

    public ObjectNode status() {
        return status(LOCAL_SCOPE);
    }

    public ObjectNode status(String scope) {
        String normalizedScope = normalizeScope(scope);
        ObjectNode status = mapper.createObjectNode();
        status.put("transport", "official-java-sdk");
        status.put("protocolTarget", "2025-11-25");
        status.put("scope", normalizedScope);
        List<ManagedClient> scopedClients = clients.values().stream()
                .filter(client -> client.key().scope().equals(normalizedScope))
                .sorted((left, right) ->
                        left.key().serverName().compareToIgnoreCase(right.key().serverName()))
                .toList();
        status.put("connected", !scopedClients.isEmpty());
        ArrayNode servers = status.putArray("servers");
        scopedClients.forEach(client -> {
            ObjectNode server = servers.addObject();
            server.put("name", client.key().serverName());
            server.put("tools", client.tools().size());
        });
        return status;
    }

    public void disconnect(String serverName) {
        disconnect(LOCAL_SCOPE, serverName);
    }

    public synchronized void disconnect(String scope, String serverName) {
        ManagedClient managed = clients.remove(new ClientKey(normalizeScope(scope), serverName));
        if (managed == null) {
            return;
        }
        unregisterRoutes(managed);
        close(managed);
    }

    public synchronized void disconnectScope(String scope) {
        String normalizedScope = normalizeScope(scope);
        clients.keySet().stream()
                .filter(key -> key.scope().equals(normalizedScope))
                .toList()
                .forEach(key -> disconnect(key.scope(), key.serverName()));
        routesByScope.remove(normalizedScope);
    }

    @PreDestroy
    public synchronized void closeAll() {
        List.copyOf(clients.keySet()).forEach(key -> disconnect(key.scope(), key.serverName()));
    }

    private List<PreparedTool> prepareTools(
            ManagedClient managed,
            List<Tool> tools,
            Set<String> reservedToolNames,
            Map<String, ToolRoute> scopedRoutes) {
        List<PreparedTool> prepared = new ArrayList<>();
        Set<String> reserved = reservedToolNames == null ? Set.of() : reservedToolNames;
        for (Tool tool : tools == null ? List.<Tool>of() : tools) {
            String exposedName = exposedName(managed, tool.name(), reserved, scopedRoutes, prepared);
            Map<String, Object> schema = tool.inputSchema();
            ToolDefinition definition = new ToolDefinition(
                    exposedName,
                    tool.name(),
                    managed.key().serverName(),
                    tool.description() == null ? "" : tool.description(),
                    schema == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(schema)));
            prepared.add(new PreparedTool(definition));
        }
        return List.copyOf(prepared);
    }

    private void registerPreparedTools(ManagedClient managed, List<PreparedTool> prepared) {
        Map<String, ToolRoute> scopedRoutes = routes(managed.key().scope());
        for (PreparedTool tool : prepared) {
            scopedRoutes.put(tool.definition().exposedName(), new ToolRoute(managed, tool.definition()));
            managed.tools().add(tool.definition().exposedName());
        }
    }

    private String exposedName(
            ManagedClient managed,
            String originalName,
            Set<String> reservedNames,
            Map<String, ToolRoute> scopedRoutes,
            List<PreparedTool> prepared) {
        if (!reservedNames.contains(originalName)
                && !routeBelongsToOtherServer(scopedRoutes.get(originalName), managed)
                && prepared.stream()
                        .noneMatch(tool -> tool.definition().exposedName().equals(originalName))) {
            return originalName;
        }
        String prefix = managed.key().serverName().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        String candidate = prefix + "__" + originalName;
        int suffix = 2;
        while (reservedNames.contains(candidate)
                || routeBelongsToOtherServer(scopedRoutes.get(candidate), managed)
                || preparedContainsName(prepared, candidate)) {
            candidate = prefix + "__" + originalName + "_" + suffix++;
        }
        return candidate;
    }

    private boolean preparedContainsName(List<PreparedTool> prepared, String candidate) {
        return prepared.stream()
                .anyMatch(tool -> tool.definition().exposedName().equals(candidate));
    }

    private boolean routeBelongsToOtherServer(ToolRoute route, ManagedClient replacement) {
        return route != null && !route.managedClient().key().equals(replacement.key());
    }

    private void unregisterRoutes(ManagedClient managed) {
        Map<String, ToolRoute> scopedRoutes = routes(managed.key().scope());
        managed.tools()
                .forEach(name -> scopedRoutes.computeIfPresent(
                        name, (ignored, route) -> route.managedClient() == managed ? null : route));
    }

    private void close(ManagedClient managed) {
        if (managed == null) {
            return;
        }
        try {
            managed.client().closeGracefully();
        } catch (Exception exception) {
            logger.debug("Erro ao encerrar MCP {}", managed.key().serverName(), exception);
        }
    }

    private Map<String, ToolRoute> routes(String scope) {
        return routesByScope.computeIfAbsent(scope, ignored -> new ConcurrentHashMap<>());
    }

    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(arguments);
        sanitized.keySet().removeIf(key -> key.startsWith("_"));
        return sanitized;
    }

    private String extractErrorMessage(CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "O servidor MCP retornou erro sem detalhes.";
        }
        return result.content().stream()
                .map(Object::toString)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("Erro MCP");
    }

    private String normalizeScope(String scope) {
        return scope == null || scope.isBlank() ? LOCAL_SCOPE : scope.trim();
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName()
                : root.getMessage();
    }
}
