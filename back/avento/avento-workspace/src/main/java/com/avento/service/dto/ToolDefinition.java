package com.avento.service.dto;

import java.util.Map;

public record ToolDefinition(
        String exposedName,
        String originalName,
        String serverName,
        String description,
        Map<String, Object> inputSchema) {}
