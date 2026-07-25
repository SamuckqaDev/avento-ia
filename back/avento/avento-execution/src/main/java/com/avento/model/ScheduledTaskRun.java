package com.avento.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "scheduled_task_runs",
        indexes = {
            @Index(name = "idx_task_run_task_id", columnList = "task_id"),
            @Index(name = "idx_task_run_created", columnList = "created_at")
        })
public class ScheduledTaskRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ScheduledTask.RunStatus status;

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "output", columnDefinition = "TEXT")
    private String output;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ScheduledTaskRun() {}

    public ScheduledTaskRun(Long taskId, ScheduledTask.RunStatus status, String prompt, String output, String error) {
        this.taskId = taskId;
        this.status = status;
        this.prompt = prompt;
        this.output = output;
        this.error = error;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public ScheduledTask.RunStatus getStatus() {
        return status;
    }

    public void setStatus(ScheduledTask.RunStatus status) {
        this.status = status;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
