package com.avento.service.execution;

import com.avento.model.ScheduledTask;
import com.avento.model.ScheduledTaskRepository;
import com.avento.model.ScheduledTaskRun;
import com.avento.model.ScheduledTaskRunRepository;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persiste o resultado terminal de uma execucao do Cowork para consulta na agenda. */
@Service
public class ScheduledTaskExecutionReporter {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunRepository runRepository;

    public ScheduledTaskExecutionReporter(
            ScheduledTaskRepository taskRepository, ScheduledTaskRunRepository runRepository) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
    }

    @Transactional
    public void recordSuccess(long taskId, String runId, String activityLog) {
        updateTask(taskId, ScheduledTask.RunStatus.SUCCESS, null, successDiagnosis(runId), activityLog);
        updateLatestRun(taskId, ScheduledTask.RunStatus.SUCCESS, activityLog, null);
    }

    @Transactional
    public void recordFailure(long taskId, String runId, String stage, String reason, String activityLog) {
        String report = failureReport(runId, stage, reason, activityLog);
        updateTask(taskId, ScheduledTask.RunStatus.FAILED, reason, report, report);
        updateLatestRun(taskId, ScheduledTask.RunStatus.FAILED, report, reason);
    }

    private void updateTask(
            long taskId,
            ScheduledTask.RunStatus status,
            String error,
            String diagnosis,
            String output) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setLastRunStatus(status);
            task.setLastRunError(error);
            task.setLastRunDiagnosis(diagnosis);
            task.setLastRunOutput(output);
            taskRepository.save(task);
        });
    }

    private void updateLatestRun(long taskId, ScheduledTask.RunStatus status, String output, String error) {
        List<ScheduledTaskRun> runs = runRepository.findTop50ByTaskIdOrderByCreatedAtDesc(taskId);
        if (runs.isEmpty()) {
            return;
        }
        ScheduledTaskRun latestRun = runs.get(0);
        latestRun.setStatus(status);
        latestRun.setOutput(output);
        latestRun.setError(error);
        runRepository.save(latestRun);
    }

    private String successDiagnosis(String runId) {
        return "Execução concluída às " + LocalTime.now().format(TIME_FORMATTER) + " (run " + runId + ").";
    }

    private String failureReport(String runId, String stage, String reason, String activityLog) {
        StringBuilder report = new StringBuilder("EXECUÇÃO AUTÔNOMA — FALHOU\n")
                .append("Run: ").append(runId).append('\n')
                .append("Onde falhou: ").append(stage).append('\n')
                .append("Motivo: ").append(reason).append('\n');
        if (activityLog != null && !activityLog.isBlank()) {
            report.append("\nTrilha da execução:\n").append(activityLog);
        }
        return report.toString();
    }
}
