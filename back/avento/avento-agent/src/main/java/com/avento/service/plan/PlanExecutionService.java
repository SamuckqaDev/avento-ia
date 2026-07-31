package com.avento.service.plan;

import com.avento.model.AgentPlan;
import com.avento.model.AgentRunJob;
import com.avento.model.AgentTask;
import com.avento.repository.AgentPlanRepository;
import com.avento.repository.AgentRunJobRepository;
import com.avento.repository.AgentTaskRepository;
import com.avento.service.FileBackupService;
import com.avento.service.ProjectVerificationService;
import com.avento.service.WorkspaceAccessService;
import com.avento.service.dto.VerificationResult;
import com.avento.service.execution.AgentRunSubmissionService;
import com.avento.service.execution.RunEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import com.avento.model.AgentProfile;
import java.util.Arrays;

@Slf4j
@Service
public class PlanExecutionService {

    private static final int MAX_ATTEMPTS = 2;
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(20);
    private static final long POLL_MILLIS = 250L;
    private static final int MAX_PROGRESS_LINES = 10;

    private final AgentPlanRepository planRepository;
    private final AgentTaskRepository taskRepository;
    private final AgentRunSubmissionService submissionService;
    private final AgentRunJobRepository jobRepository;
    private final ProjectVerificationService verificationService;
    private final WorkspaceAccessService workspaceAccessService;
    private final FileBackupService backupService;
    private final ObjectMapper objectMapper;
    private final RunEventPublisher eventPublisher;
    private final AgentRoutingService agentRoutingService;

    @Qualifier("planTaskExecutor")
    private final TaskExecutor taskExecutor;

    private final Set<Long> activePlans = ConcurrentHashMap.newKeySet();

