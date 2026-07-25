package com.avento.repository;

import com.avento.model.ScheduledTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

    List<ScheduledTask> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<ScheduledTask> findByIdAndUserId(Long id, UUID userId);

    List<ScheduledTask> findByStatusAndNextRunAtBefore(
            ScheduledTask.TaskStatus status, LocalDateTime now);
}
