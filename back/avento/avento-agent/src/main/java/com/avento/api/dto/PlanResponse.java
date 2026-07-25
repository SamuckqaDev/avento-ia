package com.avento.api.dto;

import com.avento.model.AgentPlan;
import java.util.List;

public record PlanResponse(
        Long id,
        Long chatId,
        String goal,
        String status,
        Long currentTaskId,
        String currentRunId,
        List<TaskResponse> tasks) {

    public static PlanResponse from(AgentPlan plan, List<TaskResponse> tasks) {
        return new PlanResponse(
                plan.getId(),
                plan.getChatId(),
                plan.getGoal(),
                plan.getStatus(),
                plan.getCurrentTaskId(),
                plan.getCurrentRunId(),
                tasks == null ? List.of() : List.copyOf(tasks));
    }
}
