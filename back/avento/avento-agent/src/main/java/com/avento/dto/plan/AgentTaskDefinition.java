package com.avento.dto.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentTaskDefinition(String title, String details, JsonNode targetFiles, boolean needsApproval) {}
