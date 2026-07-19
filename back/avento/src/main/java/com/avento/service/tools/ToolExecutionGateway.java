package com.avento.service.tools;

import com.avento.service.dto.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Stable boundary used by the agent regardless of whether a tool is local or MCP-backed. */
@Service
public class ToolExecutionGateway {

    private final ToolProvider toolProvider;
    private final ToolResultVerifier resultVerifier;
    private final ToolExecutionContext executionContext;

    public ToolExecutionGateway(
            ToolProvider toolProvider, ToolResultVerifier resultVerifier, ToolExecutionContext executionContext) {
        this.toolProvider = toolProvider;
        this.resultVerifier = resultVerifier;
        this.executionContext = executionContext;
    }

    public ArrayNode listTools() {
        return toolProvider.listTools();
    }

    public ArrayNode listTools(UUID userId, Long chatId, String runId) {
        try {
            return executionContext.call(new Context(userId, chatId, runId), toolProvider::listTools);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not list tools for the current chat", exception);
        }
    }

    public JsonNode execute(String toolName, Map<String, Object> arguments) throws Exception {
        return executionContext.call(executionContext.fromArguments(arguments), () -> {
            JsonNode result = toolProvider.execute(toolName, arguments);
            return resultVerifier.verify(toolName, arguments, result);
        });
    }
}
