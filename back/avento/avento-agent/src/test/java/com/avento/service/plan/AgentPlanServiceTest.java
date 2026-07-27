package com.avento.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.AgentPlan;
import com.avento.model.AgentTask;
import com.avento.repository.AgentPlanRepository;
import com.avento.repository.AgentTaskRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentPlanServiceTest {

    private final AgentPlanRepository planRepository = mock(AgentPlanRepository.class);
    private final AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
    private final AgentPlanService service = new AgentPlanService(planRepository, taskRepository);
    private final UUID userId = UUID.randomUUID();

    @Test
    void cannotReadTaskThroughAnotherPlanId() {
        when(taskRepository.findByIdAndPlanIdAndUserId(9L, 2L, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTask(userId, 2L, 9L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalClearsGateBeforeReturningTaskToPending() {
        AgentPlan plan = new AgentPlan(userId, 1L, "goal", AgentPlan.STATUS_PAUSED, "[]");
        plan.setId(2L);
        AgentTask task = new AgentTask(2L, userId, 1, "task", "details", "[]", AgentTask.STATUS_BLOCKED, true);
        task.setId(9L);
        when(planRepository.findByIdAndUserId(2L, userId)).thenReturn(Optional.of(plan));
        when(taskRepository.findByIdAndPlanIdAndUserId(9L, 2L, userId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AgentTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentTask approved = service.approveTask(userId, 2L, 9L);

        assertThat(approved.isNeedsApproval()).isFalse();
        assertThat(approved.getStatus()).isEqualTo(AgentTask.STATUS_PENDING);
    }

    @Test
    void cannotApproveAnotherUsersTask() {
        UUID otherUser = UUID.randomUUID();
        when(planRepository.findByIdAndUserId(2L, otherUser)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveTask(otherUser, 2L, 9L)).isInstanceOf(IllegalArgumentException.class);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void reorderingKeepsTaskPositionsUniqueAndContiguous() {
        AgentPlan plan = new AgentPlan(userId, 1L, "goal", AgentPlan.STATUS_DRAFT, "[]");
        plan.setId(2L);
        AgentTask first = task(9L, 1);
        AgentTask second = task(10L, 2);
        AgentTask third = task(11L, 3);
        when(planRepository.findByIdAndUserId(2L, userId)).thenReturn(Optional.of(plan));
        when(taskRepository.findByIdAndPlanIdAndUserId(9L, 2L, userId)).thenReturn(Optional.of(first));
        when(taskRepository.findByPlanIdAndUserIdOrderByOrderIndex(2L, userId))
                .thenReturn(List.of(first, second, third));
        when(taskRepository.save(any(AgentTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateTask(userId, 2L, 9L, "task 9", "details", false, 3, false);

        assertThat(first.getOrderIndex()).isEqualTo(3);
        assertThat(second.getOrderIndex()).isEqualTo(1);
        assertThat(third.getOrderIndex()).isEqualTo(2);
        verify(taskRepository).saveAll(List.of(second, third));
    }

    private AgentTask task(Long id, int order) {
        AgentTask task =
                new AgentTask(2L, userId, order, "task " + id, "details", "[]", AgentTask.STATUS_PENDING, false);
        task.setId(id);
        return task;
    }
}
