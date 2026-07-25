package com.avento.service.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ConditioningReferences(JsonNode positive, JsonNode negative) {}
