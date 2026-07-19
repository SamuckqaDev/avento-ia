package com.avento.service.execution;

import com.avento.model.AgentTimelineEvent;
import com.avento.repository.AgentRunJobRepository;
import com.avento.repository.AgentTimelineEventRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApprovalReplayGuard {

    private final AgentRunJobRepository runJobRepository;
    private final AgentTimelineEventRepository timelineEventRepository;

    public boolean isPending(UUID userId, String runId, String approvalId) {
        if (userId == null || runId == null || runId.isBlank() || approvalId == null || approvalId.isBlank()) {
            return false;
        }
        boolean terminalRun = runJobRepository
                .findByRunIdAndUserId(runId, userId)
                .map(job -> job.terminal())
                .orElse(false);
        if (terminalRun) {
            return false;
        }
        return timelineEventRepository
                .findFirstByUserIdAndRunIdAndApprovalIdOrderByCreatedAtDesc(userId, runId, approvalId)
                .map(AgentTimelineEvent::getEventType)
                .map("tool.approval.required"::equals)
                .orElse(true);
    }
}
