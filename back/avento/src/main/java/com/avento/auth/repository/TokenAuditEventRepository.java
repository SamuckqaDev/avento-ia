package com.avento.auth.repository;

import com.avento.auth.model.TokenAuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenAuditEventRepository extends JpaRepository<TokenAuditEvent, UUID> {

    List<TokenAuditEvent> findTop100ByUserIdOrderByCreatedAtDesc(UUID userId);
}
