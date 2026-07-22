package com.avento.service.plan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanDefinition(List<AgentTaskDefinition> tasks) {}
