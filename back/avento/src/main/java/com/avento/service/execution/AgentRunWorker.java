package com.avento.service.execution;

import com.avento.config.RedisExecutionProperties;
import com.avento.model.AgentRunJob;
import com.avento.repository.AgentRunJobRepository;
import com.avento.service.context.ConversationContextCache;
import com.avento.service.dto.AgentRunSnapshot;
import com.avento.service.image.ImageGenerationOptions;
import com.avento.service.orchestration.AgentOrchestrator;
import com.avento.service.orchestration.AgentRunRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

@Service
public class AgentRunWorker {

    private static final Logger logger = LoggerFactory.getLogger(AgentRunWorker.class);

    private final AgentRunJobRepository jobRepository;
    private final AgentRunSubmissionService submissionService;
    private final AgentRunCancellationRegistry cancellationRegistry;
    private final ConversationContextCache contextCache;
    private final AgentOrchestrator orchestrator;
    private final RunEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;
    private final RedisExecutionProperties properties;
    private final String consumerName = "agent-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean queueFailureLogged = new AtomicBoolean();

    public AgentRunWorker(
            AgentRunJobRepository jobRepository,
            AgentRunSubmissionService submissionService,
            AgentRunCancellationRegistry cancellationRegistry,
            ConversationContextCache contextCache,
            AgentOrchestrator orchestrator,
            RunEventPublisher eventPublisher,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper mapper,
            RedisExecutionProperties properties) {
        this.jobRepository = jobRepository;
        this.submissionService = submissionService;
        this.cancellationRegistry = cancellationRegistry;
        this.contextCache = contextCache;
        this.orchestrator = orchestrator;
        this.eventPublisher = eventPublisher;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.mapper = mapper;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeConsumerGroup() {
        if (!enabled()) {
            return;
        }
        String bootstrapId = null;
        try {
            bootstrapId = redisTemplate
                    .opsForStream()
                    .add(StreamRecords.newRecord()
                            .in(properties.getAgentJobStream())
                            .ofMap(Map.of("bootstrap", "true")))
                    .getValue();
            redisTemplate
                    .opsForStream()
                    .createGroup(
                            properties.getAgentJobStream(), ReadOffset.from("0-0"), properties.getAgentConsumerGroup());
        } catch (RuntimeException exception) {
            if (consumerGroupAlreadyExists(exception)) {
                logger.info("Redis agent consumer group {} is ready", properties.getAgentConsumerGroup());
            } else {
                logger.warn("Redis agent queue is unavailable during startup", exception);
            }
        } finally {
            if (bootstrapId != null) {
                try {
                    redisTemplate.opsForStream().delete(properties.getAgentJobStream(), bootstrapId);
                } catch (RuntimeException exception) {
                    logger.debug("Could not remove Redis consumer-group bootstrap record", exception);
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${avento.execution.redis.worker-delay-ms:200}")
    public void poll() {
        if (!enabled()) {
            return;
        }
        try {
            StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
            List<MapRecord<String, String, String>> records = streamOperations.read(
                    Consumer.from(properties.getAgentConsumerGroup(), consumerName),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create(properties.getAgentJobStream(), ReadOffset.lastConsumed()));
            if (records == null) {
                queueFailureLogged.set(false);
                return;
            }
            queueFailureLogged.set(false);
            for (MapRecord<String, String, String> record : records) {
                execute(record);
            }
        } catch (RuntimeException exception) {
            if (queueFailureLogged.compareAndSet(false, true)) {
                logger.warn("Could not poll Redis agent queue; the worker will retry automatically", exception);
            }
        }
    }

    private void execute(MapRecord<String, String, String> record) {
        Long jobId = parseJobId(record.getValue().get("jobId"));
        if (jobId == null) {
            acknowledge(record);
            return;
        }
        AgentRunJob candidate = jobRepository.findById(jobId).orElse(null);
        if (candidate == null || candidate.terminal()) {
            acknowledge(record);
            return;
        }
        if (candidate.getStatus() == AgentRunJob.Status.CANCEL_REQUESTED) {
            submissionService.markCancelled(candidate);
            acknowledge(record);
            return;
        }
        AgentRunJob job = submissionService.claimForExecution(jobId).orElse(null);
        if (job == null) {
            acknowledge(record);
            return;
        }

        try {
            JsonNode request = mapper.readTree(job.getRequestPayload());
            ArrayNode requestMessages = request.path("messages").isArray()
                    ? (ArrayNode) request.path("messages")
                    : mapper.createArrayNode();
            ArrayNode messages = contextCache.resolve(job.getUserId(), job.getChatId(), requestMessages);
            List<String> workspaceRoots = stringList(request.path("workspaceRoots"));
            ImageGenerationOptions imageOptions = ImageGenerationOptions.from(request.path("imageOptions"));

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Disposable execution = orchestrator
                    .streamWithRunId(
                            job.getRunId(),
                            request.path("model").asText(""),
                            messages,
                            workspaceRoots,
                            request.path("imageModel").asText(""),
                            imageOptions,
                            job.getChatId(),
                            job.getUserId())
                    .timeout(properties.getRunTimeout())
                    .doOnError(error::set)
                    .doFinally(signal -> completed.countDown())
                    .subscribe(ignored -> {}, ignored -> {});
            cancellationRegistry.register(job.getRunId(), execution);
            boolean finished;
            try {
                finished = awaitCompletion(job.getRunId(), completed);
                if (!finished) {
                    TimeoutException timeout = new TimeoutException(
                            "Agent run " + job.getRunId() + " exceeded its execution or inactivity timeout");
                    error.compareAndSet(null, timeout);
                    execution.dispose();
                }
            } finally {
                cancellationRegistry.unregister(job.getRunId(), execution);
            }

            AgentRunJob current = jobRepository.findById(jobId).orElse(job);
            if (current.getStatus() == AgentRunJob.Status.CANCEL_REQUESTED) {
                submissionService.markCancelled(current);
            } else if (error.get() != null) {
                submissionService.markFailed(current, error.get());
                if (causedByTimeout(error.get())) {
                    publishFailure(current, error.get());
                }
                sendToDeadLetter(current, error.get());
            } else if (orchestrator
                    .registry()
                    .find(job.getRunId())
                    .map(snapshot -> snapshot.status() == AgentRunRegistry.AgentRunStatus.AWAITING_APPROVAL)
                    .orElse(false)) {
                submissionService.markWaitingApproval(current);
            } else {
                submissionService.markCompleted(current);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            submissionService.markFailed(job, exception);
            publishFailure(job, exception);
        } catch (Exception exception) {
            submissionService.markFailed(job, exception);
            publishFailure(job, exception);
            sendToDeadLetter(job, exception);
        } finally {
            acknowledge(record);
        }
    }

    private boolean awaitCompletion(String runId, CountDownLatch completed) throws InterruptedException {
        Duration runTimeout = positive(properties.getRunTimeout(), Duration.ofMinutes(15));
        Duration inactivityTimeout = positive(properties.getRunInactivityTimeout(), Duration.ofMinutes(3));
        long deadlineNanos = System.nanoTime() + runTimeout.toNanos();

        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            long waitNanos = Math.min(remainingNanos, TimeUnit.SECONDS.toNanos(1));
            if (completed.await(waitNanos, TimeUnit.NANOSECONDS)) {
                return true;
            }
            LocalDateTime inactiveBefore = LocalDateTime.now().minus(inactivityTimeout);
            boolean inactive = orchestrator
                    .registry()
                    .find(runId)
                    .map(snapshot -> isInactive(snapshot, inactiveBefore))
                    .orElse(false);
            if (inactive) {
                return false;
            }
        }
    }

    private Duration positive(Duration configured, Duration fallback) {
        return configured == null || configured.isZero() || configured.isNegative() ? fallback : configured;
    }

    static boolean isInactive(AgentRunSnapshot snapshot, LocalDateTime inactiveBefore) {
        return snapshot != null
                && snapshot.updatedAt() != null
                && inactiveBefore != null
                && snapshot.updatedAt().isBefore(inactiveBefore);
    }

    private void sendToDeadLetter(AgentRunJob job, Throwable error) {
        try {
            redisTemplate
                    .opsForStream()
                    .add(StreamRecords.newRecord()
                            .in(properties.getDeadLetterStream())
                            .ofMap(Map.of(
                                    "jobId", job.getId().toString(),
                                    "runId", job.getRunId(),
                                    "error",
                                            error.getMessage() == null
                                                    ? error.getClass().getSimpleName()
                                                    : error.getMessage())));
        } catch (RuntimeException exception) {
            logger.debug("Could not publish run {} to dead-letter stream", job.getRunId(), exception);
        }
    }

    private void publishFailure(AgentRunJob job, Throwable error) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode event = root.putObject("avento_event");
        event.put("type", "agent.run.failed");
        event.put("title", "Execução falhou");
        event.put("detail", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        event.put("runId", job.getRunId());
        eventPublisher.publish(job.getRunId(), job.getUserId(), job.getChatId(), root.toString());
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        redisTemplate
                .opsForStream()
                .acknowledge(properties.getAgentJobStream(), properties.getAgentConsumerGroup(), record.getId());
    }

    private Long parseJobId(Object value) {
        try {
            return value == null ? null : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    values.add(value.asText());
                }
            });
        }
        return List.copyOf(values);
    }

    private boolean enabled() {
        return properties.isEnabled() && redisTemplate != null;
    }

    static boolean consumerGroupAlreadyExists(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static boolean causedByTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
