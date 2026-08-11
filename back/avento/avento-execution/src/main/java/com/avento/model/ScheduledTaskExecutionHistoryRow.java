package com.avento.model;

import java.time.LocalDateTime;

/**
 * Read model for a scheduled-task execution together with its task name.
 *
 * <p>The row is intentionally projection-only: execution records remain owned by
 * {@link ScheduledTaskRun}, while this type prevents the history screen from loading one task per
 * run.
 */
public record ScheduledTaskExecutionHistoryRow(
        Long id,
        Long taskId,
        String taskName,
        ScheduledTask.RunStatus status,
        String prompt,
        String output,
        String error,
        LocalDateTime createdAt) {}
