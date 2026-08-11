package com.avento.service.execution;

import com.avento.model.ScheduledTask;
import com.avento.model.ScheduledTaskExecutionHistoryRow;
import com.avento.model.ScheduledTaskRepository;
import com.avento.model.ScheduledTaskRun;
import com.avento.model.ScheduledTaskRunRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provides user-scoped, durable Cowork execution history. */
@Service
public class ScheduledTaskHistoryService {

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 100;

    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunRepository runRepository;

    public ScheduledTaskHistoryService(
            ScheduledTaskRepository taskRepository, ScheduledTaskRunRepository runRepository) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
    }

    @Transactional(readOnly = true)
    public List<ScheduledTaskExecutionHistoryRow> listRecentExecutions(UUID userId, Integer requestedLimit) {
        return runRepository.findExecutionHistoryByUserId(userId, PageRequest.of(0, normalizeLimit(requestedLimit)));
    }

    @Transactional(readOnly = true)
    public List<ScheduledTaskExecutionHistoryRow> listTaskExecutions(Long taskId, UUID userId) {
        ScheduledTask task = taskRepository
                .findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Tarefa agendada não encontrada"));

        return runRepository.findTop50ByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(run -> toHistoryRow(run, task.getName()))
                .toList();
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit < 1) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(requestedLimit, MAX_HISTORY_LIMIT);
    }

    private ScheduledTaskExecutionHistoryRow toHistoryRow(ScheduledTaskRun run, String taskName) {
        return new ScheduledTaskExecutionHistoryRow(
                run.getId(),
                run.getTaskId(),
                taskName,
                run.getStatus(),
                run.getPrompt(),
                run.getOutput(),
                run.getError(),
                run.getCreatedAt());
    }
}
