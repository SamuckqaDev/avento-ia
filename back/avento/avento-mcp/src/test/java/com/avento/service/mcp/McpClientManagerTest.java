package com.avento.service.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.ConnectionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class McpClientManagerTest {

    private final McpClientManager manager = new McpClientManager(new ObjectMapper(), Duration.ofSeconds(5));

    @AfterEach
    void closeClients() {
        manager.closeAll();
    }

    @Test
    void discoversAndCallsToolsThroughOfficialSdk() throws Exception {
        Assumptions.assumeTrue(nodeIsAvailable());
        Path server = Path.of(getClass().getResource("/mcp/fake-server.mjs").toURI());

        ConnectionResult connection = manager.connect("fake", List.of("node", server.toString()), Map.of(), Set.of());

        assertTrue(connection.connected(), connection.error());
        assertEquals(1, connection.tools().size());
        assertEquals("echo", connection.tools().get(0).exposedName());

        JsonNode result = manager.callTool("echo", Map.of("text", "Avento MCP funcionando"));

        assertFalse(result.path("isError").asBoolean(true));
        assertTrue(result.path("content").toString().contains("Avento MCP funcionando"));
        assertTrue(manager.status().path("connected").asBoolean());
        assertEquals("2025-11-25", manager.status().path("protocolTarget").asText());
    }

    @Test
    void namespacesCollidingExternalTools() throws Exception {
        Assumptions.assumeTrue(nodeIsAvailable());
        Path server = Path.of(getClass().getResource("/mcp/fake-server.mjs").toURI());

        ConnectionResult connection =
                manager.connect("fake", List.of("node", server.toString()), Map.of(), Set.of("echo"));

        assertTrue(connection.connected(), connection.error());
        assertEquals("fake__echo", connection.tools().get(0).exposedName());
    }

    @Test
    void isolatesClientsAndToolsByScope() throws Exception {
        Assumptions.assumeTrue(nodeIsAvailable());
        Path server = Path.of(getClass().getResource("/mcp/fake-server.mjs").toURI());

        assertTrue(manager.connect("user:a:chat:1", "fake", List.of("node", server.toString()), Map.of(), Set.of())
                .connected());
        assertTrue(manager.connect("user:b:chat:2", "fake", List.of("node", server.toString()), Map.of(), Set.of())
                .connected());

        manager.disconnectScope("user:a:chat:1");

        assertFalse(manager.hasTool("user:a:chat:1", "echo"));
        assertTrue(manager.hasTool("user:b:chat:2", "echo"));
    }

    @Test
    void keepsPreviousClientWhenReconnectFails() throws Exception {
        Assumptions.assumeTrue(nodeIsAvailable());
        Path server = Path.of(getClass().getResource("/mcp/fake-server.mjs").toURI());
        String scope = "user:a:chat:1";

        assertTrue(manager.connect(scope, "fake", List.of("node", server.toString()), Map.of(), Set.of())
                .connected());

        ConnectionResult failed =
                manager.connect(scope, "fake", List.of("command-that-does-not-exist"), Map.of(), Set.of());

        assertFalse(failed.connected());
        assertTrue(manager.isConnected(scope, "fake"));
        assertTrue(manager.hasTool(scope, "echo"));
    }

    private boolean nodeIsAvailable() {
        try {
            Process process = new ProcessBuilder("sh", "-lc", "command -v node").start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
