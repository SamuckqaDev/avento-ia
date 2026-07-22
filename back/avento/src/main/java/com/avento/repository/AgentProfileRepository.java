package com.avento.repository;

import com.avento.model.AgentProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentProfileRepository extends JpaRepository<AgentProfile, Long> {

    List<AgentProfile> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<AgentProfile> findByIdAndUserId(Long id, UUID userId);

    Optional<AgentProfile> findFirstByUserIdAndIsDefaultTrue(UUID userId);

    boolean existsByUserId(UUID userId);
}
