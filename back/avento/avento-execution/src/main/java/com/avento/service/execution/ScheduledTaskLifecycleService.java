package com.avento.service.execution;

import com.avento.model.ScheduledTask;
import com.avento.model.ScheduledTaskRepository;
import com.avento.model.ScheduledTaskRun;
import com.avento.model.ScheduledTaskRunRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mantém a agenda ativa enxuta sem descartar o histórico das tarefas pontuais. */
@Service
public class ScheduledTaskLifecycleService {

    private static final String EXPIRED_OUTPUT =
            "Tarefa pontual arquivada sem execução porque o horário agendado expirou.";

    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunRepository runRepository;

    public ScheduledTaskLifecycleService(
            ScheduledTaskRepository taskRepository, ScheduledTaskRunRepository runRepository) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
    }

    @Transactional
    public void archiveAfterTerminalResult(long taskId) {
        taskRepository.findById(taskId).ifPresent(this::archiveIfOneShot);
    }

    @Transactional
    public int archiveExpiredOneShotTasks(LocalDateTime expirationThreshold) {
        List<ScheduledTask> expiredTasks = taskRepository.findByStatusAndRunOnceTrueAndNextRunAtBefore(
                ScheduledTask.TaskStatus.ACTIVE, expirationThreshold);
        for (ScheduledTask task : expiredTasks) {
            task.setLastRunStatus(ScheduledTask.RunStatus.FAILED);
            task.setLastRunError("O horário da tarefa pontual expirou antes de ela ser iniciada.");
            task.setLastRunDiagnosis(EXPIRED_OUTPUT);
            task.setLastRunOutput(EXPIRED_OUTPUT);
            archive(task);
            runRepository.save(new ScheduledTaskRun(
                    task.getId(),
                    ScheduledTask.RunStatus.FAILED,
                    task.getPrompt(),
                    EXPIRED_OUTPUT,
                    task.getLastRunError()));
        }
        return expiredTasks.size();
    }

    private void archiveIfOneShot(ScheduledTask task) {
        if (task.isRunOnce()) {
            archive(task);
        }
    }

    private void archive(ScheduledTask task) {
        task.setStatus(ScheduledTask.TaskStatus.COMPLETED);
        task.setNextRunAt(null);
        taskRepository.save(task);
    }
}
