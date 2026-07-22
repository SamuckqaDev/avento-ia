package com.avento.repository;

import com.avento.model.AgentTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    List<AgentTask> findByPlanIdAndUserIdOrderByOrderIndex(Long planId, UUID userId);

    Optional<AgentTask> findByIdAndUserId(Long id, UUID userId);

    Optional<AgentTask> findByIdAndPlanIdAndUserId(Long id, Long planId, UUID userId);
}
