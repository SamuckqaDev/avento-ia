package com.avento.service.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(String id, String name, JsonNode arguments) {}
