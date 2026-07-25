package com.avento.service.execution;

import com.avento.model.ScheduledTask;
import com.avento.repository.ScheduledTaskRepository;
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
    private final ObjectMapper objectMapper;

    public CronTaskScheduler(
            ScheduledTaskRepository repository,
            ScheduledTaskService taskService,
            AgentRunSubmissionService submissionService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.taskService = taskService;
        this.submissionService = submissionService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 30000)
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
            payload.put("prompt", "MODO AUTÔNOMO AGENDADO (Avento Cowork):\n"
                    + "Tarefa: " + task.getName() + "\n"
                    + "Instrução: " + task.getPrompt() + "\n"
                    + "Em caso de falha em qualquer comando, analise o erro, gere um relatório de causa raiz e proponha a solução.");
            payload.put("agentMode", true);
            if (task.getProjectPath() != null && !task.getProjectPath().isBlank()) {
                payload.put("projectPath", task.getProjectPath());
            }

            Long targetChatId = task.getChatId() != null ? task.getChatId() : 0L;
            submissionService.submit(task.getUserId(), targetChatId, payload);

            // Sucesso na submissão do job
            taskService.markRunCompleted(task, true, null, "Execução iniciada no motor de agentes com sucesso.");
        } catch (Exception e) {
            logger.error("Erro na execução da tarefa '{}'", task.getName(), e);
            taskService.markRunCompleted(task, false, e.getMessage(), "Causa Raiz: " + e.getMessage() + "\nSugestão: Verifique as permissões da sandbox e a conectividade com o modelo de IA.");
            throw e;
        }
    }
}
