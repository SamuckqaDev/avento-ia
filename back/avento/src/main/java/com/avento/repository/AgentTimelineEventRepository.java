package com.avento.repository;

import com.avento.model.AgentTimelineEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTimelineEventRepository extends JpaRepository<AgentTimelineEvent, Long> {
    List<AgentTimelineEvent> findTop100ByOrderByCreatedAtDesc();

    List<AgentTimelineEvent> findByRunIdOrderByCreatedAtAsc(String runId);

    List<AgentTimelineEvent> findTop100ByUserIdOrderByCreatedAtDesc(UUID userId);

    List<AgentTimelineEvent> findByUserIdAndRunIdOrderByCreatedAtAsc(UUID userId, String runId);

    Optional<AgentTimelineEvent> findFirstByUserIdAndRunIdAndApprovalIdOrderByCreatedAtDesc(
            UUID userId, String runId, String approvalId);
}
