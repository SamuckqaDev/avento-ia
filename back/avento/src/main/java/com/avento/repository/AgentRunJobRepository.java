package com.avento.repository;

import com.avento.model.AgentRunJob;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRunJobRepository extends JpaRepository<AgentRunJob, Long> {

    Optional<AgentRunJob> findByRunId(String runId);

    Optional<AgentRunJob> findByRunIdAndUserId(String runId, UUID userId);

    List<AgentRunJob> findByChatIdAndUserId(Long chatId, UUID userId);

    List<AgentRunJob> findByStatusIn(List<AgentRunJob.Status> statuses);

    Optional<AgentRunJob> findFirstByChatIdAndUserIdAndStatusInOrderByCreatedAtDesc(
            Long chatId, UUID userId, Collection<AgentRunJob.Status> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentRunJob job
               set job.status = :runningStatus,
                   job.attempts = job.attempts + 1,
                   job.startedAt = :startedAt,
                   job.updatedAt = :startedAt
             where job.id = :jobId
               and job.status = :queuedStatus
            """)
    int claimForExecution(
            @Param("jobId") Long jobId,
            @Param("queuedStatus") AgentRunJob.Status queuedStatus,
            @Param("runningStatus") AgentRunJob.Status runningStatus,
            @Param("startedAt") LocalDateTime startedAt);
}
