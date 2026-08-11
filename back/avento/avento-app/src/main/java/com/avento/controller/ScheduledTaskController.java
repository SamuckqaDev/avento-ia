package com.avento.controller;

import com.avento.model.ScheduledTask;
import com.avento.model.ScheduledTaskExecutionHistoryRow;
import com.avento.service.auth.AuthPrincipal;
import com.avento.service.execution.CronTaskScheduler;
import com.avento.service.execution.ScheduledTaskHistoryService;
import com.avento.service.execution.ScheduledTaskService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskService taskService;
    private final CronTaskScheduler cronTaskScheduler;
    private final ScheduledTaskHistoryService historyService;

    public ScheduledTaskController(
            ScheduledTaskService taskService,
            CronTaskScheduler cronTaskScheduler,
            ScheduledTaskHistoryService historyService) {
        this.taskService = taskService;
        this.cronTaskScheduler = cronTaskScheduler;
        this.historyService = historyService;
    }

    public record CreateTaskRequest(
            String name,
            String description,
            String cronExpression,
            String prompt,
            Long chatId,
            String projectPath,
            Long onSuccessTaskId,
            boolean runOnce) {}

    public record UpdateTaskRequest(
            String name,
            String description,
            String cronExpression,
            String prompt,
            String projectPath,
            Long onSuccessTaskId,
            boolean runOnce) {}

    public record ScheduledTaskResponse(
            Long id,
            String name,
            String description,
            String cronExpression,
            String prompt,
            Long chatId,
            String projectPath,
            Long onSuccessTaskId,
            ScheduledTask.TaskStatus status,
            boolean runOnce,
            ScheduledTask.RunStatus lastRunStatus,
            LocalDateTime lastRunAt,
            LocalDateTime nextRunAt,
            String lastRunError,
            String lastRunDiagnosis,
            String lastRunOutput,
            LocalDateTime createdAt) {}

    public record ScheduledTaskRunResponse(
            Long id,
            Long taskId,
            String taskName,
            ScheduledTask.RunStatus status,
            String prompt,
            String output,
            String error,
            LocalDateTime createdAt) {}

    @GetMapping
    public ResponseEntity<List<ScheduledTaskResponse>> listTasks(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(taskService.listUserTasks(principal.userId()).stream()
                .map(ScheduledTaskController::toTaskResponse)
                .toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScheduledTaskResponse> getTask(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return taskService
                .getTask(id, principal.userId())
                .map(ScheduledTaskController::toTaskResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ScheduledTaskResponse> createTask(
            @RequestBody CreateTaskRequest req, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        ScheduledTask created = taskService.createTask(
                req.name(),
                req.description(),
                req.cronExpression(),
                req.prompt(),
                req.chatId(),
                req.projectPath(),
                req.onSuccessTaskId(),
                req.runOnce(),
                principal.userId());
        return ResponseEntity.ok(toTaskResponse(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScheduledTaskResponse> updateTask(
            @PathVariable("id") Long id,
            @RequestBody UpdateTaskRequest req,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        ScheduledTask updated = taskService.updateTask(
                id,
                req.name(),
                req.description(),
                req.cronExpression(),
                req.prompt(),
                req.projectPath(),
                req.onSuccessTaskId(),
                req.runOnce(),
                principal.userId());
        return ResponseEntity.ok(toTaskResponse(updated));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<ScheduledTaskResponse> toggleTask(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        ScheduledTask toggled = taskService.toggleTaskStatus(id, principal.userId());
        return ResponseEntity.ok(toTaskResponse(toggled));
    }

    @PostMapping("/{id}/run-now")
    public ResponseEntity<ScheduledTaskResponse> runNow(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        ScheduledTask task = taskService
                .getTask(id, principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("Tarefa não encontrada"));
        cronTaskScheduler.executeScheduledTask(task);
        return ResponseEntity.ok(toTaskResponse(task));
    }

    @GetMapping("/history")
    public ResponseEntity<List<ScheduledTaskRunResponse>> getExecutionHistory(
            @RequestParam(name = "limit", required = false) Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(historyService.listRecentExecutions(principal.userId(), limit).stream()
                .map(ScheduledTaskController::toRunResponse)
                .toList());
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<ScheduledTaskRunResponse>> getTaskRuns(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(historyService.listTaskExecutions(id, principal.userId()).stream()
                .map(ScheduledTaskController::toRunResponse)
                .toList());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        taskService.deleteTask(id, principal.userId());
        return ResponseEntity.noContent().build();
    }

    private static ScheduledTaskResponse toTaskResponse(ScheduledTask task) {
        return new ScheduledTaskResponse(
                task.getId(),
                task.getName(),
                task.getDescription(),
                task.getCronExpression(),
                task.getPrompt(),
                task.getChatId(),
                task.getProjectPath(),
                task.getOnSuccessTaskId(),
                task.getStatus(),
                task.isRunOnce(),
                task.getLastRunStatus(),
                task.getLastRunAt(),
                task.getNextRunAt(),
                task.getLastRunError(),
                task.getLastRunDiagnosis(),
                task.getLastRunOutput(),
                task.getCreatedAt());
    }

    private static ScheduledTaskRunResponse toRunResponse(ScheduledTaskExecutionHistoryRow run) {
        return new ScheduledTaskRunResponse(
                run.id(),
                run.taskId(),
                run.taskName(),
                run.status(),
                run.prompt(),
                run.output(),
                run.error(),
                run.createdAt());
    }
}
