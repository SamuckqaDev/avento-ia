package com.avento.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "avento_token_audit_events")
public class TokenAuditEvent {

    @Id
    @GeneratedValue
    private UUID id;

    private UUID userId;

    private UUID sessionId;

    @Column(length = 80)
    private String accessJti;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TokenAuditEventType type;

    @Column(length = 80)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
