package com.avento.service.execution;

import com.avento.model.ScheduledTask;
import com.avento.repository.ScheduledTaskRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduledTaskService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledTaskService.class);

    private final ScheduledTaskRepository repository;

    public ScheduledTaskService(ScheduledTaskRepository repository) {
        this.repository = repository;
    }

    public List<ScheduledTask> listUserTasks(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Optional<ScheduledTask> getTask(Long id, UUID userId) {
        return repository.findByIdAndUserId(id, userId);
    }

    @Transactional
    public ScheduledTask createTask(
            String name,
            String description,
            String cronExpression,
            String prompt,
            Long chatId,
            String projectPath,
            Long onSuccessTaskId,
            UUID userId) {
        ScheduledTask task = new ScheduledTask();
        task.setName(name);
        task.setDescription(description);
        task.setCronExpression(cronExpression);
        task.setPrompt(prompt);
        task.setChatId(chatId);
        task.setProjectPath(projectPath);
        task.setOnSuccessTaskId(onSuccessTaskId);
        task.setUserId(userId);
        task.setStatus(ScheduledTask.TaskStatus.ACTIVE);
        task.setLastRunStatus(ScheduledTask.RunStatus.IDLE);

        LocalDateTime nextRun = calculateNextRun(cronExpression);
        task.setNextRunAt(nextRun);

        logger.info("Criando tarefa repetitiva '{}' com cron '{}'. Próxima execução: {}", name, cronExpression, nextRun);
        return repository.save(task);
    }

    @Transactional
    public ScheduledTask createTask(
            String name,
            String description,
            String cronExpression,
            String prompt,
            Long chatId,
            String projectPath,
            UUID userId) {
        return createTask(name, description, cronExpression, prompt, chatId, projectPath, null, userId);
    }

    @Transactional
    public ScheduledTask updateTask(
            Long id,
            String name,
            String description,
            String cronExpression,
            String prompt,
            String projectPath,
            Long onSuccessTaskId,
            UUID userId) {
        ScheduledTask task = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Tarefa agendada não encontrada"));

        task.setName(name);
        task.setDescription(description);
        task.setCronExpression(cronExpression);
        task.setPrompt(prompt);
        task.setProjectPath(projectPath);
        task.setOnSuccessTaskId(onSuccessTaskId);

        LocalDateTime nextRun = calculateNextRun(cronExpression);
        task.setNextRunAt(nextRun);

        return repository.save(task);
    }

    @Transactional
    public ScheduledTask updateTask(
            Long id,
            String name,
            String description,
            String cronExpression,
            String prompt,
            String projectPath,
            UUID userId) {
        return updateTask(id, name, description, cronExpression, prompt, projectPath, null, userId);
    }

    @Transactional
    public ScheduledTask toggleTaskStatus(Long id, UUID userId) {
        ScheduledTask task = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Tarefa agendada não encontrada"));

        if (task.getStatus() == ScheduledTask.TaskStatus.ACTIVE) {
            task.setStatus(ScheduledTask.TaskStatus.PAUSED);
        } else {
            task.setStatus(ScheduledTask.TaskStatus.ACTIVE);
            task.setNextRunAt(calculateNextRun(task.getCronExpression()));
        }

        return repository.save(task);
    }

    @Transactional
    public void deleteTask(Long id, UUID userId) {
        ScheduledTask task = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Tarefa agendada não encontrada"));
        repository.delete(task);
    }

    @Transactional
    public void markRunStarted(ScheduledTask task) {
        task.setLastRunStatus(ScheduledTask.RunStatus.RUNNING);
        task.setLastRunAt(LocalDateTime.now());
        repository.save(task);
    }

    @Transactional
    public void markRunCompleted(ScheduledTask task, boolean success, String error, String diagnosis, String output) {
        task.setLastRunStatus(success ? ScheduledTask.RunStatus.SUCCESS : ScheduledTask.RunStatus.FAILED);
        task.setLastRunError(error);
        task.setLastRunDiagnosis(diagnosis);
        task.setLastRunOutput(output);
        task.setNextRunAt(calculateNextRun(task.getCronExpression()));
        repository.save(task);
    }

    @Transactional
    public void markRunCompleted(ScheduledTask task, boolean success, String error, String diagnosis) {
        markRunCompleted(task, success, error, diagnosis, null);
    }

    public LocalDateTime calculateNextRun(String cronExpressionStr) {
        try {
            // Suporta o formato Cron do Spring (6 campos) ou padrão (5 campos)
            String normalizedCron = normalizeCronExpression(cronExpressionStr);
            CronExpression cron = CronExpression.parse(normalizedCron);
            LocalDateTime next = cron.next(LocalDateTime.now());
            return next != null ? next : LocalDateTime.now().plusHours(24);
        } catch (Exception e) {
            logger.warn("Expressão Cron inválida ou não suportada: '{}'. Usando fallback de 24h.", cronExpressionStr, e);
            return LocalDateTime.now().plusHours(24);
        }
    }

    private String normalizeCronExpression(String cron) {
        if (cron == null || cron.isBlank()) return "0 0 * * * *";
        String trimmed = cron.trim();
        // Se o usuário digitou asteriscos sem espaço (ex: "*****" ou "****")
        if (trimmed.matches("^\\*+$")) {
            trimmed = "* * * * *";
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 5) {
            // Adiciona os segundos 0 no início para o Spring CronExpression (6 campos)
            return "0 " + trimmed;
        }
        return trimmed;
    }
}
