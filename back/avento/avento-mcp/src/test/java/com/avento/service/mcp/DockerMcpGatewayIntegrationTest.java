package com.avento.service.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.ConnectionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "AVENTO_RUN_DOCKER_MCP_INTEGRATION", matches = "true")
class DockerMcpGatewayIntegrationTest {

    private final McpClientManager manager = new McpClientManager(new ObjectMapper(), Duration.ofSeconds(30));

    @AfterEach
    void closeGateway() {
        manager.closeAll();
    }

    @Test
    void connectsAndListsContainersThroughOfficialGateway() {
        ConnectionResult connection = manager.connect(
                "docker",
                List.of(
                        "docker",
                        "mcp",
                        "gateway",
                        "run",
                        "--servers",
                        "docker",
                        "--transport",
                        "stdio",
                        "--block-secrets"),
                Map.of("DOCKER_MCP_IN_CONTAINER", "1"),
                Set.of());

        assertTrue(connection.connected(), connection.error());
        assertTrue(
                connection.tools().stream().anyMatch(tool -> tool.originalName().equals("docker")));

        JsonNode result = manager.callTool("docker", Map.of("args", List.of("ps", "--format", "{{.Names}}")));

        assertFalse(result.path("isError").asBoolean(true), result.toPrettyString());
        assertTrue(result.toString().contains("avento-postgres"), result.toPrettyString());
    }
}
