package com.avento.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.dto.ConnectionResult;
import com.avento.dto.ServerDescriptor;
import com.avento.dto.ToolDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

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

    /** O gateway atualizado usa o perfil {@code default} quando não há subconjunto configurado. */
    @Test
    @EnabledIf("dockerDesktopRunning")
    void launchesTheDockerGatewayWithTheDefaultProfile() {
        RecordingManager manager = new RecordingManager();
        new McpServerCatalogService(manager, configuredEnvironment(), new ProjectDatabaseDiscoveryService())
                .connect(List.of("docker-gateway"), List.of());

        assertEquals(List.of("docker", "mcp", "gateway", "run", "--profile", "default"), manager.command);
    }

    @Test
    @EnabledIf("dockerDesktopRunning")
    void launchesTheDockerGatewayWithTheConfiguredProfile() {
        RecordingManager manager = new RecordingManager();
        new McpServerCatalogService(
                        manager,
                        configuredEnvironment().withProperty("avento.mcp.docker-gateway.profile", "avento"),
                        new ProjectDatabaseDiscoveryService())
                .connect(List.of("docker-gateway"), List.of());

        assertEquals(List.of("docker", "mcp", "gateway", "run", "--profile", "avento"), manager.command);
    }

    /** Com a lista configurada, ela vira {@code --servers}; sem ela, o gateway usa o registry. */
    @Test
    @EnabledIf("dockerDesktopRunning")
    void passesTheConfiguredServerListToTheGateway() {
        RecordingManager manager = new RecordingManager();
        new McpServerCatalogService(
                        manager,
                        configuredEnvironment().withProperty("avento.mcp.docker-gateway.servers", "github,postgres"),
                        new ProjectDatabaseDiscoveryService())
                .connect(List.of("docker-gateway"), List.of());

        assertEquals(List.of("docker", "mcp", "gateway", "run", "--servers", "github,postgres"), manager.command);
    }

    private static final class RecordingManager extends McpClientManager {

        private List<String> command = List.of();

        private RecordingManager() {
            super(new ObjectMapper(), Duration.ofSeconds(1));
        }

        @Override
        public synchronized ConnectionResult connect(
                String scope,
                String serverName,
                List<String> command,
                Map<String, String> environment,
                Set<String> reservedToolNames) {
            this.command = List.copyOf(command);
            return ConnectionResult.failedFor(serverName, "nao conecta de verdade no teste");
        }
    }

    static boolean dockerDesktopRunning() {
        return java.nio.file.Files.exists(
                java.nio.file.Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock"));
    }

    /**
     * Sem o Docker Desktop, o gateway tem de explicar POR QUE — nao devolver "comando nao
     * encontrado", que e falso: o binario `docker` existe e o daemon (Colima) esta funcionando.
     */
    @Test
    @DisabledIf("dockerDesktopRunning")
    void explainsThatTheGatewayNeedsDockerDesktopAndNotJustADaemon() {
        RecordingManager manager = new RecordingManager();
        List<ConnectionResult> results = new McpServerCatalogService(
                        manager, configuredEnvironment(), new ProjectDatabaseDiscoveryService())
                .connect(List.of("docker-gateway"), List.of());

        assertFalse(results.getFirst().connected());
        assertTrue(results.getFirst().error().contains("Docker Desktop"));
        assertTrue(results.getFirst().error().contains("Colima"));
        assertEquals(List.of(), manager.command, "nao deve nem tentar lancar o processo");
    }

    /**
     * O cache por digest existe para que a descoberta de capacidades saiba QUAIS ferramentas um
     * servidor em container oferece sem subir o container.
     *
     * <p>Sem isto, o {@code search_capabilities} anuncia apenas o servidor, e o modelo precisa
     * adivinhar: para achar o download de página web ele teria de procurar por "fetch", o nome do
     * servidor, e não por "baixar página", que é o que ele quer fazer.
     */
    @Test
    void answersWhichToolsAContainerServerOffersWithoutStartingIt() {
        McpToolSchemaCache cache = new McpToolSchemaCache(
                new ObjectMapper(),
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "avento-catalog-test.json"),
                image -> java.util.Optional.of("sha256:fixo"));
        cache.record(
                "mcp/fetch",
                java.util.List.of(new ToolDefinition("fetch", "fetch", "fetch", "Baixa uma URL", java.util.Map.of())));

        McpServerCatalogService service = new McpServerCatalogService(
                manager(),
                configuredEnvironment(),
                new ProjectDatabaseDiscoveryService(),
                new com.avento.service.tools.ToolExecutionContext(),
                provider(cache));

        assertThat(service.knownTools("fetch"))
                .extracting(ToolDefinition::exposedName)
                .containsExactly("fetch");
    }

    /** Servidor que não roda em container não tem imagem, logo não tem cache — e não mente dizendo que tem. */
    @Test
    void answersNothingForAServerThatDoesNotRunInAContainer() {
        McpServerCatalogService service = new McpServerCatalogService(
                manager(),
                configuredEnvironment(),
                new ProjectDatabaseDiscoveryService(),
                new com.avento.service.tools.ToolExecutionContext(),
                provider(null));

        assertThat(service.knownTools("playwright")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private org.springframework.beans.factory.ObjectProvider<McpToolSchemaCache> provider(McpToolSchemaCache cache) {
        var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(cache);
        return provider;
    }
}
