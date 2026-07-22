package com.avento.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.AgentPlan;
import com.avento.model.AgentRunJob;
import com.avento.model.AgentTask;
import com.avento.repository.AgentPlanRepository;
import com.avento.repository.AgentRunJobRepository;
import com.avento.repository.AgentTaskRepository;
import com.avento.service.FileBackupService;
import com.avento.service.ProjectVerificationService;
import com.avento.service.WorkspaceAccessService;
import com.avento.service.dto.RevertResult;
import com.avento.service.dto.VerificationResult;
import com.avento.service.execution.AgentRunSubmissionService;
import com.avento.service.execution.RunEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

class PlanExecutionServiceTest {

    private final AgentPlanRepository planRepository = mock(AgentPlanRepository.class);
    private final AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
    private final AgentRunSubmissionService submissionService = mock(AgentRunSubmissionService.class);
    private final AgentRunJobRepository jobRepository = mock(AgentRunJobRepository.class);
    private final ProjectVerificationService verificationService = mock(ProjectVerificationService.class);
    private final WorkspaceAccessService workspaceAccessService = mock(WorkspaceAccessService.class);
    private final FileBackupService backupService = mock(FileBackupService.class);
    private final RunEventPublisher eventPublisher = mock(RunEventPublisher.class);
    private final AgentRoutingService agentRoutingService = mock(AgentRoutingService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskExecutor directExecutor = Runnable::run;
    private final UUID userId = UUID.randomUUID();

    @TempDir
    Path workspace;

    private PlanExecutionService service;
    private AgentPlan plan;
    private AgentTask task;

    @BeforeEach
    void setup() throws Exception {
        service = new PlanExecutionService(
                planRepository,
                taskRepository,
                submissionService,
                jobRepository,
                verificationService,
                workspaceAccessService,
                backupService,
                mapper,
                eventPublisher,
                agentRoutingService,
                directExecutor);
        com.avento.model.AgentProfile defaultAgent =
                new com.avento.model.AgentProfile(userId, "Generalista", "", "", "", "", null, true);
        defaultAgent.setId(1L);
        when(agentRoutingService.pick(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentRoutingService.Routed(defaultAgent, "default"));

        plan = new AgentPlan(
                userId, 55L, "Goal", AgentPlan.STATUS_DRAFT, mapper.writeValueAsString(List.of(workspace.toString())));
        plan.setId(1L);
        task = new AgentTask(1L, userId, 1, "Task", "Details", "[]", AgentTask.STATUS_PENDING, false);
        task.setId(10L);

        when(planRepository.findByIdAndUserId(1L, userId)).thenAnswer(ignored -> Optional.of(plan));
        when(taskRepository.findByPlanIdAndUserIdOrderByOrderIndex(1L, userId)).thenReturn(List.of(task));
        when(taskRepository.findByIdAndPlanIdAndUserId(10L, 1L, userId)).thenAnswer(ignored -> Optional.of(task));
        when(planRepository.save(any(AgentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.save(any(AgentTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workspaceAccessService.registerWorkspaceRoot(userId, workspace.toString()))
                .thenReturn(workspace);
        when(backupService.revertMostRecent(userId, 55L)).thenReturn(new RevertResult("run", 1));
    }

    @Test
    void executesTaskThroughDurableBackboneAndVerifiesWorkspace() {
        completedJobForEveryAttempt();
        when(verificationService.verify(eq(userId), eq(workspace.toString())))
                .thenReturn(new VerificationResult(true, true, "OK", 0, false, ""));

        boolean scheduled = service.run(userId, 1L);

        assertThat(scheduled).isTrue();
        assertThat(plan.getStatus()).isEqualTo(AgentPlan.STATUS_DONE);
        assertThat(task.getStatus()).isEqualTo(AgentTask.STATUS_DONE);
        assertThat(task.getAttempts()).isEqualTo(1);
        verify(submissionService).submit(eq(userId), eq(55L), any(), eq("plan_1_task_10_attempt_1"));
        verify(verificationService).verify(eq(userId), eq(workspace.toString()));
    }

    @Test
    void doesNotFailWhenProjectHasNothingToVerify() {
        // Projeto vazio / sem build (detected=false) não é falha — não há o que checar neste passo.
        completedJobForEveryAttempt();
        when(verificationService.verify(eq(userId), eq(workspace.toString())))
                .thenReturn(new VerificationResult(false, false, "", 0, false, "Nenhum comando detectado"));

        service.run(userId, 1L);

        assertThat(plan.getStatus()).isEqualTo(AgentPlan.STATUS_DONE);
        assertThat(task.getStatus()).isEqualTo(AgentTask.STATUS_DONE);
    }

    @Test
    void pausesBeforeTaskThatNeedsApproval() {
        task.setNeedsApproval(true);

        service.run(userId, 1L);

        assertThat(plan.getStatus()).isEqualTo(AgentPlan.STATUS_PAUSED);
        assertThat(task.getStatus()).isEqualTo(AgentTask.STATUS_BLOCKED);
        verify(submissionService, never()).submit(any(), any(), any(), anyString());
    }

    @Test
    void stopsAfterBoundedVerificationFailuresAndRollsBackEachAttempt() {
        completedJobForEveryAttempt();
        when(verificationService.verify(eq(userId), eq(workspace.toString())))
                .thenReturn(new VerificationResult(true, false, "failed", 1, false, "compile error"));

        service.run(userId, 1L);

        assertThat(plan.getStatus()).isEqualTo(AgentPlan.STATUS_PAUSED);
        assertThat(task.getStatus()).isEqualTo(AgentTask.STATUS_FAILED);
        assertThat(task.getAttempts()).isEqualTo(2);
        verify(backupService, times(2)).revertMostRecent(userId, 55L);
        verify(submissionService, times(2)).submit(eq(userId), eq(55L), any(), anyString());
    }

    @Test
    void cannotExecuteAnotherUsersPlan() {
        UUID otherUser = UUID.randomUUID();
        when(planRepository.findByIdAndUserId(1L, otherUser)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run(otherUser, 1L)).isInstanceOf(IllegalArgumentException.class);

        verify(submissionService, never()).submit(any(), anyLong(), any(), anyString());
    }

    @Test
    void recoveryReusesInterruptedAttemptIdempotently() {
        plan.setStatus(AgentPlan.STATUS_RUNNING);
        task.setStatus(AgentTask.STATUS_RUNNING);
        task.setAttempts(1);
        when(planRepository.findByStatusIn(List.of(AgentPlan.STATUS_RUNNING))).thenReturn(List.of(plan));
        completedJobForEveryAttempt();
        when(verificationService.verify(eq(userId), eq(workspace.toString())))
                .thenReturn(new VerificationResult(true, true, "OK", 0, false, ""));

        service.recoverInterruptedPlans();

        assertThat(plan.getStatus()).isEqualTo(AgentPlan.STATUS_DONE);
        assertThat(task.getStatus()).isEqualTo(AgentTask.STATUS_DONE);
        assertThat(task.getAttempts()).isEqualTo(1);
        verify(submissionService).submit(eq(userId), eq(55L), any(), eq("plan_1_task_10_attempt_1"));
    }

    @Test
    void executesTasksInTheirPersistedOrder() {
        AgentTask second = new AgentTask(1L, userId, 2, "Task 2", "Details 2", "[]", AgentTask.STATUS_PENDING, false);
        second.setId(11L);
        when(taskRepository.findByPlanIdAndUserIdOrderByOrderIndex(1L, userId)).thenReturn(List.of(task, second));
        when(taskRepository.findByIdAndPlanIdAndUserId(11L, 1L, userId)).thenReturn(Optional.of(second));
        completedJobForEveryAttempt();
        when(verificationService.verify(eq(userId), eq(workspace.toString())))
                .thenReturn(new VerificationResult(true, true, "OK", 0, false, ""));

        service.run(userId, 1L);

        ArgumentCaptor<String> runIds = ArgumentCaptor.forClass(String.class);
        verify(submissionService, times(2)).submit(eq(userId), eq(55L), any(), runIds.capture());
        assertThat(runIds.getAllValues()).containsExactly("plan_1_task_10_attempt_1", "plan_1_task_11_attempt_1");
        assertThat(second.getStatus()).isEqualTo(AgentTask.STATUS_DONE);
    }

    @Test
    void cancellationStopsTheCurrentRunAndTask() {
        plan.setStatus(AgentPlan.STATUS_RUNNING);
        plan.setCurrentTaskId(task.getId());
        plan.setCurrentRunId("plan_1_task_10_attempt_1");
        task.setStatus(AgentTask.STATUS_RUNNING);

        service.cancel(userId, plan.getId());

        assertThat(plan.getStatus()).isEqualTo(AgentPlan.STATUS_CANCELLED);
        assertThat(task.getStatus()).isEqualTo(AgentTask.STATUS_SKIPPED);
        verify(submissionService).requestCancellation("plan_1_task_10_attempt_1", userId);
    }

    private void completedJobForEveryAttempt() {
        when(submissionService.submit(eq(userId), eq(55L), any(), anyString())).thenAnswer(invocation -> {
            AgentRunJob job = new AgentRunJob();
            job.setId((long) task.getAttempts());
            job.setRunId(invocation.getArgument(3));
            job.setUserId(userId);
            job.setChatId(55L);
            job.setStatus(AgentRunJob.Status.COMPLETED);
            return job;
        });
        when(jobRepository.findByRunIdAndUserId(anyString(), eq(userId))).thenAnswer(invocation -> {
            AgentRunJob job = new AgentRunJob();
            job.setRunId(invocation.getArgument(0));
            job.setUserId(userId);
            job.setChatId(55L);
            job.setStatus(AgentRunJob.Status.COMPLETED);
            return Optional.of(job);
        });
    }
}
