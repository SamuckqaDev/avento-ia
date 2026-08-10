package com.avento.model;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenAuditEventRepository extends JpaRepository<TokenAuditEvent, UUID> {

    List<TokenAuditEvent> findTop100ByUserIdOrderByCreatedAtDesc(UUID userId);
}
