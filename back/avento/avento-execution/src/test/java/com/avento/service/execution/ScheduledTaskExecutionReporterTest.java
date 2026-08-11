package com.avento.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.ScheduledTask;
import com.avento.model.ScheduledTaskRepository;
import com.avento.model.ScheduledTaskRun;
import com.avento.model.ScheduledTaskRunRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskExecutionReporterTest {

    @Mock
    private ScheduledTaskRepository taskRepository;

    @Mock
    private ScheduledTaskRunRepository runRepository;

    @Mock
    private ScheduledTaskLifecycleService lifecycleService;

    @Test
    void recordsTheExactFailureStageAndReasonInTheTaskAndItsHistory() {
        ScheduledTask task = new ScheduledTask();
        ScheduledTaskRun run = new ScheduledTaskRun();
        when(taskRepository.findById(62L)).thenReturn(Optional.of(task));
        when(runRepository.findTop50ByTaskIdOrderByCreatedAtDesc(62L)).thenReturn(List.of(run));
        ScheduledTaskExecutionReporter reporter =
                new ScheduledTaskExecutionReporter(taskRepository, runRepository, lifecycleService);

        reporter.recordFailure(
                62L,
                "run_chrome",
                "Ferramenta open_browser_tab",
                "Google Chrome não respondeu ao AppleScript.",
                "[tool.failed] open_browser_tab: Google Chrome não respondeu ao AppleScript.");

        assertThat(task.getLastRunStatus()).isEqualTo(ScheduledTask.RunStatus.FAILED);
        assertThat(task.getLastRunError()).isEqualTo("Google Chrome não respondeu ao AppleScript.");
        assertThat(task.getLastRunOutput())
                .contains("Run: run_chrome")
                .contains("Onde falhou: Ferramenta open_browser_tab")
                .contains("Trilha da execução");
        assertThat(run.getStatus()).isEqualTo(ScheduledTask.RunStatus.FAILED);
        assertThat(run.getError()).isEqualTo("Google Chrome não respondeu ao AppleScript.");
        verify(taskRepository).save(task);
        verify(runRepository).save(run);
        verify(lifecycleService).archiveAfterTerminalResult(62L);
    }
}
