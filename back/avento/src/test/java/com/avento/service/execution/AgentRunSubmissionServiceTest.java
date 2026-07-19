package com.avento.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.config.RedisExecutionProperties;
import com.avento.model.AgentRunJob;
import com.avento.model.ExecutionOutboxEvent;
import com.avento.repository.AgentRunJobRepository;
import com.avento.repository.ExecutionOutboxEventRepository;
import com.avento.service.dto.AgentRunView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.Disposables;

class AgentRunSubmissionServiceTest {

    @Test
    void savesTheJobAndOutboxInOneSubmission() {
        AgentRunJobRepository jobs = mock(AgentRunJobRepository.class);
        ExecutionOutboxEventRepository outbox = mock(ExecutionOutboxEventRepository.class);
        when(jobs.save(any())).thenAnswer(invocation -> {
            AgentRunJob job = invocation.getArgument(0);
            job.setId(99L);
            return job;
        });
        RedisExecutionProperties properties = new RedisExecutionProperties();
        properties.setEnabled(true);
        AgentRunSubmissionService service = service(jobs, outbox, properties);
        UUID userId = UUID.randomUUID();

        AgentRunJob job = service.submit(
                userId, 12L, new ObjectMapper().createObjectNode().put("model", "qwen3:8b"));

        assertThat(job.getRunId()).startsWith("run_");
        assertThat(job.getStatus()).isEqualTo(AgentRunJob.Status.QUEUED);
        ArgumentCaptor<ExecutionOutboxEvent> event = ArgumentCaptor.forClass(ExecutionOutboxEvent.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getStreamKey()).isEqualTo("avento:jobs:agent");
        assertThat(event.getValue().getPayload()).contains("\"jobId\":99");
        assertThat(event.getValue().getAggregateId()).isEqualTo(job.getRunId());
    }

    @Test
    void cancellationChangesTheDurableStatusAndStopsAnActiveRun() {
        AgentRunJobRepository jobs = mock(AgentRunJobRepository.class);
        ExecutionOutboxEventRepository outbox = mock(ExecutionOutboxEventRepository.class);
        AgentRunCancellationRegistry cancellations = new AgentRunCancellationRegistry();
        Disposable activeRun = Disposables.single();
        cancellations.register("run_cancel", activeRun);
        AtomicReference<String> published = new AtomicReference<>();
        RunEventPublisher events = (runId, ownerId, chatId, raw) -> published.set(raw);
        RedisExecutionProperties properties = new RedisExecutionProperties();
        AgentRunJob job = new AgentRunJob();
        UUID userId = UUID.randomUUID();
        job.setId(8L);
        job.setRunId("run_cancel");
        job.setUserId(userId);
        job.setChatId(3L);
        job.setRequestPayload("{}");
        when(jobs.findByRunIdAndUserId("run_cancel", userId)).thenReturn(Optional.of(job));
        AgentRunSubmissionService service =
                new AgentRunSubmissionService(jobs, outbox, new ObjectMapper(), properties, cancellations, events);

        boolean cancelled = service.requestCancellation("run_cancel", userId);

        assertThat(cancelled).isTrue();
        assertThat(job.getStatus()).isEqualTo(AgentRunJob.Status.CANCEL_REQUESTED);
        assertThat(activeRun.isDisposed()).isTrue();
        assertThat(published.get()).contains("agent.run.cancelled");
    }

    @Test
    void keepsTheDurableRunWaitingWhenApprovalContinuationRequestsAnotherApproval() {
        AgentRunJobRepository jobs = mock(AgentRunJobRepository.class);
        ExecutionOutboxEventRepository outbox = mock(ExecutionOutboxEventRepository.class);
        AgentRunJob job = waitingJob("run_approval");
        when(jobs.findByRunIdAndUserId(job.getRunId(), job.getUserId())).thenReturn(Optional.of(job));
        AgentRunSubmissionService service = service(jobs, outbox, new RedisExecutionProperties());

        service.finishAfterApproval(job.getRunId(), job.getUserId(), true);

        assertThat(job.getStatus()).isEqualTo(AgentRunJob.Status.WAITING_APPROVAL);
        assertThat(job.getCompletedAt()).isNull();
    }

