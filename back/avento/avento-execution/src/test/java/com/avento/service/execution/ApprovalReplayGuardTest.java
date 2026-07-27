package com.avento.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.model.AgentRunJob;
import com.avento.model.AgentTimelineEvent;
import com.avento.repository.AgentRunJobRepository;
import com.avento.repository.AgentTimelineEventRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalReplayGuardTest {

    @Test
    void onlyTreatsTheLatestRequiredEventOfANonTerminalRunAsPending() {
        AgentRunJobRepository jobs = mock(AgentRunJobRepository.class);
        AgentTimelineEventRepository timeline = mock(AgentTimelineEventRepository.class);
        ApprovalReplayGuard guard = new ApprovalReplayGuard(jobs, timeline);
        UUID userId = UUID.randomUUID();
        AgentRunJob running = job("run_active", userId, AgentRunJob.Status.RUNNING);
        when(jobs.findByRunIdAndUserId("run_active", userId)).thenReturn(Optional.of(running));
        when(timeline.findFirstByUserIdAndRunIdAndApprovalIdOrderByCreatedAtDesc(userId, "run_active", "approval_1"))
                .thenReturn(Optional.of(event("tool.approval.required")));

        assertThat(guard.isPending(userId, "run_active", "approval_1")).isTrue();

        when(timeline.findFirstByUserIdAndRunIdAndApprovalIdOrderByCreatedAtDesc(userId, "run_active", "approval_1"))
                .thenReturn(Optional.of(event("tool.approval.accepted")));
        assertThat(guard.isPending(userId, "run_active", "approval_1")).isFalse();
    }

    @Test
    void neverReplaysAnApprovalFromATerminalRun() {
        AgentRunJobRepository jobs = mock(AgentRunJobRepository.class);
        AgentTimelineEventRepository timeline = mock(AgentTimelineEventRepository.class);
        ApprovalReplayGuard guard = new ApprovalReplayGuard(jobs, timeline);
        UUID userId = UUID.randomUUID();
        AgentRunJob completed = job("run_done", userId, AgentRunJob.Status.COMPLETED);
        when(jobs.findByRunIdAndUserId("run_done", userId)).thenReturn(Optional.of(completed));

        assertThat(guard.isPending(userId, "run_done", "approval_old")).isFalse();
    }

    private AgentRunJob job(String runId, UUID userId, AgentRunJob.Status status) {
        AgentRunJob job = new AgentRunJob();
        job.setRunId(runId);
        job.setUserId(userId);
        job.setStatus(status);
        return job;
    }

    private AgentTimelineEvent event(String eventType) {
        AgentTimelineEvent event = new AgentTimelineEvent();
        event.setEventType(eventType);
        return event;
    }
}
