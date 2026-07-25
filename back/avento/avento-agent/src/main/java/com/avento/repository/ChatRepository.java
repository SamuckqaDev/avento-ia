package com.avento.repository;

import com.avento.model.Chat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepository extends JpaRepository<Chat, Long> {
    List<Chat> findAllByOrderByUpdatedAtDesc();

    List<Chat> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    List<Chat> findByUserIdIsNullOrderByUpdatedAtDesc();

    Optional<Chat> findByIdAndUserId(Long id, UUID userId);
}
