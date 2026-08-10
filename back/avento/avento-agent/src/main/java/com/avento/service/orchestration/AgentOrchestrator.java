package com.avento.service.orchestration;

import com.avento.service.AgentTimelineService;
import com.avento.service.execution.RunEventPublisher;
import com.avento.service.image.ImageGenerationOptions;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Entry point that owns run identity/lifecycle while AgentService handles model turns. */
@Service
public class AgentOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final AgentExecutionEngine agentService;
    private final AgentRunRegistry runRegistry;
    private final ObjectMapper mapper;
    private final AgentTimelineService timelineService;
    private final RunEventPublisher eventPublisher;
    private final OrphanReplyRescue orphanReplyRescue;

    public AgentOrchestrator(AgentExecutionEngine agentService, AgentRunRegistry runRegistry, ObjectMapper mapper) {
        this(
                agentService,
                runRegistry,
                mapper,
                new AgentTimelineService(Optional.empty()),
                (runId, userId, chatId, raw) -> {},
                // Sem repositorio a rede de seguranca vira no-op: comportamento identico ao de
                // antes dela existir, que e o que os testes que montam isto a mao esperam.
                new OrphanReplyRescue(
                        (com.avento.repository.MessageRepository) null,
                        (com.avento.repository.ChatRepository) null,
                        mapper,
                        java.time.Duration.ofSeconds(20)));
    }

    @Autowired
    public AgentOrchestrator(
            AgentExecutionEngine agentService,
            AgentRunRegistry runRegistry,
            ObjectMapper mapper,
            AgentTimelineService timelineService,
            RunEventPublisher eventPublisher,
            OrphanReplyRescue orphanReplyRescue) {
        this.agentService = agentService;
        this.runRegistry = runRegistry;
        this.mapper = mapper;
        this.timelineService = timelineService;
        this.eventPublisher = eventPublisher;
        this.orphanReplyRescue = orphanReplyRescue;
    }

    public Flux<String> stream(
            String model,
            ArrayNode messages,
            List<String> workspaceRoots,
            String imageModel,
            ImageGenerationOptions imageOptions,
            Long chatId,
            UUID userId) {
        String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);
        return streamWithRunId(runId, model, messages, workspaceRoots, imageModel, imageOptions, chatId, userId);
    }

    public Flux<String> streamWithRunId(
            String runId,
            String model,
            ArrayNode messages,
            List<String> workspaceRoots,
            String imageModel,
            ImageGenerationOptions imageOptions,
            Long chatId,
            UUID userId) {
        logger.info("Agent run {} starting for chat {} with model {}", runId, chatId, model);
        runRegistry.start(runId, latestUserMessage(messages), workspaceRoots, userId);
        orphanReplyRescue.onRunStarted(runId);
        timelineService.registerRun(runId, userId, chatId);
        String startedEvent = runStartedEvent(runId);
        eventPublisher.publish(runId, userId, chatId, startedEvent);
        Flux<String> execution = agentService
                .streamChat(model, messages, workspaceRoots, imageModel, imageOptions, runId, chatId, userId)
                .doOnNext(chunk -> {
                    runRegistry.observe(runId, chunk);
                    orphanReplyRescue.observe(runId, chunk);
                    eventPublisher.publish(runId, userId, chatId, chunk);
                })
                .doOnComplete(() -> {
                    logger.info("Agent run {} completed", runId);
                    runRegistry.finish(runId);
                    // A resposta pode ter ficado sem dono se o cliente caiu no meio; ver a classe.
                    orphanReplyRescue.onRunFinished(runId, chatId);
                    // O evento terminal precisa sair SEMPRE que o fluxo acaba. Antes so saia com
                    // status exatamente COMPLETED, e finish() preserva FAILED/CANCELLED — entao uma
                    // run que terminou por falha repetida de ferramenta completava sem erro, sem
                    // status COMPLETED e sem publicar nada. Como o SSE so fecha em
                    // agent.run.completed|failed|cancelled (ver RunEventStreamService), o navegador
                    // ficava girando para sempre enquanto o servidor ja tinha terminado. O log dizia
                    // "completed" porque a linha acima e incondicional, o que escondia o problema.
                    runRegistry.find(runId).ifPresent(snapshot -> {
                        switch (snapshot.status()) {
                            case FAILED ->
                                eventPublisher.publish(
                                        runId,
                                        userId,
                                        chatId,
                                        lifecycleEvent(
                                                "agent.run.failed", "Execução encerrada após falha", runId, runId));
                            case CANCELLED ->
                                eventPublisher.publish(
                                        runId,
                                        userId,
                                        chatId,
                                        lifecycleEvent("agent.run.cancelled", "Execução cancelada", runId, runId));
                            // AWAITING_APPROVAL nao publica: tool.approval.required ja fechou o
                            // stream, e o cliente reabre depois de responder.
                            case AWAITING_APPROVAL -> {}
                            default -> eventPublisher.publish(runId, userId, chatId, runCompletedEvent(runId));
                        }
                    });
                })
                .doOnError(error -> {
                    logger.warn("Agent run {} failed: {}", runId, error.getMessage());
                    runRegistry.fail(runId);
                    eventPublisher.publish(runId, userId, chatId, runFailedEvent(runId, error));
                });
        return Flux.concat(Flux.just(startedEvent), execution);
    }

    public Flux<String> approve(String approvalId, String comment) {
        runRegistry.resumeApproval(approvalId);
        String runId = runRegistry.findRunIdByApproval(approvalId).orElse("");
        return monitorExistingRun(runId, agentService.approveTool(approvalId, comment));
    }

    public Flux<String> reject(String approvalId, String comment) {
        String runId = runRegistry.findRunIdByApproval(approvalId).orElse("");
        return monitorExistingRun(runId, agentService.rejectTool(approvalId, comment));
    }

    public AgentRunRegistry registry() {
        return runRegistry;
    }

    public Optional<String> runIdForApproval(String approvalId) {
        return runRegistry.findRunIdByApproval(approvalId);
    }

    private Flux<String> monitorExistingRun(String runId, Flux<String> stream) {
        if (runId.isBlank()) {
            return stream;
        }
        return stream.doOnNext(chunk -> {
                    runRegistry.observe(runId, chunk);
                    eventPublisher.publish(runId, null, null, chunk);
                })
                .doOnComplete(() -> {
                    runRegistry.finish(runId);
                    runRegistry.find(runId).ifPresent(snapshot -> {
                        if (snapshot.status() == AgentRunRegistry.AgentRunStatus.COMPLETED) {
                            eventPublisher.publish(runId, null, null, runCompletedEvent(runId));
                        } else if (snapshot.status() == AgentRunRegistry.AgentRunStatus.CANCELLED) {
                            eventPublisher.publish(runId, null, null, runCancelledEvent(runId));
                        }
                    });
                })
                .doOnError(error -> {
                    runRegistry.fail(runId);
                    eventPublisher.publish(runId, null, null, runFailedEvent(runId, error));
                });
    }

    private String latestUserMessage(ArrayNode messages) {
        if (messages == null) {
            return "";
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if ("user".equals(message.path("role").asText())) {
                return message.path("content").asText("");
            }
        }
        return "";
    }

    private String runStartedEvent(String runId) {
        return lifecycleEvent("agent.run.started", "Execução iniciada", runId, runId);
    }

    private String runCompletedEvent(String runId) {
        return lifecycleEvent("agent.run.completed", "Execução concluída", runId, runId);
    }

    private String runFailedEvent(String runId, Throwable error) {
        String detail = error == null || error.getMessage() == null ? runId : error.getMessage();
        return lifecycleEvent("agent.run.failed", "Execução falhou", detail, runId);
    }

    private String runCancelledEvent(String runId) {
        return lifecycleEvent("agent.run.cancelled", "Execução cancelada", runId, runId);
    }

    private String lifecycleEvent(String type, String title, String detail, String runId) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode event = root.putObject("avento_event");
        event.put("type", type);
        event.put("title", title);
        event.put("detail", detail);
        event.put("runId", runId);
        event.put("timestamp", LocalDateTime.now().toString());
        return root.toString();
    }
}
