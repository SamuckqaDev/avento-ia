package com.avento.api.dto;

import jakarta.validation.constraints.Size;

public record AgentProfileUpdateRequest(
        @Size(max = 80) String name,
        @Size(max = 4000) String specialty,
        @Size(max = 4000) String systemInstructions,
        @Size(max = 4000) String allowedTools,
        @Size(max = 4000) String triggers,
        @Size(max = 120) String model,
        Boolean isDefault) {}
