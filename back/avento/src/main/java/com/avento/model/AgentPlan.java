package com.avento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "agent_plans",
        indexes = {
            @Index(name = "idx_agent_plans_user_status", columnList = "user_id,status"),
            @Index(name = "idx_agent_plans_user_updated", columnList = "user_id,updated_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class AgentPlan {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String goal;

    @Column(nullable = false)
    private String status;

    @Column(name = "workspace_roots", columnDefinition = "TEXT")
    private String workspaceRoots;

    @Column(name = "current_task_id")
    private Long currentTaskId;

    @Column(name = "current_run_id", length = 80)
    private String currentRunId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AgentPlan(UUID userId, Long chatId, String goal, String status, String workspaceRoots) {
        this.userId = userId;
        this.chatId = chatId;
        this.goal = goal;
        this.status = status;
        this.workspaceRoots = workspaceRoots;
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
