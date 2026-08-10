package com.avento.service.dto;

import tools.jackson.databind.JsonNode;

public record ConditioningReferences(JsonNode positive, JsonNode negative) {}
