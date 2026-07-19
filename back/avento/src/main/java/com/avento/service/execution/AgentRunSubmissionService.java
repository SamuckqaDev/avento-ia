package com.avento.service.execution;

import com.avento.config.RedisExecutionProperties;
import com.avento.model.AgentRunJob;
import com.avento.model.ExecutionOutboxEvent;
import com.avento.repository.AgentRunJobRepository;
import com.avento.repository.ExecutionOutboxEventRepository;
import com.avento.service.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunSubmissionService {

    private static final List<AgentRunJob.Status> ACTIVE_STATUSES = List.of(
            AgentRunJob.Status.QUEUED,
            AgentRunJob.Status.RUNNING,
            AgentRunJob.Status.WAITING_APPROVAL,
            AgentRunJob.Status.CANCEL_REQUESTED);

    private final AgentRunJobRepository jobRepository;
    private final ExecutionOutboxEventRepository outboxRepository;
    private final ObjectMapper mapper;
    private final RedisExecutionProperties properties;
    private final AgentRunCancellationRegistry cancellationRegistry;
    private final RunEventPublisher eventPublisher;

    public AgentRunSubmissionService(
            AgentRunJobRepository jobRepository,
            ExecutionOutboxEventRepository outboxRepository,
            ObjectMapper mapper,
            RedisExecutionProperties properties,
            AgentRunCancellationRegistry cancellationRegistry,
            RunEventPublisher eventPublisher) {
        this.jobRepository = jobRepository;
        this.outboxRepository = outboxRepository;
        this.mapper = mapper;
        this.properties = properties;
        this.cancellationRegistry = cancellationRegistry;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AgentRunJob submit(UUID userId, Long chatId, JsonNode request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Redis execution is disabled");
        }
        AgentRunJob job = new AgentRunJob();
        job.setRunId("run_" + UUID.randomUUID().toString().substring(0, 8));
        job.setUserId(userId);
        job.setChatId(chatId);
        job.setRequestPayload(request.toString());
        job = jobRepository.save(job);
        createOutbox(job);
        return job;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedJobs() {
        if (!properties.isEnabled()) {
            return;
        }
        List<AgentRunJob> recoverable =
                jobRepository.findByStatusIn(List.of(AgentRunJob.Status.QUEUED, AgentRunJob.Status.RUNNING));
        for (AgentRunJob job : recoverable) {
            job.setStatus(AgentRunJob.Status.QUEUED);
            jobRepository.save(job);
            createOutbox(job);
        }
        List<AgentRunJob> expiredApprovals = jobRepository.findByStatusIn(List.of(AgentRunJob.Status.WAITING_APPROVAL));
        for (AgentRunJob job : expiredApprovals) {
            markFailed(job, new IllegalStateException("Approval expired after backend restart"));
        }
    }

    public Optional<AgentRunJob> findOwned(String runId, UUID userId) {
        return userId == null ? Optional.empty() : jobRepository.findByRunIdAndUserId(runId, userId);
    }

    public Optional<AgentRunView> findActiveForChat(Long chatId, UUID userId) {
        if (chatId == null || userId == null) {
            return Optional.empty();
        }
        return jobRepository
                .findFirstByChatIdAndUserIdAndStatusInOrderByCreatedAtDesc(chatId, userId, ACTIVE_STATUSES)
                .map(this::toView);
    }

    @Transactional
    public boolean requestCancellation(String runId, UUID userId) {
        Optional<AgentRunJob> optional = findOwned(runId, userId);
        if (optional.isEmpty() || optional.get().terminal()) {
            return false;
        }
        AgentRunJob job = optional.get();
        job.setStatus(AgentRunJob.Status.CANCEL_REQUESTED);
        jobRepository.save(job);
        cancellationRegistry.cancel(runId);
        eventPublisher.publish(runId, userId, job.getChatId(), lifecycleEvent("agent.run.cancelled", runId));
        return true;
    }

    @Transactional
    public Optional<AgentRunJob> claimForExecution(Long jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        LocalDateTime startedAt = LocalDateTime.now();
        int claimed = jobRepository.claimForExecution(
                jobId, AgentRunJob.Status.QUEUED, AgentRunJob.Status.RUNNING, startedAt);
        return claimed == 1 ? jobRepository.findById(jobId) : Optional.empty();
    }

    @Transactional
    public void markWaitingApproval(AgentRunJob job) {
        job.setStatus(AgentRunJob.Status.WAITING_APPROVAL);
        job.clearRequestPayload();
        jobRepository.save(job);
    }

    @Transactional
    public void markCompleted(AgentRunJob job) {
        job.setStatus(AgentRunJob.Status.COMPLETED);
        job.clearRequestPayload();
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);
    }

    @Transactional
    public void markCancelled(AgentRunJob job) {
        job.setStatus(AgentRunJob.Status.CANCELLED);
        job.clearRequestPayload();
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);
    }

    @Transactional
    public void markFailed(AgentRunJob job, Throwable error) {
        job.setStatus(AgentRunJob.Status.FAILED);
        job.clearRequestPayload();
        job.setLastError(error == null ? "Unknown execution error" : error.getMessage());
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);
    }

    @Transactional
    public void finishAfterApproval(String runId, UUID userId, boolean waitingForApproval) {
        findOwned(runId, userId).ifPresent(job -> {
            if (job.getStatus() == AgentRunJob.Status.WAITING_APPROVAL) {
                if (waitingForApproval) {
                    markWaitingApproval(job);
                } else {
                    markCompleted(job);
                }
            }
        });
    }

    @Transactional
    public void failAfterApproval(String runId, UUID userId, Throwable error) {
        findOwned(runId, userId).ifPresent(job -> {
            if (!job.terminal()) {
                markFailed(job, error);
            }
        });
    }

    @Transactional
    public void cancelAfterRejection(String runId, UUID userId) {
        findOwned(runId, userId).ifPresent(job -> {
            if (!job.terminal()) {
                markCancelled(job);
            }
        });
    }

    @Transactional
    public int deleteForChat(Long chatId, UUID userId) {
        if (chatId == null || userId == null) {
            return 0;
        }
        List<AgentRunJob> jobs = jobRepository.findByChatIdAndUserId(chatId, userId);
        if (jobs.isEmpty()) {
            return 0;
        }
        List<String> runIds = jobs.stream().map(AgentRunJob::getRunId).toList();
        runIds.forEach(cancellationRegistry::cancel);
        outboxRepository.deleteByAggregateIdIn(runIds);
        jobRepository.deleteAllInBatch(jobs);
        return jobs.size();
    }

    private String queuePayload(AgentRunJob job) {
        return mapper.createObjectNode()
                .put("jobId", job.getId())
                .put("runId", job.getRunId())
                .toString();
    }

    private void createOutbox(AgentRunJob job) {
        ExecutionOutboxEvent outbox = new ExecutionOutboxEvent();
        outbox.setStreamKey(properties.getAgentJobStream());
        outbox.setAggregateId(job.getRunId());
        outbox.setPayload(queuePayload(job));
        outboxRepository.save(outbox);
    }

    private String lifecycleEvent(String type, String runId) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode event = root.putObject("avento_event");
        event.put("type", type);
        event.put("title", "Execução cancelada");
        event.put("detail", runId);
        event.put("runId", runId);
        event.put("timestamp", LocalDateTime.now().toString());
        return root.toString();
    }

    private AgentRunView toView(AgentRunJob job) {
        return new AgentRunView(
                job.getRunId(),
                job.getChatId(),
                job.getStatus().name(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getStartedAt());
    }
}
