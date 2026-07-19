package com.avento.service.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.ConnectionResult;
import com.avento.service.dto.ServerDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class McpServerCatalogServiceTest {

    private final String originalProjectRoot = System.getProperty("avento.project.root");

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreProjectRoot() {
        if (originalProjectRoot == null) {
            System.clearProperty("avento.project.root");
        } else {
            System.setProperty("avento.project.root", originalProjectRoot);
        }
    }

    @Test
    void exposesLocalCatalogAndMarksConditionalServersUnavailable() {
        McpClientManager manager = manager();
        MockEnvironment environment = configuredEnvironment();
        McpServerCatalogService service =
                new McpServerCatalogService(manager, environment, new ProjectDatabaseDiscoveryService());

        List<ServerDescriptor> catalog = service.catalog(List.of());

        assertTrue(catalog.stream().anyMatch(server -> server.id().equals("markitdown") && server.local()));
        assertTrue(catalog.stream().anyMatch(server -> server.id().equals("memory") && server.local()));
        assertTrue(catalog.stream().anyMatch(server -> server.id().equals("dbhub") && !server.available()));
        assertTrue(catalog.stream().anyMatch(server -> server.id().equals("searxng") && !server.available()));
        assertTrue(catalog.stream().anyMatch(server -> server.id().equals("docker-gateway") && !server.available()));
        assertFalse(catalog.isEmpty());
    }

    @Test
    void returnsExplicitFailureForUnknownServer() {
        McpClientManager manager = manager();
        McpServerCatalogService service =
                new McpServerCatalogService(manager, new MockEnvironment(), new ProjectDatabaseDiscoveryService());

        List<ConnectionResult> results = service.connect(List.of("missing"), List.of());

        assertEquals(1, results.size());
        assertFalse(results.getFirst().connected());
        assertEquals("missing", results.getFirst().serverName());
        assertFalse(manager.status().path("connected").asBoolean());
    }

    @Test
    void autoConnectsMarkitdownByDefault() {
        McpServerCatalogService service =
                new McpServerCatalogService(manager(), configuredEnvironment(), new ProjectDatabaseDiscoveryService());

        assertTrue(service.autoConnectServerIds().contains("markitdown"));
        assertFalse(service.autoConnectServerIds().contains("desktop-commander"));
    }

    @Test
    void doesNotRestartAnAlreadyConnectedServer() {
        AlreadyConnectedManager manager = new AlreadyConnectedManager();
        McpServerCatalogService service =
                new McpServerCatalogService(manager, configuredEnvironment(), new ProjectDatabaseDiscoveryService());

        List<ConnectionResult> results = service.connect(List.of("memory"), List.of());

        assertEquals(1, results.size());
        assertTrue(results.getFirst().connected());
        assertEquals(0, manager.connectCalls);
    }

    @Test
    void findsMarkitdownInstalledAtProjectRoot() throws Exception {
        System.setProperty("avento.project.root", tempDir.toString());
        Path command = tempDir.resolve(".avento-tools/mcp/bin/markitdown-mcp");
        Files.createDirectories(command.getParent());
        Files.writeString(command, "#!/bin/sh\nexit 0");
        command.toFile().setExecutable(true);
        McpServerCatalogService service = new McpServerCatalogService(
                manager(),
                configuredEnvironment()
                        .withProperty(
                                "avento.documents.markitdown-mcp-command", ".avento-tools/mcp/bin/markitdown-mcp"),
                new ProjectDatabaseDiscoveryService());

        ServerDescriptor markitdown = service.catalog(List.of()).stream()
                .filter(server -> server.id().equals("markitdown"))
                .findFirst()
                .orElseThrow();

        assertTrue(markitdown.available());
    }

    private McpClientManager manager() {
        return new McpClientManager(new ObjectMapper(), Duration.ofSeconds(1));
    }

    private MockEnvironment configuredEnvironment() {
        return new MockEnvironment()
                .withProperty("avento.mcp.packages.memory", "memory-mcp@1")
                .withProperty("avento.mcp.packages.sequential-thinking", "thinking-mcp@1")
                .withProperty("avento.mcp.packages.desktop-commander", "desktop-mcp@1")
                .withProperty("avento.mcp.packages.macos-automator", "automator-mcp@1")
                .withProperty("avento.mcp.packages.apple", "apple-mcp@1")
                .withProperty("avento.mcp.packages.playwright", "playwright-mcp@1")
                .withProperty("avento.mcp.packages.chrome-devtools", "chrome-mcp@1")
                .withProperty("avento.mcp.packages.puppeteer", "puppeteer-mcp@1")
                .withProperty("avento.mcp.packages.searxng", "searxng-mcp@1");
    }

    private static final class AlreadyConnectedManager extends McpClientManager {

        private int connectCalls;

        private AlreadyConnectedManager() {
            super(new ObjectMapper(), Duration.ofSeconds(1));
        }

        @Override
        public boolean isConnected(String scope, String serverName) {
            return true;
        }

        @Override
        public synchronized ConnectionResult connect(
                String scope,
                String serverName,
                List<String> command,
                Map<String, String> environment,
                Set<String> reservedToolNames) {
            connectCalls++;
            return ConnectionResult.failedFor(serverName, "Nao deveria reconectar.");
        }
    }
}