    public PlanExecutionService(
            AgentPlanRepository planRepository,
            AgentTaskRepository taskRepository,
            AgentRunSubmissionService submissionService,
            AgentRunJobRepository jobRepository,
            ProjectVerificationService verificationService,
            WorkspaceAccessService workspaceAccessService,
            FileBackupService backupService,
            ObjectMapper objectMapper,
            RunEventPublisher eventPublisher,
            AgentRoutingService agentRoutingService,
            @Qualifier("planTaskExecutor") TaskExecutor taskExecutor) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.submissionService = submissionService;
        this.jobRepository = jobRepository;
        this.verificationService = verificationService;
        this.workspaceAccessService = workspaceAccessService;
        this.backupService = backupService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.agentRoutingService = agentRoutingService;
        this.taskExecutor = taskExecutor;
    }

    public boolean run(UUID userId, Long planId) {
        AgentPlan plan = findOwnedPlan(userId, planId);
        if (!isRunnable(plan) || !activePlans.add(planId)) {
            return false;
        }
        plan.setStatus(AgentPlan.STATUS_RUNNING);
        planRepository.save(plan);
        publish(plan, null, "plan.running", "Plano em execução");
        try {
            taskExecutor.execute(() -> executePlan(userId, planId));
            return true;
        } catch (RuntimeException exception) {
            activePlans.remove(planId);
            plan.setStatus(AgentPlan.STATUS_PAUSED);
            planRepository.save(plan);
            throw exception;
        }
    }

    public AgentPlan pause(UUID userId, Long planId) {
        AgentPlan plan = findOwnedPlan(userId, planId);
        if (!AgentPlan.STATUS_RUNNING.equals(plan.getStatus())) {
            return plan;
        }
        plan.setStatus(AgentPlan.STATUS_PAUSED);
        planRepository.save(plan);
        cancelCurrentRun(plan);
        publish(plan, currentTask(plan), "plan.paused", "Execução pausada");
        return plan;
    }

    public AgentPlan cancel(UUID userId, Long planId) {
        AgentPlan plan = findOwnedPlan(userId, planId);
        plan.setStatus(AgentPlan.STATUS_CANCELLED);
        planRepository.save(plan);
        cancelCurrentRun(plan);
        AgentTask currentTask = currentTask(plan);
        if (currentTask != null && AgentTask.STATUS_RUNNING.equals(currentTask.getStatus())) {
            currentTask.setStatus(AgentTask.STATUS_SKIPPED);
            currentTask.setResultSummary("Execução cancelada pelo usuário.");
            taskRepository.save(currentTask);
        }
        publish(plan, currentTask, "plan.cancelled", "Plano cancelado");
        return plan;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedPlans() {
        List<AgentPlan> interrupted = planRepository.findByStatusIn(List.of(AgentPlan.STATUS_RUNNING));
        for (AgentPlan plan : interrupted) {
            taskRepository.findByPlanIdAndUserIdOrderByOrderIndex(plan.getId(), plan.getUserId()).stream()
                    .filter(task -> AgentTask.STATUS_RUNNING.equals(task.getStatus()))
                    .forEach(task -> {
                        task.setStatus(AgentTask.STATUS_PENDING);
                        // Reutiliza a chave idempotente persistida pela tentativa interrompida.
                        task.setAttempts(Math.max(0, task.getAttempts() - 1));
                        taskRepository.save(task);
                    });
            plan.setStatus(AgentPlan.STATUS_PAUSED);
            planRepository.save(plan);
            run(plan.getUserId(), plan.getId());
        }
    }

    private void executePlan(UUID userId, Long planId) {
        try {
            List<AgentTask> tasks = taskRepository.findByPlanIdAndUserIdOrderByOrderIndex(planId, userId);
            for (AgentTask listedTask : tasks) {
                AgentPlan plan = findOwnedPlan(userId, planId);
                if (!AgentPlan.STATUS_RUNNING.equals(plan.getStatus())) {
                    return;
                }
                AgentTask task = findOwnedTask(userId, planId, listedTask.getId());
                if (!AgentTask.STATUS_PENDING.equals(task.getStatus())
                        && !AgentTask.STATUS_FAILED.equals(task.getStatus())) {
                    continue;
                }
                if (task.getAttempts() >= MAX_ATTEMPTS) {
                    pauseAfterFailure(plan, task, "A tarefa atingiu o limite de tentativas.");
                    return;
                }
                if (task.isNeedsApproval()) {
                    task.setStatus(AgentTask.STATUS_BLOCKED);
                    task.setResultSummary("Aguardando aprovação do usuário.");
                    taskRepository.save(task);
                    plan.setStatus(AgentPlan.STATUS_PAUSED);
                    plan.setCurrentTaskId(task.getId());
                    planRepository.save(plan);
                    publish(plan, task, "plan.approval.required", "Tarefa aguardando aprovação");
                    return;
                }
                if (!executeTask(userId, plan, task)) {
                    return;
                }
            }

            AgentPlan completed = findOwnedPlan(userId, planId);
            boolean allDone = taskRepository.findByPlanIdAndUserIdOrderByOrderIndex(planId, userId).stream()
                    .allMatch(task -> AgentTask.STATUS_DONE.equals(task.getStatus())
                            || AgentTask.STATUS_SKIPPED.equals(task.getStatus()));
            if (allDone && AgentPlan.STATUS_RUNNING.equals(completed.getStatus())) {
                completed.setStatus(AgentPlan.STATUS_DONE);
                completed.setCurrentTaskId(null);
                completed.setCurrentRunId(null);
                planRepository.save(completed);
                publish(completed, null, "plan.completed", "Plano concluído");
            }
        } catch (RuntimeException exception) {
            log.error("Plan {} execution failed", planId, exception);
            AgentPlan plan = planRepository.findByIdAndUserId(planId, userId).orElse(null);
            if (plan != null && AgentPlan.STATUS_RUNNING.equals(plan.getStatus())) {
                plan.setStatus(AgentPlan.STATUS_FAILED);
                planRepository.save(plan);
                publish(plan, currentTask(plan), "plan.failed", safeMessage(exception));
            }
        } finally {
            activePlans.remove(planId);
        }
    }

    private boolean executeTask(UUID userId, AgentPlan plan, AgentTask task) {
        List<String> roots = restoreAuthorizedRoots(userId, parseRoots(plan));
        if (roots.isEmpty()) {
            pauseAfterFailure(plan, task, "O plano não possui workspace autorizado.");
            return false;
        }

        while (task.getAttempts() < MAX_ATTEMPTS) {
            plan = findOwnedPlan(userId, plan.getId());
            if (!AgentPlan.STATUS_RUNNING.equals(plan.getStatus())) {
                resetRunningTask(task);
                return false;
            }

            int attempt = task.getAttempts() + 1;
            task.setAttempts(attempt);
            task.setStatus(AgentTask.STATUS_RUNNING);
            taskRepository.save(task);
            plan.setCurrentTaskId(task.getId());
            planRepository.save(plan);
            publish(plan, task, "plan.task.running", "Executando tarefa " + task.getOrderIndex());

            String runId = "plan_" + plan.getId() + "_task_" + task.getId() + "_attempt_" + attempt;
            try {
                ObjectNode payload = taskPayload(userId, plan, task, roots, runId);
                AgentRunJob job = submissionService.submit(userId, plan.getChatId(), payload, runId);
                plan.setCurrentRunId(job.getRunId());
                planRepository.save(plan);

                AgentRunJob completedJob = waitForJob(userId, plan.getId(), job.getRunId());
                if (completedJob.getStatus() != AgentRunJob.Status.COMPLETED) {
                    throw new IllegalStateException(
                            "Execução do agente terminou com status " + completedJob.getStatus());
                }

                verifyRoots(userId, roots);
                task.setStatus(AgentTask.STATUS_DONE);
                task.setResultSummary("Tarefa executada e projeto verificado com sucesso.");
                taskRepository.save(task);
                plan.setCurrentRunId(null);
                planRepository.save(plan);
                publish(plan, task, "plan.task.completed", "Tarefa concluída");
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                resetRunningTask(task);
                return false;
            } catch (Exception exception) {
                log.warn("Task {} attempt {} failed", task.getId(), attempt, exception);
                revertLatestRun(userId, plan.getChatId());
                plan = findOwnedPlan(userId, plan.getId());
                if (!AgentPlan.STATUS_RUNNING.equals(plan.getStatus())) {
                    resetRunningTask(task);
                    return false;
                }
                if (task.getAttempts() >= MAX_ATTEMPTS) {
                    pauseAfterFailure(plan, task, safeMessage(exception));
                    return false;
                }
                task.setStatus(AgentTask.STATUS_PENDING);
                task.setResultSummary("Tentativa " + attempt + " falhou; preparando nova tentativa.");
                taskRepository.save(task);
                publish(plan, task, "plan.task.retrying", task.getResultSummary());
            }
        }
        return false;
    }

    private ObjectNode taskPayload(
            UUID userId, AgentPlan plan, AgentTask task, List<String> roots, String idempotencyKey) {
        // Resolve o agente (persona) desta tarefa: manual > roteamento automático > default.
        AgentRoutingService.Routed routed = agentRoutingService.pick(userId, task);
        AgentProfile agent = routed.agent();
        String persona = agent.getSystemInstructions() == null
                        || agent.getSystemInstructions().isBlank()
                ? ""
                : agent.getSystemInstructions().strip() + "\n\n";

        ArrayNode messages = objectMapper.createArrayNode();
        messages.addObject()
                .put("role", "system")
                .put(
                        "content",
                        persona
                                + "Execute somente a tarefa atual. Leia apenas os arquivos necessários, use as ferramentas "
                                + "disponíveis para alterar o workspace e não tente executar o plano inteiro.");
        String progress = compactProgress(userId, plan.getId(), task.getOrderIndex());
        String prompt = "Tarefa: " + task.getTitle() + "\nDetalhes: " + task.getDetails() + "\nArquivos alvo: "
                + task.getTargetFiles() + (progress.isBlank() ? "" : "\nProgresso anterior:\n" + progress);
        messages.addObject().put("role", "user").put("content", prompt);
        log.info(
                "Plan {} task {} agente '{}' prompt estimate: {} chars",
                plan.getId(),
                task.getId(),
                agent.getName(),
                prompt.length());

        ObjectNode payload = objectMapper.createObjectNode();
        // Modelo preferido do agente (vazio = default do sistema).
        payload.put("model", agent.getModel() == null ? "" : agent.getModel());
        payload.put("idempotencyKey", idempotencyKey);
        payload.set("messages", messages);
        ArrayNode rootsNode = payload.putArray("workspaceRoots");
        roots.forEach(rootsNode::add);
        // Allow-list de ferramentas do agente (vazia = sem restrição). O worker registra por runId e
        // o loop do agente restringe o toolset a esse escopo.
        List<String> allowedTools = parseCsv(agent.getAllowedTools());
        if (!allowedTools.isEmpty()) {
            ArrayNode allowed = payload.putArray("allowedTools");
            allowedTools.forEach(allowed::add);
        }
        return payload;
    }

    private AgentRunJob waitForJob(UUID userId, Long planId, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            AgentPlan plan = findOwnedPlan(userId, planId);
            if (!AgentPlan.STATUS_RUNNING.equals(plan.getStatus())) {
                submissionService.requestCancellation(runId, userId);
            }
            AgentRunJob job = jobRepository
                    .findByRunIdAndUserId(runId, userId)
                    .orElseThrow(() -> new IllegalStateException("Execução durável não encontrada: " + runId));
            if (job.terminal()) {
                return job;
            }
            TimeUnit.MILLISECONDS.sleep(POLL_MILLIS);
        }
        submissionService.requestCancellation(runId, userId);
        throw new IllegalStateException("A tarefa excedeu o tempo limite de execução.");
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private void verifyRoots(UUID userId, List<String> roots) {
        for (String root : roots) {
            // userId explícito: a verificação roda na thread de background do plano, onde o contexto
            // de execução não carrega o usuário atual (senão a autorização de workspace falha).
            VerificationResult result = verificationService.verify(userId, root);
            // Só é falha se HAVIA o que verificar e falhou. "Nenhum comando detectado" (projeto vazio
            // ou sem build/testes ainda) não é falha — não há o que checar neste passo.
            if (result != null && result.detected() && !result.ok()) {
                throw new IllegalStateException("Verificação falhou em " + root + ": " + result.errorSummary());
            }
        }
    }

    private List<String> parseRoots(AgentPlan plan) {
        if (plan.getWorkspaceRoots() == null || plan.getWorkspaceRoots().isBlank()) {
            return List.of();
        }
        try {
            List<String> roots = objectMapper.readValue(
                    plan.getWorkspaceRoots(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return roots.stream()
                    .map(Path::of)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .filter(Files::isDirectory)
                    .map(Path::toString)
                    .distinct()
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Workspaces do plano estão inválidos.", exception);
        }
    }

    private List<String> restoreAuthorizedRoots(UUID userId, List<String> roots) {
        return roots.stream()
                .map(root -> workspaceAccessService
                        .registerWorkspaceRoot(userId, root)
                        .toString())
                .distinct()
                .toList();
    }

    private String compactProgress(UUID userId, Long planId, int currentOrder) {
        return taskRepository.findByPlanIdAndUserIdOrderByOrderIndex(planId, userId).stream()
                .filter(task -> task.getOrderIndex() < currentOrder)
                .filter(task -> AgentTask.STATUS_DONE.equals(task.getStatus()))
                .limit(MAX_PROGRESS_LINES)
                .map(task -> "- " + task.getTitle() + ": "
                        + (task.getResultSummary() == null ? "concluída" : task.getResultSummary()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private void pauseAfterFailure(AgentPlan plan, AgentTask task, String detail) {
        task.setStatus(AgentTask.STATUS_FAILED);
        task.setResultSummary("Falha após " + task.getAttempts() + " tentativa(s): " + detail);
        taskRepository.save(task);
        plan.setStatus(AgentPlan.STATUS_PAUSED);
        plan.setCurrentRunId(null);
        planRepository.save(plan);
        publish(plan, task, "plan.task.failed", task.getResultSummary());
    }

    private void resetRunningTask(AgentTask task) {
        if (AgentTask.STATUS_RUNNING.equals(task.getStatus())) {
            task.setStatus(AgentTask.STATUS_PENDING);
            taskRepository.save(task);
        }
    }

    private void revertLatestRun(UUID userId, Long chatId) {
        try {
            backupService.revertMostRecent(userId, chatId);
        } catch (RuntimeException exception) {
            log.error("Could not restore the latest task checkpoint", exception);
        }
    }

    private AgentPlan findOwnedPlan(UUID userId, Long planId) {
        if (userId == null) {
            throw new SecurityException("Authenticated user is required");
        }
        return planRepository
                .findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found or not owned by user"));
    }

    private AgentTask findOwnedTask(UUID userId, Long planId, Long taskId) {
        return taskRepository
                .findByIdAndPlanIdAndUserId(taskId, planId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found or not owned by plan"));
    }

    private AgentTask currentTask(AgentPlan plan) {
        if (plan == null || plan.getCurrentTaskId() == null) {
            return null;
        }
        return taskRepository
                .findByIdAndPlanIdAndUserId(plan.getCurrentTaskId(), plan.getId(), plan.getUserId())
                .orElse(null);
    }

    private void cancelCurrentRun(AgentPlan plan) {
        if (plan.getCurrentRunId() != null && !plan.getCurrentRunId().isBlank()) {
            submissionService.requestCancellation(plan.getCurrentRunId(), plan.getUserId());
        }
    }

    private boolean isRunnable(AgentPlan plan) {
        return AgentPlan.STATUS_DRAFT.equals(plan.getStatus()) || AgentPlan.STATUS_PAUSED.equals(plan.getStatus());
    }

    private void publish(AgentPlan plan, AgentTask task, String type, String detail) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode event = root.putObject("avento_event");
        event.put("type", type);
        event.put("title", detail);
        event.put("detail", detail);
        event.put("planId", plan.getId());
        if (task != null) {
            event.put("taskId", task.getId());
            event.put("taskStatus", task.getStatus());
        }
        event.put("timestamp", LocalDateTime.now().toString());
        eventPublisher.publish(planStreamId(plan.getId()), plan.getUserId(), plan.getChatId(), root.toString());
    }

    public static String planStreamId(Long planId) {
        return "plan_" + planId;
    }

    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "Falha inesperada durante a execução.";
        }
        String message = error.getMessage().strip();
        return message.length() <= 600 ? message : message.substring(0, 600);
    }
}
