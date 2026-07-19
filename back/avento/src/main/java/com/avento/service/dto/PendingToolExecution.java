package com.avento.service.dto;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;

public record PendingToolExecution(
        String model,
        ArrayNode messages,
        int executedToolCalls,
        int round,
        ToolCall toolCall,
        boolean continueAfterTool,
        List<String> workspaceRoots,
        String runId) {}
