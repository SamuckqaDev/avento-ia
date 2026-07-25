package com.avento.controller;

import com.avento.auth.security.AuthPrincipal;
import com.avento.model.ScheduledTask;
import com.avento.service.execution.CronTaskScheduler;
import com.avento.service.execution.ScheduledTaskService;
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
import org.springframework.web.bind.annotation.RestController;

import com.avento.model.ScheduledTaskRun;
import com.avento.repository.ScheduledTaskRunRepository;

@RestController
@RequestMapping("/api/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskService taskService;
    private final CronTaskScheduler cronTaskScheduler;
    private final ScheduledTaskRunRepository runRepository;

    public ScheduledTaskController(
            ScheduledTaskService taskService,
            CronTaskScheduler cronTaskScheduler,
            ScheduledTaskRunRepository runRepository) {
        this.taskService = taskService;
        this.cronTaskScheduler = cronTaskScheduler;
        this.runRepository = runRepository;
    }

    public record CreateTaskRequest(
            String name,
            String description,
            String cronExpression,
            String prompt,
            Long chatId,
            String projectPath) {}

    public record UpdateTaskRequest(
            String name,
            String description,
            String cronExpression,
            String prompt,
            String projectPath) {}

    @GetMapping
    public ResponseEntity<List<ScheduledTask>> listTasks(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        List<ScheduledTask> tasks = taskService.listUserTasks(principal.userId());
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScheduledTask> getTask(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return taskService.getTask(id, principal.userId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ScheduledTask> createTask(
            @RequestBody CreateTaskRequest req, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        ScheduledTask created = taskService.createTask(
                req.name(),
                req.description(),
                req.cronExpression(),
                req.prompt(),
                req.chatId(),
                req.projectPath(),
                principal.userId());
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScheduledTask> updateTask(
            @PathVariable("id") Long id,
            @RequestBody UpdateTaskRequest req,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        ScheduledTask updated = taskService.updateTask(
                id, req.name(), req.description(), req.cronExpression(), req.prompt(), req.projectPath(), principal.userId());
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<ScheduledTask> toggleTask(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        ScheduledTask toggled = taskService.toggleTaskStatus(id, principal.userId());
        return ResponseEntity.ok(toggled);
    }

    @PostMapping("/{id}/run-now")
    public ResponseEntity<ScheduledTask> runNow(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        ScheduledTask task = taskService.getTask(id, principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("Tarefa não encontrada"));
        cronTaskScheduler.executeScheduledTask(task);
        return ResponseEntity.ok(task);
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<ScheduledTaskRun>> getTaskRuns(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        taskService.getTask(id, principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("Tarefa não encontrada"));
        return ResponseEntity.ok(runRepository.findTop50ByTaskIdOrderByCreatedAtDesc(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable("id") Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        taskService.deleteTask(id, principal.userId());
        return ResponseEntity.noContent().build();
    }
}
