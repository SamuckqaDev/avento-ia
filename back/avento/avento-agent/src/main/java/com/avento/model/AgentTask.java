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
        name = "agent_tasks",
        indexes = {
            @Index(name = "idx_agent_tasks_plan_order", columnList = "plan_id,order_index"),
            @Index(name = "idx_agent_tasks_user_plan", columnList = "user_id,plan_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class AgentTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_BLOCKED = "BLOCKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "order_index")
    private int orderIndex;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "target_files", columnDefinition = "TEXT")
    private String targetFiles;

    @Column(nullable = false)
    private String status;

    @Column(name = "needs_approval")
    private boolean needsApproval;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    // Agente (persona) que executa esta tarefa. Definido pelo roteamento na criação do plano, e
    // pode ser trocado manualmente pelo usuário. Nulo → o executor cai no agente default.
    @Column(name = "assigned_agent_id")
    private Long assignedAgentId;

    @Column(name = "agent_rationale", columnDefinition = "TEXT")
    private String agentRationale;

    private int attempts = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AgentTask(
            Long planId,
            UUID userId,
            int orderIndex,
            String title,
            String details,
            String targetFiles,
            String status,
            boolean needsApproval) {
        this.planId = planId;
        this.userId = userId;
        this.orderIndex = orderIndex;
        this.title = title;
        this.details = details;
        this.targetFiles = targetFiles;
        this.status = status;
        this.needsApproval = needsApproval;
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
