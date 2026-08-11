package com.avento.model;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRun, Long> {
    List<ScheduledTaskRun> findTop50ByTaskIdOrderByCreatedAtDesc(Long taskId);

    @Query("""
            select new com.avento.model.ScheduledTaskExecutionHistoryRow(
                taskRun.id,
                taskRun.taskId,
                task.name,
                taskRun.status,
                taskRun.prompt,
                taskRun.output,
                taskRun.error,
                taskRun.createdAt)
            from ScheduledTaskRun taskRun
            join ScheduledTask task on task.id = taskRun.taskId
            where task.userId = :userId
            order by taskRun.createdAt desc
            """)
    List<ScheduledTaskExecutionHistoryRow> findExecutionHistoryByUserId(
            @Param("userId") UUID userId, Pageable pageable);
}
