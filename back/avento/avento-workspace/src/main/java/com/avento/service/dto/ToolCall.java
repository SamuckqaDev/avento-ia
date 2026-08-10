package com.avento.service.dto;

import tools.jackson.databind.JsonNode;

public record ToolCall(String id, String name, JsonNode arguments) {}
