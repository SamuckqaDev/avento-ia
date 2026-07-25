package com.avento.service.dto;

import com.avento.service.orchestration.AgentRunRegistry.AgentRunStatus;
import java.time.LocalDateTime;
import java.util.List;

public record AgentRunSnapshot(
        String runId,
        String objective,
        List<String> workspaceRoots,
        AgentRunStatus status,
        String lastEventType,
        String approvalId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public AgentRunSnapshot with(AgentRunStatus status, String eventType, String approvalId) {
        return new AgentRunSnapshot(
                runId, objective, workspaceRoots, status, eventType, approvalId, createdAt, LocalDateTime.now());
    }

    public AgentRunSnapshot touch() {
        return new AgentRunSnapshot(
                runId, objective, workspaceRoots, status, lastEventType, approvalId, createdAt, LocalDateTime.now());
    }
}
