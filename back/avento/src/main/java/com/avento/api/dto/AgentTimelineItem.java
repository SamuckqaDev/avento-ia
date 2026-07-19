package com.avento.api.dto;

import com.avento.model.AgentTimelineEvent;
import java.time.LocalDateTime;

public record AgentTimelineItem(
        Long id,
        String runId,
        String approvalId,
        String eventType,
        String toolName,
        String detail,
        String payload,
        LocalDateTime createdAt) {

    public static AgentTimelineItem from(AgentTimelineEvent event) {
        return new AgentTimelineItem(
                event.getId(),
                event.getRunId(),
                event.getApprovalId(),
                event.getEventType(),
                event.getToolName(),
                event.getDetail(),
                event.getPayload(),
                event.getCreatedAt());
    }
}
