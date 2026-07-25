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
@Table(name = "agent_timeline_events")
public class AgentTimelineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "approval_id")
    private String approvalId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "tool_name")
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public AgentTimelineEvent() {}

    public AgentTimelineEvent(String runId, String eventType, String toolName, String detail, String payload) {
        this(runId, null, null, eventType, toolName, detail, payload);
    }

    public AgentTimelineEvent(
            String runId, UUID userId, Long chatId, String eventType, String toolName, String detail, String payload) {
        this.runId = runId;
        this.userId = userId;
        this.chatId = chatId;
        this.eventType = eventType;
        this.toolName = toolName;
        this.detail = detail;
        this.payload = payload;
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

    public void setId(Long id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
