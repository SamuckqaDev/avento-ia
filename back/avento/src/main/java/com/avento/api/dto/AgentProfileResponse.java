package com.avento.api.dto;

import com.avento.model.AgentProfile;
import java.time.LocalDateTime;

public record AgentProfileResponse(
        Long id,
        String name,
        String specialty,
        String systemInstructions,
        String allowedTools,
        String triggers,
        String model,
        boolean isDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AgentProfileResponse from(AgentProfile agent) {
        return new AgentProfileResponse(
                agent.getId(),
                agent.getName(),
                agent.getSpecialty(),
                agent.getSystemInstructions(),
                agent.getAllowedTools(),
                agent.getTriggers(),
                agent.getModel(),
                agent.isDefault(),
                agent.getCreatedAt(),
                agent.getUpdatedAt());
    }
}
