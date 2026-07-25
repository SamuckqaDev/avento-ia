package com.avento.service.plan;

import com.avento.model.AgentPlan;
import com.avento.model.AgentTask;
import com.avento.repository.AgentPlanRepository;
import com.avento.repository.AgentTaskRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPlanService {

    private static final Set<String> EDITABLE_PLAN_STATUSES = Set.of(AgentPlan.STATUS_DRAFT, AgentPlan.STATUS_PAUSED);
    private static final List<String> ACTIVE_PLAN_STATUSES =
            List.of(AgentPlan.STATUS_RUNNING, AgentPlan.STATUS_PAUSED, AgentPlan.STATUS_DRAFT);

    private final AgentPlanRepository planRepository;
    private final AgentTaskRepository taskRepository;

    @Transactional(readOnly = true)
    public List<AgentPlan> listPlans(UUID userId) {
        return planRepository.findByUserIdOrderByUpdatedAtDesc(requireUser(userId));
    }

    @Transactional(readOnly = true)
    public AgentPlan getPlan(UUID userId, Long planId) {
        return planRepository
                .findByIdAndUserId(planId, requireUser(userId))
                .orElseThrow(() -> new IllegalArgumentException("Plan not found or not owned by user"));
    }

    @Transactional(readOnly = true)
    public AgentPlan getActivePlan(UUID userId) {
        return planRepository
                .findFirstByUserIdAndStatusInOrderByUpdatedAtDesc(requireUser(userId), ACTIVE_PLAN_STATUSES)
                .orElseThrow(() -> new IllegalArgumentException("Nenhum plano ativo foi encontrado."));
    }

    @Transactional(readOnly = true)
    public List<AgentTask> getTasks(UUID userId, Long planId) {
        // Validation check for owner
        getPlan(userId, planId);
        return taskRepository.findByPlanIdAndUserIdOrderByOrderIndex(planId, requireUser(userId));
    }

    @Transactional(readOnly = true)
    public AgentTask getTask(UUID userId, Long planId, Long taskId) {
        return taskRepository
                .findByIdAndPlanIdAndUserId(taskId, planId, requireUser(userId))
                .orElseThrow(() -> new IllegalArgumentException("Task not found or not owned by user"));
    }

    @Transactional
    public AgentTask updateTask(
            UUID userId,
            Long planId,
            Long taskId,
            String title,
            String details,
            Boolean needsApproval,
            Integer orderIndex,
            Boolean skipped) {
        requireEditablePlan(userId, planId);
        AgentTask task = getTask(userId, planId, taskId);
        task.setTitle(title);
        task.setDetails(details);
        if (needsApproval != null) {
            task.setNeedsApproval(needsApproval);
        }
        if (orderIndex != null && orderIndex > 0 && orderIndex != task.getOrderIndex()) {
            reorderTask(userId, planId, task, orderIndex);
        }
        if (Boolean.TRUE.equals(skipped)) {
            task.setStatus(AgentTask.STATUS_SKIPPED);
        } else if (AgentTask.STATUS_SKIPPED.equals(task.getStatus())) {
            task.setStatus(AgentTask.STATUS_PENDING);
        }
        return taskRepository.save(task);
    }

    private void reorderTask(UUID userId, Long planId, AgentTask task, int requestedOrder) {
        List<AgentTask> tasks = getTasks(userId, planId);
        int previousOrder = task.getOrderIndex();
        int targetOrder = Math.max(1, Math.min(requestedOrder, tasks.size()));
        if (previousOrder == targetOrder) {
            return;
        }

        List<AgentTask> shifted = new ArrayList<>();
        for (AgentTask candidate : tasks) {
            if (candidate.getId().equals(task.getId())) {
                continue;
            }
            boolean mustShift = previousOrder < targetOrder
                    ? candidate.getOrderIndex() > previousOrder && candidate.getOrderIndex() <= targetOrder
                    : candidate.getOrderIndex() >= targetOrder && candidate.getOrderIndex() < previousOrder;
            if (mustShift) {
                candidate.setOrderIndex(candidate.getOrderIndex() + (previousOrder < targetOrder ? -1 : 1));
                shifted.add(candidate);
            }
        }
        task.setOrderIndex(targetOrder);
        taskRepository.saveAll(shifted);
    }

    @Transactional
    public AgentTask updateTaskStatus(UUID userId, Long planId, Long taskId, String status, String resultSummary) {
        AgentTask task = getTask(userId, planId, taskId);
        task.setStatus(status);
        if (resultSummary != null) {
            task.setResultSummary(resultSummary);
        }
        return taskRepository.save(task);
    }

    @Transactional
    public AgentTask approveTask(UUID userId, Long planId, Long taskId) {
        requireEditablePlan(userId, planId);
        AgentTask task = getTask(userId, planId, taskId);
        task.setNeedsApproval(false);
        task.setStatus(AgentTask.STATUS_PENDING);
        task.setResultSummary(null);
        return taskRepository.save(task);
    }

    @Transactional
    public AgentPlan updatePlanStatus(UUID userId, Long planId, String status) {
        AgentPlan plan = getPlan(userId, planId);
        plan.setStatus(status);
        return planRepository.save(plan);
    }

    private UUID requireUser(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required");
        }
        return userId;
    }

    private AgentPlan requireEditablePlan(UUID userId, Long planId) {
        AgentPlan plan = getPlan(userId, planId);
        if (!EDITABLE_PLAN_STATUSES.contains(plan.getStatus())) {
            throw new IllegalStateException("O plano precisa estar em rascunho ou pausado para editar tarefas.");
        }
        return plan;
    }
}
