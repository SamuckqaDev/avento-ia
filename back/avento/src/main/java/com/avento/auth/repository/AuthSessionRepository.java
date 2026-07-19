package com.avento.auth.repository;

import com.avento.auth.model.AuthSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    Optional<AuthSession> findByIdAndRevokedFalse(UUID id);
}
