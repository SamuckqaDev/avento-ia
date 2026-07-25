package com.avento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "video_generation_jobs")
public class VideoGenerationJob {

    public enum Status {
        QUEUED,
        SUBMITTING,
        GENERATING,
        SAVING,
        COMPLETED,
        FAILED,
        CANCELLED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    @Id
    private UUID id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String prompt;

    @Column(name = "source_image_path", columnDefinition = "TEXT")
    private String sourceImagePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String stage;

    @Column(name = "comfy_prompt_id")
    private String comfyPromptId;

    @Column(name = "comfy_client_id")
    private String comfyClientId;

    private int width;
    private int height;
    private int frames;
    private int fps;
    private int steps;
    private int progress;

    @Column(name = "estimated_total_seconds")
    private long estimatedTotalSeconds;

    @Column(name = "estimated_remaining_seconds")
    private Long estimatedRemainingSeconds;

    @Column(name = "output_path", columnDefinition = "TEXT")
    private String outputPath;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "cancellation_requested", nullable = false)
    private boolean cancellationRequested;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public VideoGenerationJob() {}

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getSourceImagePath() {
        return sourceImagePath;
    }

    public void setSourceImagePath(String sourceImagePath) {
        this.sourceImagePath = sourceImagePath;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getComfyPromptId() {
        return comfyPromptId;
    }

    public void setComfyPromptId(String comfyPromptId) {
        this.comfyPromptId = comfyPromptId;
    }

    public String getComfyClientId() {
        return comfyClientId;
    }

    public void setComfyClientId(String comfyClientId) {
        this.comfyClientId = comfyClientId;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getFrames() {
        return frames;
    }

    public void setFrames(int frames) {
        this.frames = frames;
    }

    public int getFps() {
        return fps;
    }

    public void setFps(int fps) {
        this.fps = fps;
    }

    public int getSteps() {
        return steps;
    }

    public void setSteps(int steps) {
        this.steps = steps;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public long getEstimatedTotalSeconds() {
        return estimatedTotalSeconds;
    }

    public void setEstimatedTotalSeconds(long estimatedTotalSeconds) {
        this.estimatedTotalSeconds = estimatedTotalSeconds;
    }

    public Long getEstimatedRemainingSeconds() {
        return estimatedRemainingSeconds;
    }

    public void setEstimatedRemainingSeconds(Long estimatedRemainingSeconds) {
        this.estimatedRemainingSeconds = estimatedRemainingSeconds;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    public void setCancellationRequested(boolean cancellationRequested) {
        this.cancellationRequested = cancellationRequested;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
