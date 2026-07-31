package com.avento.service.execution;

import com.avento.config.RedisExecutionProperties;
import com.avento.model.AgentRunJob;
import com.avento.model.AgentTimelineEvent;
import com.avento.model.ScheduledTaskRun;
import com.avento.repository.AgentRunJobRepository;
import com.avento.repository.ScheduledTaskRepository;
import com.avento.repository.ScheduledTaskRunRepository;
import com.avento.service.AgentTimelineService;
import com.avento.service.WorkspaceAccessService;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final com.avento.service.tools.RunToolPolicyRegistry toolPolicyRegistry;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final ScheduledTaskRunRepository runRepository;
    private final AgentTimelineService timelineService;
    private final com.avento.service.execution.CronTaskScheduler cronTaskScheduler;
    private final WorkspaceAccessService workspaceAccessService;
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
            RedisExecutionProperties properties,
            com.avento.service.tools.RunToolPolicyRegistry toolPolicyRegistry,
            ScheduledTaskRepository scheduledTaskRepository,
            ScheduledTaskRunRepository runRepository,
            AgentTimelineService timelineService,
            com.avento.service.execution.CronTaskScheduler cronTaskScheduler,
            WorkspaceAccessService workspaceAccessService) {
        this.jobRepository = jobRepository;
        this.submissionService = submissionService;
        this.cancellationRegistry = cancellationRegistry;
        this.contextCache = contextCache;
        this.orchestrator = orchestrator;
        this.eventPublisher = eventPublisher;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.mapper = mapper;
        this.properties = properties;
        this.toolPolicyRegistry = toolPolicyRegistry;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.runRepository = runRepository;
        this.timelineService = timelineService;
        this.cronTaskScheduler = cronTaskScheduler;
        this.workspaceAccessService = workspaceAccessService;
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
            if (request.hasNonNull("prompt") && !request.path("prompt").asText().isBlank()) {
                ObjectNode userMsg = mapper.createObjectNode();
                userMsg.put("role", "user");
                userMsg.put("content", request.path("prompt").asText());
                requestMessages.add(userMsg);
            }
            ArrayNode messages = contextCache.resolve(job.getUserId(), job.getChatId(), requestMessages);
            List<String> requestedRoots = stringList(request.path("workspaceRoots"));
            if (requestedRoots.isEmpty()
                    && request.hasNonNull("projectPath")
                    && !request.path("projectPath").asText().isBlank()) {
                requestedRoots = List.of(request.path("projectPath").asText());
            }
            // Autoriza as pastas no sandbox ANTES de executar. Sem esta etapa o agente recebe os
            // caminhos no prompt mas o WorkspaceAccessService não tem nada registrado para este
            // usuário, e toda ferramenta de arquivo/terminal falha com SecurityException — foi
            // exatamente o erro de permissão das tarefas do Cowork. O fluxo de chat já fazia isso
            // em LocalAiOrchestratorController.registerWorkspaceRoots; o worker não.
            List<String> workspaceRoots = registerWorkspaceRoots(job.getUserId(), requestedRoots);
            // Sem pasta válida, só a TAREFA AGENDADA falha: ela existe para agir sobre um projeto,
            // então rodar sem pasta gastaria uma execução para nada e esconderia a má configuração.
            // Uma conversa comum sem projeto conectado é caso normal e segue sem raiz nenhuma — as
            // ferramentas de arquivo nem chegam a ser expostas, e o sandbox continua negando tudo.
            boolean isScheduledTask = request.path("taskId").asLong(0L) > 0L;
            if (workspaceRoots.isEmpty() && isScheduledTask) {
                throw new IllegalStateException(
                        requestedRoots.isEmpty()
                                ? "Esta tarefa não tem pasta de projeto definida. Abra o Cowork, edite a tarefa"
                                        + " e selecione a pasta em que ela deve rodar."
                                : "Nenhuma das pastas configuradas para esta tarefa existe mais: " + requestedRoots
                                        + ". Atualize a pasta da tarefa no Cowork.");
            }
            ImageGenerationOptions imageOptions = ImageGenerationOptions.from(request.path("imageOptions"));
            // Restricts this run's toolset to the agent's allow-list, if the plan sent one.
            List<String> allowedTools = stringList(request.path("allowedTools"));
            if (!allowedTools.isEmpty()) {
                toolPolicyRegistry.allow(job.getRunId(), Set.copyOf(allowedTools));
            }
            // Execução sem ninguém olhando: não há para quem pedir aprovação. Sem esta marca a run
            // pararia no primeiro pedido de permissão e morreria no timeout de inatividade — de
            // madrugada, silenciosamente. O que limita o estrago aqui é o sandbox do
            // WorkspaceAccessService, não a aprovação: é ele que prende a tarefa à pasta dela.
            toolPolicyRegistry.markAutonomous(job.getRunId());

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
                toolPolicyRegistry.clear(job.getRunId());
            }

            AgentRunJob current = jobRepository.findById(jobId).orElse(job);
            if (current.getStatus() == AgentRunJob.Status.CANCEL_REQUESTED) {
                submissionService.markCancelled(current);
            } else if (error.get() != null) {
                submissionService.markFailed(current, error.get());
                // Publica SEMPRE, não só em timeout. Uma falha que só existe na coluna last_error é
                // invisível: a interface não mostra nada e o sintoma vira "mandei e não aconteceu
                // nada", com o motivo real (pasta ausente, modelo inexistente) escondido no banco.
                publishFailure(current, error.get());
                sendToDeadLetter(current, error.get());
            } else if (orchestrator
                    .registry()
                    .find(job.getRunId())
                    .map(snapshot -> snapshot.status() == AgentRunRegistry.AgentRunStatus.AWAITING_APPROVAL)
                    .orElse(false)) {
                submissionService.markWaitingApproval(current);
            } else {
                submissionService.markCompleted(current);
                try {
                    StringBuilder sb = new StringBuilder();
                    List<AgentTimelineEvent> events = timelineService.eventsForRun(job.getRunId());
                    if (events != null && !events.isEmpty()) {
                        for (AgentTimelineEvent e : events) {
                            if (e.getDetail() != null && !e.getDetail().isBlank()) {
                                sb.append("[").append(e.getEventType()).append("] ");
                                if (e.getToolName() != null)
                                    sb.append(e.getToolName()).append(": ");
                                sb.append(e.getDetail()).append("\n");
                            } else if (e.getPayload() != null && !e.getPayload().isBlank()) {
                                sb.append("[")
                                        .append(e.getEventType())
                                        .append("] ")
                                        .append(e.getPayload())
                                        .append("\n");
                            }
                        }
                    }

                    ArrayNode finalMessages =
                            contextCache.resolve(job.getUserId(), job.getChatId(), mapper.createArrayNode());
                    if (finalMessages != null && !finalMessages.isEmpty()) {
                        for (int i = finalMessages.size() - 1; i >= 0; i--) {
                            JsonNode msg = finalMessages.get(i);
                            if ("assistant".equalsIgnoreCase(msg.path("role").asText())
                                    && msg.hasNonNull("content")
                                    && !msg.path("content").asText().isBlank()) {
                                if (sb.length() > 0) sb.append("\n--- Resposta / Análise do Agente ---\n");
                                sb.append(msg.path("content").asText());
                                break;
                            }
                        }
                    }

                    String outputToSave = sb.length() > 0
                            ? sb.toString()
                            : "Execução autônoma concluída com sucesso pelo motor de IA do Avento.";
                    Long targetTaskId = request.path("taskId").asLong(0L);

                    if (targetTaskId > 0) {
                        scheduledTaskRepository.findById(targetTaskId).ifPresent(t -> {
                            t.setLastRunOutput(outputToSave);
                            t.setLastRunDiagnosis("Execução autônoma concluída com sucesso às "
                                    + LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                            scheduledTaskRepository.save(t);

                            // Workflows Encadeados (Disparo de Tarefa Dependente)
                            if (t.getOnSuccessTaskId() != null && t.getOnSuccessTaskId() > 0) {
                                scheduledTaskRepository
                                        .findById(t.getOnSuccessTaskId())
                                        .ifPresent(nextTask -> {
                                            logger.info(
                                                    "Disparando tarefa encadeada dependente ID {} ({}) pós-sucesso da tarefa {}",
                                                    nextTask.getId(),
                                                    nextTask.getName(),
                                                    t.getId());
                                            try {
                                                cronTaskScheduler.executeScheduledTask(nextTask);
                                            } catch (Exception ex) {
                                                logger.warn(
                                                        "Erro ao disparar tarefa encadeada {}: {}",
                                                        nextTask.getId(),
                                                        ex.getMessage());
                                            }
                                        });
                            }
                        });

                        List<ScheduledTaskRun> runs = runRepository.findTop50ByTaskIdOrderByCreatedAtDesc(targetTaskId);
                        if (!runs.isEmpty()) {
                            ScheduledTaskRun latestRun = runs.get(0);
                            latestRun.setOutput(outputToSave);
                            runRepository.save(latestRun);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Erro ao atualizar lastRunOutput da tarefa agendada: {}", e.getMessage());
                }
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
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode event = root.putObject("avento_event");
        event.put("type", "agent.run.failed");
        event.put("title", "Execução falhou");
        event.put("detail", detail);
        event.put("runId", job.getRunId());
        eventPublisher.publish(job.getRunId(), job.getUserId(), job.getChatId(), root.toString());

        // O evento acima não é renderizado por ninguém no front (nenhum consumidor de
        // onActivityEvent). O que a conversa mostra é chunk de conteúdo, então o motivo da falha vai
        // também como texto — senão o usuário vê a resposta parar sem explicação e o motivo real
        // fica só na coluna last_error do banco.
        eventPublisher.publish(
                job.getRunId(),
                job.getUserId(),
                job.getChatId(),
                contentChunk("\n> ❌ **Execução falhou:** " + detail + "\n"));
    }

    private String contentChunk(String content) {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("choices").addObject().putObject("delta").put("content", content);
        return root.toString();
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

    /**
     * Registra as pastas da tarefa no sandbox e devolve as que realmente existem. Espelha
     * {@code LocalAiOrchestratorController.registerWorkspaceRoots} — uma pasta apagada desde que a
     * tarefa foi criada é ignorada em vez de derrubar a execução inteira.
     */
    private List<String> registerWorkspaceRoots(UUID userId, List<String> requestedRoots) {
        List<String> registered = new ArrayList<>();
        for (String root : requestedRoots) {
            try {
                workspaceAccessService.registerWorkspaceRoot(userId, root);
                registered.add(root);
            } catch (IllegalArgumentException staleRoot) {
                logger.warn("Pasta configurada na tarefa não pôde ser autorizada: {}", root);
            }
        }
        return List.copyOf(registered);
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
