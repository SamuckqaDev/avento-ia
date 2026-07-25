package com.avento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_permission_rules")
public class AgentPermissionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "project_path", columnDefinition = "TEXT")
    private String projectPath;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "resource_key", nullable = false, columnDefinition = "TEXT")
    private String resourceKey;

    @Column(nullable = false)
    private String decision;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public AgentPermissionRule() {}

    public AgentPermissionRule(
            UUID userId,
            String projectPath,
            String toolName,
            String resourceKey,
            String decision,
            LocalDateTime expiresAt) {
        this.userId = userId;
        this.projectPath = projectPath;
        this.toolName = toolName;
        this.resourceKey = resourceKey;
        this.decision = decision;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public String getToolName() {
        return toolName;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public String getDecision() {
        return decision;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
