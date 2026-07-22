# Agent Mode Corrections and Specialized Agents

> **Purpose:** record the diagnosis and architectural decisions. The surgical implementation guide
> is `docs/codex-agent-implementation-plan.md`. The original specification remains in
> `docs/autonomous-agent-plan.md`, and the final audit is in `docs/codex-review-plan.md`.

---

## 1. Diagnosis Confirmed Against the Code

### What already works and must be preserved

- `PlanExecutionService` already executes tasks in order, prevents duplicate execution of the same
  plan, runs verification/checkpoint/revert, waits for durable jobs, and recovers plans after boot.
- `PlanExecutionConfig` already configures `planTaskExecutor` with `corePoolSize=1`,
  `maxPoolSize=1`, and a bounded queue. No executor-size correction is pending; behavior still needs
  an explicit serialization test and accurate documentation.
- `AgentRunSubmissionService`, `AgentRunWorker`, outbox, cancellation, and event publication already
  form the durable backbone. Do not create a second queue.
- `AgentPlanService`, `PlanBuilderService`, `PlanController`, `planApi`, and
  `PlanExecutionPanel` already provide the base workflow.

### Confirmed problems

1. **Two disconnected plan objects:** `ImplPlanCard` renders model text while
   `PlanExecutionPanel` maintains an unrelated persisted plan. The card and panel must represent the
   same `DRAFT` plan ID; using two surfaces is intentional, using two plan objects is not.
2. **Approval is in the wrong surface:** a blocked task is approved from the panel. The contextual
   request belongs in chat; the panel should only reflect status.
3. **No `AgentProfile` layer:** tasks do not carry a reusable persona, preferred model, or tool cap.
4. **Persona injection through the payload is ineffective:**
   `AgentService.compactMessagesForModel(...)` removes incoming `system` messages and
   `withBackendIdentityPrompt(...)` creates the trusted backend system prompt. Adding a system
   message to `taskPayload` alone does not apply the persona.
5. **`requiredToolNames` is not a strict allow-list:** when filtering finds no tool, the current flow
   falls back to normal selection. Reusing it without separating concepts can accidentally expose
   all tools.
6. **Persisted approval is tool-specific:** `PendingToolApprovalService` stores a
   `PendingToolExecution`; it has no approval kind, `planId`, or `taskId`.
7. **`IntentEmbeddingClassifier` handles fixed intents:** it does not rank dynamic user profiles.
   Initial profile routing must be deterministic and cheap.

---

## 2. Target Experience

| Element | Correct surface |
|---|---|
| Compact pending-plan summary and primary approval | chat card |
| Full objective, tasks, files, risks, verification, and assigned agent | plan side panel |
| Start after explicit approval, pause, resume, and cancel | chat card and plan side panel |
| Contextual task/tool approval | chat |
| Reusable persona management | agents screen/panel |
| Open full plan details | click the plan card in chat |

An **agent** is a reusable user-owned persona. A **plan** represents an objective. A **task** belongs
to a plan and references an agent. The generated plan is persisted as `DRAFT` and remains inert until
the user explicitly chooses `Approve and execute`. Execution remains strictly one task at a time.

---

## 3. Agent Data Model

### `AgentProfile`

- `id: Long`
- `userId: UUID`, required and indexed
- `name: String`
- `specialty: String`
- `systemInstructions: TEXT`
- `allowedTools: JSON/TEXT`, serialized as canonical tool names
- `triggers: JSON/TEXT`, serialized as normalized values
- `model: String?`, blank means the system default
- `isDefault: boolean`
- `createdAt`, `updatedAt`

DTOs expose `allowedTools` and `triggers` as `List<String>`, never CSV. The service trims values,
removes blanks, preserves deterministic order with `LinkedHashSet`, and validates tools against the
backend registry. Unknown tools produce a validation error.

### Profile invariants

- Every read and write is scoped by the authenticated `userId`.
- Exactly one default profile must exist after first resolution.
- Default changes run in one transaction with a pessimistic lock over that user's profiles.
- Deleting a profile assigned to a `RUNNING` task is forbidden.
- Deleting a profile assigned only to non-started tasks clears those assignments and reroutes them.
- Deleting the default promotes another profile deterministically; if none exists, create a
  `Generalist` in the same transaction.

### `AgentTask` additions

- `assignedAgentId: Long?`
- `agentRationale: String?`

Do not overload the existing full task-update DTO for assignment. Add a dedicated endpoint to avoid
resending title/details and causing lost updates:

```text
PUT /api/plans/{planId}/tasks/{taskId}/agent
body: { "agentId": 123 | null }
```

`null` requests automatic rerouting.

---

## 4. Deterministic Task Routing

`AgentRoutingService.pick(userId, task)` follows this order:

1. Honor a manually assigned `assignedAgentId` after owner validation.
2. Normalize `title + details + targetFiles`.
3. Score exact and token overlap against profile triggers.
4. Score specialty matches at a lower weight.
5. Apply a documented deterministic tie-break: score, non-default profile, `updatedAt`, then `id`.
6. Use the user's default profile when no score is positive.

