# Implementation Plan - Avento as an Autonomous Agent ("Local Codex")

> **Audience:** this is the base execution specification for the autonomous-agent mode.
> Agreed flow: **Antigravity implements -> Codex fixes -> Claude performs the final review**.
> Read the entire document before writing code.
>
> **Current position:** the first implementation already exists and must not be rebuilt. For the
> specialized-agent evolution, follow this order:
> `agent-corrections-plan.md` (architecture decisions) ->
> `codex-agent-implementation-plan.md` (implementation) -> `codex-review-plan.md` (final audit).

---

## 0. Mandatory Baseline Audit

The project has changed significantly around long-term memory, token budgets, history summaries,
and user scoping. Before implementing anything:

1. Run `git status` and `git log --oneline -15`. Separate committed work from local changes. Do not
   commit during this phase.
2. Validate the backend from `back/avento`:

   ```bash
   mvn -q compile
   mvn test
   mvn spotless:check
   ```

   Use the immediately preceding local baseline. The last documented result was 401 tests, zero
   failures, and one skipped test. Stop and report unexpected failures.
3. Validate the frontend from the repository root with `npm --prefix front run validate`.
4. Review recent high-risk areas:
   - `AgentService`: `num_ctx=16384` and `num_predict=4096` may pressure a 16 GB machine. Measure and
     report; do not change model behavior without approval.
   - Long-term memory: verify that `UserMemory`, repositories, services, controllers, and DTOs are
     fully scoped by `userId`.
   - History compaction: inspect `AgentService.compactMessagesForModel(...)` and confirm that the
     current user request is never truncated.
5. Produce a short baseline report covering build status, regressions, inconsistent files, and
   confirmation that the existing backbone in section 4 is understood.

Only continue after the baseline is clean.

---

## 1. Objective

Make Avento behave as an autonomous coding agent similar to Codex or Claude Code. The user provides
an objective, Avento creates an ordered task list, and it executes one task at a time while verifying
the project and recording a checkpoint between tasks. The user can observe, pause, approve risky
steps, resume, and cancel.

The local model is small and has a limited context window. Task-level partitioning is therefore a
core architectural requirement, not an optimization.

---

## 2. Non-Negotiable Constraints

1. **User isolation.** Every user-owned entity carries a `UUID userId`. Every endpoint derives it
   from `AuthPrincipal`; no request body or parameter can choose the owner. Every repository query
   is scoped by user.
2. **Local-first execution.** Model inference uses local providers such as Ollama. Do not introduce a
   mandatory cloud dependency.
3. **Bounded context and memory.** Never solve context pressure by loading an entire repository or
   increasing the context window without measurement.
4. **No regressions.** Preserve the validated backend and frontend baselines.
5. **Project standards.** Use constructor injection, DTOs, the existing layer boundaries, Lombok
   where appropriate, `BaseResponse<T>`, `ApiResponses`, and Spotless.
6. **Local policies remain local.** Do not version `src/main/resources/agent/policies/*.md`.
7. **Reuse the existing backbone.** Do not create parallel queues, verification, rollback, or
   cancellation systems.

### Required Ownership Pattern

```java
@Column(name = "user_id", nullable = false)
private UUID userId;

Optional<AgentPlan> findByIdAndUserId(Long id, UUID userId);

@GetMapping("/{id}")
public ResponseEntity<BaseResponse<PlanResponse>> get(
        @PathVariable Long id,
        @AuthenticationPrincipal AuthPrincipal principal) {
    return ApiResponses.ok(PlanResponse.from(planService.get(principal.userId(), id)));
}
```

Any service method that mutates a user-owned plan or task without receiving `userId` is incorrect.

---

## 3. Existing Backbone to Reuse

| Requirement | Existing component |
|---|---|
| Durable jobs and recovery | `AgentRunSubmissionService`, `AgentRunWorker`, `RedisOutboxDispatcher`, `AgentRunJob`, `ExecutionOutboxEvent` |
| Cancellation | `AgentRunCancellationRegistry` |
| Agent turn with tools | `AgentService.streamChat(...)` through `AgentExecutionEngine` |
| Project verification | `ProjectVerificationService` |
| Checkpoint and rollback | `FileBackupService.backupBeforeWrite(...)` and `revertMostRecent(...)` |
| Plan-generation guidance | `agent/skills/implementation-plan.md` |
| API envelope | `BaseResponse<T>` and `ApiResponses` |
| Authentication | `AuthPrincipal` |
| Progress streaming | `RunEventPublisher` and the existing SSE pattern |

The current `PlanExecutionService` already uses these foundations. Extend it; do not replace it.

---

## 4. Data Model

### `AgentPlan` (`agent_plans`)

- `id: Long`
- `userId: UUID`, required
- `chatId: Long?`
- `goal: TEXT`
- `status: DRAFT | RUNNING | PAUSED | DONE | FAILED | CANCELLED`
- `workspaceRoots: TEXT`, structured serialization
- `currentTaskId: Long?`
- `createdAt`, `updatedAt`
- indexes on `(user_id, status)` and `(user_id, updated_at)`

### `AgentTask` (`agent_tasks`)

- `id: Long`
- `planId: Long`
- `userId: UUID`, denormalized for direct ownership checks
- `orderIndex: int`
- `title: String`
- `details: TEXT`
- `targetFiles: TEXT?`
- `status: PENDING | RUNNING | DONE | FAILED | SKIPPED | BLOCKED`
- `needsApproval: boolean`
- `resultSummary: TEXT?`
- `attempts: int`
- `createdAt`, `updatedAt`
- indexes on `(plan_id, order_index)` and `(user_id, plan_id)`

