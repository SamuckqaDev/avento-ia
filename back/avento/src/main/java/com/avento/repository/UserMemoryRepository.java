package com.avento.repository;

import com.avento.model.UserMemory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMemoryRepository extends JpaRepository<UserMemory, Long> {

    List<UserMemory> findByUserIdAndStatusOrderByUpdatedAtDesc(UUID userId, String status);

    List<UserMemory> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<UserMemory> findByIdAndUserId(Long id, UUID userId);

    boolean existsByUserIdAndStatusAndContentIgnoreCase(UUID userId, String status, String content);

    long countByUserIdAndStatus(UUID userId, String status);
}
