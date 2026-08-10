package com.avento.dto;

import java.util.List;
import tools.jackson.databind.node.ArrayNode;

public record PendingToolExecution(
        String model,
        ArrayNode messages,
        int executedToolCalls,
        int round,
        ToolCall toolCall,
        boolean continueAfterTool,
        List<String> workspaceRoots,
        String runId) {}