Specialized-agent fields are defined in `agent-corrections-plan.md`.

---

## 5. Task Context Partitioning

Each task receives a small, self-contained context:

1. A concise trusted system prompt.
2. The current task title and details.
3. Only relevant files from `targetFiles`, repository search, symbols, or RAG.
4. A compact progress summary of completed tasks, limited to approximately 8-10 lines.
5. Only tools relevant and allowed for that task.

Hard rules:

- Do not send the entire plan or raw chat history with every task.
- Do not load the entire repository.
- If one task cannot fit, split it into smaller tasks instead of silently truncating it.
- Log an estimated prompt size before submitting a task, without logging private prompt content.

---

## 6. Core Services

### `PlanBuilderService`

`build(UUID userId, Long chatId, String goal, List<String> workspaceRoots)` must:

- request bounded structured JSON from the local model;
- parse an ordered task list safely;
- reject or repair malformed entries without accepting arbitrary unbounded output;
- persist a `DRAFT` plan and `PENDING` tasks in one transaction;
- cap the number of tasks, for example at 20.

### `PlanExecutionService`

The existing service is the plan orchestrator. Preserve its recovery, active-plan guard, durable job
submission, verification, checkpoint, rollback, retry, and event-publication behavior.

For every pending task, in order:

1. Stop at an approval gate when required.
2. Mark the task `RUNNING` and update `currentTaskId`.
3. Build the partitioned context and submit one durable agent job.
4. Wait for the durable job result without blocking a web request.
5. Verify the project.
6. Retry a bounded number of times when verification fails.
7. On final failure, mark the task failed and pause the plan.
8. On success, record a short result summary and advance.

Mark the plan `DONE` only after every non-skipped task succeeds.

### `PlanController` (`/api/plans`)

All endpoints are scoped by `principal.userId()`:

- `POST /api/plans`
- `GET /api/plans`
- `GET /api/plans/{id}`
- `PUT /api/plans/{id}/tasks/{taskId}`
- `POST /api/plans/{id}/run`
- `POST /api/plans/{id}/pause`
- `POST /api/plans/{id}/resume`
- `POST /api/plans/{id}/cancel`
- `GET /api/plans/{id}/stream`

Approval and agent-assignment endpoint corrections are specified in the later documents.

---

## 7. Guardrails

1. Verify after each task.
2. Keep a task-level checkpoint and a reliable rollback path.
3. Require approval for risky actions without treating approval as a tool allow-list.
4. Pause on failure instead of continuing through a broken build.
5. Cap retries to prevent infinite loops.
6. Persist critical execution state; do not rely only on React state or in-memory maps.
7. Keep all workspace access inside authorized roots.

---

## 8. Frontend Base

> **Evolution note:** the current correction is defined in `agent-corrections-plan.md`. A compact
> review/approval card remains in chat, while the full plan lives in the side panel. Both surfaces
> reference the same persisted `DRAFT`; merely opening it never starts execution.

- The plan panel owns the objective, ordered task list, status, progress, and plan controls.
- Progress is updated through SSE and restored from backend state after reload.
- Use the shared `apiClient`; authentication is carried only by the backend cookie.
- Do not create user state that is shared across accounts or chats.

---

## 9. Delivery Phases

### Phase 1 - Executable plan

Entities, builder, execution service, controller, verification/checkpoint flow, and live task list.
Acceptance: a simple objective becomes ordered tasks, runs one task at a time, verifies each result,
and never leaks plans across users.

### Phase 2 - Scheduling and queueing

Schedule plans and enqueue them on the existing durable backbone. Acceptance: a scheduled plan can
start without an open browser and recover after restart.

### Phase 3 - Fine-grained partitioning

Use target files, symbol search, RAG, and progress summaries to keep every task small. Acceptance:
large objectives are split rather than overflowing the model context.

Each phase ends with backend and frontend validation.

---

## 10. Required Tests

- `PlanBuilderServiceTest`: structured parsing, ordering, bounds, and malformed output.
- `PlanExecutionServiceTest`: ordering, failure stop, retries, cancellation, approval gate, and final
  statuses.
- Ownership tests: one user cannot read, mutate, stream, or execute another user's plan/task.
- Repository tests: owner-scoped queries return empty for a different owner.
- Recovery tests: interrupted plans resume idempotently after application restart.

---

## 11. Final Checklist

- [ ] Baseline was recorded before implementation.
- [ ] Every entity, repository, service, and endpoint is scoped by `userId`.
- [ ] The existing durable execution backbone was reused.
- [ ] Task context is partitioned and bounded.
- [ ] Verification, checkpoint, approval, retry cap, and stop-on-failure are active.
- [ ] Backend and frontend validations are green without baseline regression.
- [ ] `mvn spotless:apply` was run.
- [ ] No local policy file was versioned.
- [ ] No commit or push was performed without explicit approval.

## 12. Do Not

- Do not mix data or context between users.
- Do not load a repository or raw conversation history into every task prompt.
- Do not continue after a failed verification.
- Do not recreate durable execution, verification, or rollback infrastructure.
- Do not increase `num_ctx` as a substitute for partitioning.
- Do not commit or push without explicit approval.