    @Test
    void recordsFailureRaisedWhileContinuingAnApprovedRun() {
        AgentRunJobRepository jobs = mock(AgentRunJobRepository.class);
        ExecutionOutboxEventRepository outbox = mock(ExecutionOutboxEventRepository.class);
        AgentRunJob job = waitingJob("run_failed_approval");
        when(jobs.findByRunIdAndUserId(job.getRunId(), job.getUserId())).thenReturn(Optional.of(job));
        AgentRunSubmissionService service = service(jobs, outbox, new RedisExecutionProperties());

        service.failAfterApproval(job.getRunId(), job.getUserId(), new IllegalStateException("tool failed"));

        assertThat(job.getStatus()).isEqualTo(AgentRunJob.Status.FAILED);
        assertThat(job.getLastError()).isEqualTo("tool failed");
        assertThat(job.getCompletedAt()).isNotNull();
    }

    @Test
    void returnsTheLatestDurableActiveRunForAChat() {
        AgentRunJobRepository jobs = mock(AgentRunJobRepository.class);
        ExecutionOutboxEventRepository outbox = mock(ExecutionOutboxEventRepository.class);
        UUID userId = UUID.randomUUID();
        AgentRunJob job = new AgentRunJob();
        job.setId(12L);
        job.setRunId("run_active");
        job.setUserId(userId);
        job.setChatId(7L);
        job.setRequestPayload("{}");
        job.setStatus(AgentRunJob.Status.RUNNING);
        when(jobs.findFirstByChatIdAndUserIdAndStatusInOrderByCreatedAtDesc(eq(7L), eq(userId), anyCollection()))
                .thenReturn(Optional.of(job));
        AgentRunSubmissionService service = service(jobs, outbox, new RedisExecutionProperties());

        AgentRunView activeRun = service.findActiveForChat(7L, userId).orElseThrow();

        assertThat(activeRun.runId()).isEqualTo("run_active");
        assertThat(activeRun.chatId()).isEqualTo(7L);
        assertThat(activeRun.status()).isEqualTo("RUNNING");
    }

    @Test
    void claimsAQueuedJobOnlyWhenTheAtomicUpdateSucceeds() {
        AgentRunJobRepository jobs = mock(AgentRunJobRepository.class);
        ExecutionOutboxEventRepository outbox = mock(ExecutionOutboxEventRepository.class);
        AgentRunJob claimedJob = new AgentRunJob();
        claimedJob.setId(31L);
        claimedJob.setStatus(AgentRunJob.Status.RUNNING);
        when(jobs.claimForExecution(eq(31L), eq(AgentRunJob.Status.QUEUED), eq(AgentRunJob.Status.RUNNING), any()))
                .thenReturn(1);
        when(jobs.findById(31L)).thenReturn(Optional.of(claimedJob));
        AgentRunSubmissionService service = service(jobs, outbox, new RedisExecutionProperties());

        assertThat(service.claimForExecution(31L)).contains(claimedJob);

        when(jobs.claimForExecution(eq(32L), eq(AgentRunJob.Status.QUEUED), eq(AgentRunJob.Status.RUNNING), any()))
                .thenReturn(0);
        assertThat(service.claimForExecution(32L)).isEmpty();
    }

    private AgentRunJob waitingJob(String runId) {
        AgentRunJob job = new AgentRunJob();
        job.setId(10L);
        job.setRunId(runId);
        job.setUserId(UUID.randomUUID());
        job.setChatId(4L);
        job.setRequestPayload("{}");
        job.setStatus(AgentRunJob.Status.WAITING_APPROVAL);
        return job;
    }

    private AgentRunSubmissionService service(
            AgentRunJobRepository jobs, ExecutionOutboxEventRepository outbox, RedisExecutionProperties properties) {
        return new AgentRunSubmissionService(
                jobs,
                outbox,
                new ObjectMapper(),
                properties,
                new AgentRunCancellationRegistry(),
                (runId, userId, chatId, raw) -> {});
    }
}
