package com.avento.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.ScheduledTask;
import com.avento.model.ScheduledTaskExecutionHistoryRow;
import com.avento.model.ScheduledTaskRepository;
import com.avento.model.ScheduledTaskRun;
import com.avento.model.ScheduledTaskRunRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskHistoryServiceTest {

    @Mock
    private ScheduledTaskRepository taskRepository;

    @Mock
    private ScheduledTaskRunRepository runRepository;

    @Test
    void listsOnlyTheAuthenticatedUsersMostRecentExecutionsWithALimitedQuery() {
        UUID userId = UUID.randomUUID();
        ScheduledTaskExecutionHistoryRow historyRow = new ScheduledTaskExecutionHistoryRow(
                8L,
                5L,
                "Abrir LinkedIn",
                ScheduledTask.RunStatus.SUCCESS,
                "Abra o LinkedIn",
                "[tool.completed] open_browser_tab",
                null,
                LocalDateTime.of(2026, 8, 11, 16, 50));
        when(runRepository.findExecutionHistoryByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(List.of(historyRow));
        ScheduledTaskHistoryService service = new ScheduledTaskHistoryService(taskRepository, runRepository);

        List<ScheduledTaskExecutionHistoryRow> result = service.listRecentExecutions(userId, 500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findExecutionHistoryByUserId(eq(userId), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(result).containsExactly(historyRow);
    }

    @Test
    void mapsTaskRunsWithTheTaskNameOnlyAfterCheckingOwnership() {
        UUID userId = UUID.randomUUID();
        ScheduledTask task = new ScheduledTask();
        task.setName("Abrir LinkedIn");
        ScheduledTaskRun run = new ScheduledTaskRun(
                5L, ScheduledTask.RunStatus.FAILED, "Abra o LinkedIn", null, "Google Chrome não respondeu");
        run.setId(9L);
        when(taskRepository.findByIdAndUserId(5L, userId)).thenReturn(Optional.of(task));
        when(runRepository.findTop50ByTaskIdOrderByCreatedAtDesc(5L)).thenReturn(List.of(run));
        ScheduledTaskHistoryService service = new ScheduledTaskHistoryService(taskRepository, runRepository);

        List<ScheduledTaskExecutionHistoryRow> result = service.listTaskExecutions(5L, userId);

        assertThat(result).singleElement().satisfies(history -> {
            assertThat(history.taskId()).isEqualTo(5L);
            assertThat(history.taskName()).isEqualTo("Abrir LinkedIn");
            assertThat(history.error()).isEqualTo("Google Chrome não respondeu");
        });
        verify(taskRepository).findByIdAndUserId(5L, userId);
    }
}
