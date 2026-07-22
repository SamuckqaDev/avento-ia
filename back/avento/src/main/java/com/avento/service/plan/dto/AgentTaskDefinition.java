package com.avento.service.plan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentTaskDefinition(String title, String details, JsonNode targetFiles, boolean needsApproval) {}
