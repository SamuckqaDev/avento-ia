package com.avento.dto;

import tools.jackson.databind.JsonNode;

public record ToolCall(String id, String name, JsonNode arguments) {}