The initial router must not invoke another model or use `IntentEmbeddingClassifier`. A later semantic
version may use `EmbeddingModel` directly and cache profile vectors without competing with primary
inference.

`agentRationale` stores a short stable explanation such as `java trigger (3 points)` or
`default profile fallback`.

---

## 5. Trusted Execution Context

Create an internal typed object such as:

```java
public record AgentExecutionOptions(
        String trustedSystemInstructions,
        Set<String> allowedToolNames,
        String preferredModel) {}
```

Flow:

```text
PlanExecutionService
  -> durable payload with agentProfileId and a validated options snapshot
  -> AgentRunWorker
  -> AgentExecutionEngine
  -> AgentService
  -> trusted system prompt and strict tool selection
```

Mandatory decisions:

- Append the persona on the backend in `withBackendIdentityPrompt(...)` or a dedicated trusted
  helper after loading and validating the user's profile. Never accept a privileged system-prompt
  override from the frontend.
- Store a validated snapshot of instructions, model, and tools in the durable job so editing a
  profile does not change a task that has already started.
- An empty `allowedTools` preserves the current eligible tool set. A non-empty list is a strict cap:
  `eligible tools intersected with profile tools`. An empty intersection remains empty; it never
  falls back to all.
- A tool allow-list does not grant permission. Allowed risky tools still pass through the permission
  engine and user approval.
- If the preferred model is unavailable, emit an observable fallback event and use the default.
  Profile creation should not fail solely because Ollama is temporarily offline.

---

## 6. Task Approval in Chat

A `needsApproval` task gate is different from a tool-call approval. Both can share a UI component,
but the backend must distinguish their meaning.

### Persisted contract

Evolve approval storage into a typed contract:

- `approvalKind: TOOL | PLAN_TASK`
- `userId`, `chatId`, `runId`
- plan fields: `planId`, `taskId`
- `status`, `payload`, timestamps, and expiration

Keep the current tool methods and add explicit plan-task methods. Never keep a pending approval only
in memory.

### Coordination

Create an `ApprovalWorkflowService` focused on resolving an approval ID:

- `TOOL`: delegate to the current `AgentService`/orchestrator flow.
- `PLAN_TASK`: validate owner, plan, and task; approve the task; resume the plan.
- Task rejection keeps the plan `PAUSED` and task `BLOCKED` with a reason. The user may edit, skip,
  or request approval again; rejection does not cancel the full plan automatically.

This avoids making `LocalAiOrchestratorController` larger and prevents a circular dependency between
`AgentService` and `PlanExecutionService`.

### Frontend delivery and reconnection

- Publish the durable event on `plan_<id>` with `approvalId`, `planId`, `taskId`, summary, and
  `chatId`.
- `Home` subscribes to the selected chat's active plan stream and maps the event to the existing
  inline approval component.
- Add active-plan lookup by chat: `GET /api/plans/active?chatId=...`.
- Close the previous subscription on chat switch.
- On return or reload, query persisted pending approval state and rebuild the card without creating
  a duplicate approval.
- The plan panel displays `Waiting for approval in chat` and has no approval button.

After a task gate is approved, risky tools used by the task may still require their own approvals.

---

## 7. Concurrency and Memory Limits

The current plan executor is globally single-threaded inside one backend process. It prevents two
plans from executing tasks simultaneously in that process. It does not, by itself, serialize normal
chat, image generation, video generation, or a second backend instance.

For this phase:

- keep the existing bounded `core=1/max=1` executor;
- test two distinct plans with latches;
- do not add parallel agent execution;
- document that the lock is process-local.

A global provider-level `LocalModelExecutionGate` or semaphore is a later product decision because
it would also serialize interactive chat. Measure memory and latency before introducing it.

---

## 8. Architectural Acceptance Criteria

- [ ] The existing durable backbone was not duplicated.
- [ ] Profiles are isolated by user and exactly one default survives concurrent requests.
- [ ] Manual routing wins; automatic routing is deterministic and uses no additional inference.
- [ ] The trusted persona reaches the real backend system prompt.
- [ ] The tool allow-list is strict and independent from risk approval.
- [ ] Task approval is typed, persistent, appears in the correct chat, and survives reload/restart.
- [ ] Approval resumes exactly the intended task; rejection leaves a recoverable state.
- [ ] The chat card and side panel share one persisted plan ID; opening either surface never starts it.
- [ ] Only explicit approval transitions a plan from `DRAFT` to execution.
- [ ] Two plans cannot run tasks simultaneously in the same backend instance.
- [ ] Cross-user access tests cover profiles, assignment, and approvals.

## 9. Do Not

- Do not inject a persona only as a `system` message in the job payload.
- Do not use `requiredToolNames` as an allow-list without removing its permissive fallback.
- Do not deserialize a plan-task approval as `PendingToolExecution`.
- Do not use CSV for new persisted list fields.
- Do not force `IntentEmbeddingClassifier` into a dynamic profile-ranking domain.
- Do not claim machine-wide serialization based only on the plan executor.
- Do not rebuild verification, checkpoint, worker, outbox, or boot recovery.
- Do not alter local policies, commit, or push without explicit authorization.
