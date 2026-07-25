package com.avento.repository;

import com.avento.model.AgentPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPlanRepository extends JpaRepository<AgentPlan, Long> {

    Optional<AgentPlan> findByIdAndUserId(Long id, UUID userId);

    List<AgentPlan> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<AgentPlan> findFirstByUserIdAndStatusInOrderByUpdatedAtDesc(UUID userId, List<String> statuses);

    List<AgentPlan> findByStatusIn(List<String> statuses);

    List<AgentPlan> findByChatIdAndUserId(Long chatId, UUID userId);
}
