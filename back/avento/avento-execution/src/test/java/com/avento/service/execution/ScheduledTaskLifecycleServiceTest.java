package com.avento.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.ScheduledTask;
import com.avento.model.ScheduledTaskRepository;
import com.avento.model.ScheduledTaskRunRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskLifecycleServiceTest {

    @Mock
    private ScheduledTaskRepository taskRepository;

    @Mock
    private ScheduledTaskRunRepository runRepository;

    @Test
    void archivesOneShotTaskAfterItsTerminalResultAndKeepsItsHistory() {
        ScheduledTask task = oneShotTask();
        when(taskRepository.findById(41L)).thenReturn(Optional.of(task));
        ScheduledTaskLifecycleService service = new ScheduledTaskLifecycleService(taskRepository, runRepository);

        service.archiveAfterTerminalResult(41L);

        assertThat(task.getStatus()).isEqualTo(ScheduledTask.TaskStatus.COMPLETED);
        assertThat(task.getNextRunAt()).isNull();
        verify(taskRepository).save(task);
    }

    @Test
    void archivesMissedOneShotTaskAndRecordsWhyItDidNotRun() {
        ScheduledTask task = oneShotTask();
        when(taskRepository.findByStatusAndRunOnceTrueAndNextRunAtBefore(
                        ScheduledTask.TaskStatus.ACTIVE, LocalDateTime.of(2026, 8, 11, 10, 0)))
                .thenReturn(List.of(task));
        ScheduledTaskLifecycleService service = new ScheduledTaskLifecycleService(taskRepository, runRepository);

        int archived = service.archiveExpiredOneShotTasks(LocalDateTime.of(2026, 8, 11, 10, 0));

        assertThat(archived).isOne();
        assertThat(task.getStatus()).isEqualTo(ScheduledTask.TaskStatus.COMPLETED);
        assertThat(task.getLastRunStatus()).isEqualTo(ScheduledTask.RunStatus.FAILED);
        assertThat(task.getLastRunOutput()).contains("horário agendado expirou");
        verify(taskRepository).save(task);
        verify(runRepository).save(org.mockito.ArgumentMatchers.any());
    }

    private ScheduledTask oneShotTask() {
        ScheduledTask task = new ScheduledTask();
        task.setId(41L);
        task.setPrompt("Abra uma aba no Chrome.");
        task.setRunOnce(true);
        task.setStatus(ScheduledTask.TaskStatus.ACTIVE);
        task.setNextRunAt(LocalDateTime.of(2026, 8, 11, 9, 30));
        return task;
    }
}
