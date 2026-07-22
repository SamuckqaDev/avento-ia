package com.avento.api.dto;

import com.avento.model.AgentTask;

public record TaskResponse(
        Long id,
        int orderIndex,
        String title,
        String details,
        String status,
        boolean needsApproval,
        String resultSummary,
        String targetFiles,
        Long assignedAgentId,
        String assignedAgentName,
        String agentRationale) {

    public static TaskResponse from(AgentTask task) {
        return from(task, null);
    }

    public static TaskResponse from(AgentTask task, String assignedAgentName) {
        return new TaskResponse(
                task.getId(),
                task.getOrderIndex(),
                task.getTitle(),
                task.getDetails(),
                task.getStatus(),
                task.isNeedsApproval(),
                task.getResultSummary(),
                task.getTargetFiles(),
                task.getAssignedAgentId(),
                assignedAgentName,
                task.getAgentRationale());
    }
}
