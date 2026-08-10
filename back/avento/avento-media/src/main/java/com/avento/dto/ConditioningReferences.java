package com.avento.dto;

import tools.jackson.databind.JsonNode;

public record ConditioningReferences(JsonNode positive, JsonNode negative) {}
