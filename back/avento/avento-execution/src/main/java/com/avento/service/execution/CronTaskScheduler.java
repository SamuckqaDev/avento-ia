package com.avento.service.execution;

import com.avento.model.ScheduledTask;
import com.avento.repository.ScheduledTaskRepository;
import com.avento.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CronTaskScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CronTaskScheduler.class);

    private final ScheduledTaskRepository repository;
    private final ScheduledTaskService taskService;
    private final AgentRunSubmissionService submissionService;
    private final NotificationService notificationService;
    private final com.avento.repository.ScheduledTaskRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public CronTaskScheduler(
            ScheduledTaskRepository repository,
            ScheduledTaskService taskService,
            AgentRunSubmissionService submissionService,
            NotificationService notificationService,
            com.avento.repository.ScheduledTaskRunRepository runRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.taskService = taskService;
        this.submissionService = submissionService;
        this.notificationService = notificationService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 10000)
    public void processDueScheduledTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledTask> dueTasks = repository.findByStatusAndNextRunAtBefore(
                ScheduledTask.TaskStatus.ACTIVE, now);

        if (dueTasks.isEmpty()) {
            return;
        }

        logger.info("Encontradas {} tarefa(s) agendada(s) prontas para execução.", dueTasks.size());

        for (ScheduledTask task : dueTasks) {
            try {
                executeScheduledTask(task);
            } catch (Exception e) {
                logger.error("Erro ao disparar tarefa agendada id={} name='{}'", task.getId(), task.getName(), e);
                taskService.markRunCompleted(task, false, e.getMessage(), "Falha ao inicializar tarefa: " + e.getMessage());
            }
        }
    }

    public void executeScheduledTask(ScheduledTask task) {
        logger.info("Iniciando execução autônoma da tarefa agendada '{}' (id={})", task.getName(), task.getId());
        taskService.markRunStarted(task);

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("taskId", task.getId());
            payload.put("prompt", "MODO AUTÔNOMO AGENDADO (Avento Cowork):\n"
                    + "Tarefa: " + task.getName() + "\n"
                    + "Instrução: " + task.getPrompt() + "\n"
                    + "Instruções de execução: Execute os comandos de terminal/bash necessários (ex: `run_command` com `git`, `curl`, etc.) no diretório do projeto e mostre o resultado textual bruto retornado.");
            payload.put("agentMode", true);
            if (task.getProjectPath() != null && !task.getProjectPath().isBlank()) {
                payload.put("projectPath", task.getProjectPath());
            }

            Long targetChatId = task.getChatId() != null ? task.getChatId() : 0L;
            submissionService.submit(task.getUserId(), targetChatId, payload);

            // Sucesso na submissão do job
            String logMessage = "Execução autônoma iniciada às " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + ".\nComando/Instrução: " + task.getPrompt();
            taskService.markRunCompleted(task, true, null, "Execução iniciada no motor de agentes com sucesso.", logMessage);
            runRepository.save(new com.avento.model.ScheduledTaskRun(
                    task.getId(),
                    com.avento.model.ScheduledTask.RunStatus.SUCCESS,
                    task.getPrompt(),
                    logMessage,
                    null
            ));
            notificationService.record(
                    "COWORK_TASK_EXECUTED",
                    "Automação Concluída: " + task.getName(),
                    "A tarefa agendada '" + task.getName() + "' foi executada com sucesso pelo Avento."
            );
        } catch (Exception e) {
            logger.error("Erro na execução da tarefa '{}'", task.getName(), e);
            String errorDiag = "Causa Raiz: " + e.getMessage() + "\nSugestão: Verifique as permissões da sandbox e a conectividade com o modelo de IA.";
            taskService.markRunCompleted(task, false, e.getMessage(), errorDiag);
            runRepository.save(new com.avento.model.ScheduledTaskRun(
                    task.getId(),
                    com.avento.model.ScheduledTask.RunStatus.FAILED,
                    task.getPrompt(),
                    null,
                    e.getMessage()
            ));
            notificationService.record(
                    "COWORK_TASK_FAILED",
                    "Falha na Automação: " + task.getName(),
                    "Ocorreu um erro ao executar '" + task.getName() + "': " + e.getMessage()
            );
            throw e;
        }
    }
}
