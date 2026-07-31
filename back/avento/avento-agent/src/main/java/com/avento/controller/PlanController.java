package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.PlanCreateRequest;
import com.avento.api.dto.PlanResponse;
import com.avento.api.dto.TaskResponse;
import com.avento.api.dto.TaskUpdateRequest;
import com.avento.auth.security.AuthPrincipal;
import com.avento.model.AgentPlan;
import com.avento.model.AgentTask;
import com.avento.service.execution.RunEventStreamService;
import com.avento.service.plan.AgentPlanService;
import com.avento.service.plan.PlanBuilderService;
import com.avento.service.plan.PlanExecutionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final AgentPlanService planService;
    private final PlanBuilderService planBuilderService;
    private final PlanExecutionService executionService;
    private final RunEventStreamService runEventStreamService;
    private final com.avento.repository.AgentProfileRepository agentProfileRepository;

    @PostMapping
    public ResponseEntity<BaseResponse<PlanResponse>> createPlan(
            @Valid @RequestBody PlanCreateRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        AgentPlan plan = planBuilderService.buildPlan(principal.userId(), request);
        List<AgentTask> tasks = planService.getTasks(principal.userId(), plan.getId());
        return ApiResponses.created(PlanResponse.from(plan, mapTasks(tasks)));
    }

    @GetMapping
    public ResponseEntity<BaseResponse<List<PlanResponse>>> listPlans(
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<PlanResponse> responses = planService.listPlans(principal.userId()).stream()
                .map(p -> PlanResponse.from(p, mapTasks(planService.getTasks(principal.userId(), p.getId()))))
                .toList();
        return ApiResponses.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BaseResponse<PlanResponse>> getPlan(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        AgentPlan plan = planService.getPlan(principal.userId(), id);
        List<AgentTask> tasks = planService.getTasks(principal.userId(), id);
        return ApiResponses.ok(PlanResponse.from(plan, mapTasks(tasks)));
    }

    @GetMapping("/active")
    public ResponseEntity<BaseResponse<PlanResponse>> getActivePlan(@AuthenticationPrincipal AuthPrincipal principal) {
        AgentPlan plan = planService.getActivePlan(principal.userId());
        return ApiResponses.ok(
                PlanResponse.from(plan, mapTasks(planService.getTasks(principal.userId(), plan.getId()))));
    }

    @PutMapping("/{id}/tasks/{taskId}")
    public ResponseEntity<BaseResponse<TaskResponse>> updateTask(
            @PathVariable Long id,
            @PathVariable Long taskId,
            @Valid @RequestBody TaskUpdateRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        AgentTask task = planService.updateTask(
                principal.userId(),
                id,
                taskId,
                request.title(),
                request.details(),
                request.needsApproval(),
                request.orderIndex(),
                request.skipped());
        return ApiResponses.ok(TaskResponse.from(task));
    }

    @PostMapping("/{id}/tasks/{taskId}/approve")
    public ResponseEntity<BaseResponse<TaskResponse>> approveTask(
            @PathVariable Long id, @PathVariable Long taskId, @AuthenticationPrincipal AuthPrincipal principal) {
        AgentTask task = planService.approveTask(principal.userId(), id, taskId);
        return ApiResponses.ok(TaskResponse.from(task));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<BaseResponse<PlanResponse>> runPlan(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (!executionService.run(principal.userId(), id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "O plano já está executando ou não pode ser iniciado.");
        }
        return ApiResponses.accepted(PlanResponse.from(
                planService.getPlan(principal.userId(), id), mapTasks(planService.getTasks(principal.userId(), id))));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<BaseResponse<PlanResponse>> pausePlan(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        AgentPlan plan = executionService.pause(principal.userId(), id);
        return ApiResponses.ok(PlanResponse.from(plan, mapTasks(planService.getTasks(principal.userId(), id))));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<BaseResponse<PlanResponse>> resumePlan(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        if (!executionService.run(principal.userId(), id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "O plano já está executando ou não pode ser retomado.");
        }
        return ApiResponses.accepted(PlanResponse.from(
                planService.getPlan(principal.userId(), id), mapTasks(planService.getTasks(principal.userId(), id))));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<BaseResponse<PlanResponse>> cancelPlan(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        AgentPlan plan = executionService.cancel(principal.userId(), id);
        return ApiResponses.ok(PlanResponse.from(plan, mapTasks(planService.getTasks(principal.userId(), id))));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamPlan(
            @PathVariable Long id,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        planService.getPlan(principal.userId(), id);
        return runEventStreamService.stream(principal.userId(), PlanExecutionService.planStreamId(id), lastEventId);
    }

    private List<TaskResponse> mapTasks(List<AgentTask> tasks) {
        // Resolve o nome do agente atribuído a cada tarefa (as tarefas já vêm escopadas por usuário,
        // então os ids atribuídos pertencem ao próprio dono).
        Map<Long, String> agentNames = new java.util.HashMap<>();
        List<Long> ids = tasks.stream()
                .map(AgentTask::getAssignedAgentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (!ids.isEmpty()) {
            agentProfileRepository.findAllById(ids).forEach(agent -> agentNames.put(agent.getId(), agent.getName()));
        }
        return tasks.stream()
                .map(task -> TaskResponse.from(task, agentNames.get(task.getAssignedAgentId())))
                .toList();
    }
}
