package com.avento.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.dto.ToolDefinition;
import com.avento.service.mcp.McpClientManager;
import com.avento.service.tools.ToolExecutionContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class McpControllerDockerMcpPrecedenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesOneCanonicalToolAndRoutesItToTheDockerGateway() throws Exception {
        McpClientManager mcpClientManager = mock(McpClientManager.class);
        ToolDefinition dockerReadFile = new ToolDefinition(
                "docker-gateway__read_file",
                "read_file",
                "docker-gateway",
                "Le um arquivo no container.",
                Map.of("type", "object"));
        ObjectNode dockerResponse = mapper.createObjectNode().put("server", "docker-gateway").put("ok", true);
        when(mcpClientManager.listTools("local")).thenReturn(List.of(dockerReadFile));
        when(mcpClientManager.callTool(eq("local"), eq("docker-gateway__read_file"), anyMap()))
                .thenReturn(dockerResponse);

        McpController controller = configuredController(mcpClientManager);

        ArrayNode tools = controller.getAvailableToolsInternal();
        JsonNode result = controller.executeToolInternal("read_file", Map.of("path", "/workspace/README.md"));

        JsonNode readFile = null;
        for (JsonNode tool : tools) {
            if (tool.path("name").asText().equals("read_file")) {
                assertThat(readFile).as("uma única ferramenta canônica read_file").isNull();
                readFile = tool;
            }
        }
        assertThat(readFile).isNotNull();
        assertThat(readFile.path("mcpServer").asText()).isEqualTo("docker-gateway");
        assertThat(readFile.path("originalName").asText()).isEqualTo("read_file");
        assertThat(result).isSameAs(dockerResponse);
        verify(mcpClientManager).callTool("local", "docker-gateway__read_file", Map.of("path", "/workspace/README.md"));
    }

    private McpController configuredController(McpClientManager mcpClientManager) {
        McpController controller = new McpController();
        ReflectionTestUtils.setField(controller, "mcpClientManager", mcpClientManager);
        ReflectionTestUtils.setField(controller, "toolExecutionContext", new ToolExecutionContext());
        ReflectionTestUtils.setField(controller, "mcpSdkEnabled", true);
        return controller;
    }
}
