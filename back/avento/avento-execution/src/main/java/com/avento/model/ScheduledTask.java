package com.avento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "scheduled_tasks",
        indexes = {
            @Index(name = "idx_scheduled_task_status", columnList = "status"),
            @Index(name = "idx_scheduled_task_user", columnList = "user_id"),
            @Index(name = "idx_scheduled_task_next_run", columnList = "next_run_at")
        })
public class ScheduledTask {

    public enum TaskStatus {
        ACTIVE,
        PAUSED
    }

    public enum RunStatus {
        IDLE,
        RUNNING,
        SUCCESS,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "project_path", length = 1000)
    private String projectPath;

    @Column(name = "on_success_task_id")
    private Long onSuccessTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TaskStatus status = TaskStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", nullable = false)
    private RunStatus lastRunStatus = RunStatus.IDLE;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_error", columnDefinition = "TEXT")
    private String lastRunError;

    @Column(name = "last_run_diagnosis", columnDefinition = "TEXT")
    private String lastRunDiagnosis;

    @Column(name = "last_run_output", columnDefinition = "TEXT")
    private String lastRunOutput;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public Long getOnSuccessTaskId() {
        return onSuccessTaskId;
    }

    public void setOnSuccessTaskId(Long onSuccessTaskId) {
        this.onSuccessTaskId = onSuccessTaskId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public RunStatus getLastRunStatus() {
        return lastRunStatus;
    }

    public void setLastRunStatus(RunStatus lastRunStatus) {
        this.lastRunStatus = lastRunStatus;
    }

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(LocalDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public LocalDateTime getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(LocalDateTime nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public String getLastRunError() {
        return lastRunError;
    }

    public void setLastRunError(String lastRunError) {
        this.lastRunError = lastRunError;
    }

    public String getLastRunDiagnosis() {
        return lastRunDiagnosis;
    }

    public void setLastRunDiagnosis(String lastRunDiagnosis) {
        this.lastRunDiagnosis = lastRunDiagnosis;
    }

    public String getLastRunOutput() {
        return lastRunOutput;
    }

    public void setLastRunOutput(String lastRunOutput) {
        this.lastRunOutput = lastRunOutput;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
