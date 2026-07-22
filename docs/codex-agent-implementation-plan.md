# Surgical Implementation Plan - Specialized Agents

> **Primary implementation document.** Read `docs/agent-corrections-plan.md` first. Preserve the
> foundation described in `docs/autonomous-agent-plan.md`. Run the audit in
> `docs/codex-review-plan.md` after implementation.
>
> This plan was checked against the current code. Do not rebuild `PlanExecutionService`,
> verification/checkpoint/revert, worker/outbox, cancellation, or boot recovery.

---

## 0. Preparation and Baseline

1. Record `git status` and separate pre-existing changes from this phase. Do not revert local work,
   commit, or push unless explicitly requested.
2. Validate the backend:

   ```bash
   cd back/avento
   mvn -q compile
   mvn test
   mvn spotless:check
   ```

   Last documented reference: 401 tests, zero failures, and one skipped test. The test count may
   grow; failures may not.
3. Validate the frontend:

   ```bash
   npm --prefix front run test
   npm --prefix front run typecheck
   npm --prefix front run lint
   npm --prefix front run build
   ```

   Last documented reference: seven passing frontend tests.
4. Confirm these code facts before editing:
   - `PlanExecutionConfig` already uses `corePoolSize=1` and `maxPoolSize=1`.
   - `PlanExecutionService.taskPayload(...)` still sends a generic system message and blank model.
   - `AgentRunWorker` reads model/messages/workspace roots but no profile execution context.
   - `AgentService.compactMessagesForModel(...)` discards incoming system messages.
   - `AgentService.withBackendIdentityPrompt(...)` creates the trusted system prompt.
   - `PendingToolApprovalService` persists only `PendingToolExecution`.

If the baseline is broken, fix or report that cause before mixing in this feature.

---

## 1. Phase A - `AgentProfile` Persistence and CRUD

### A1. Entity and list serialization

Create `model/AgentProfile.java` using the project's Lombok/JPA conventions:

- `id`, `userId`, `name`, `specialty`, `systemInstructions`, `allowedTools`, `triggers`, `model`,
  `isDefault`, `createdAt`, and `updatedAt`.
- Add indexes on `(user_id, updated_at)` and `(user_id, is_default)`.
- Persist lists as JSON in `TEXT` through a reusable `AttributeConverter<List<String>, String>` or
  centralized Jackson helper. DTOs remain strongly typed as `List<String>`.
- Do not implement CSV or ad hoc `split(",")` parsing.

Create enums/constants only where they remove magic strings. Use normal imports, constructor
injection, and small focused classes.

### A2. Repository

Create `repository/AgentProfileRepository.java` with owner-scoped queries:

```java
List<AgentProfile> findByUserIdOrderByUpdatedAtDesc(UUID userId);
Optional<AgentProfile> findByIdAndUserId(Long id, UUID userId);
Optional<AgentProfile> findFirstByUserIdAndIsDefaultTrue(UUID userId);
List<AgentProfile> findByUserId(UUID userId);
```

Add a `@Lock(PESSIMISTIC_WRITE)` query for the transaction that creates or changes the default.

### A3. DTOs and validation

Create in `api.dto`:

- `AgentProfileCreateRequest`
- `AgentProfileUpdateRequest`
- `AgentProfileResponse`

Apply length limits to name, specialty, and instructions; bound list sizes; keep model optional; and
make `isDefault` explicit. Never accept `userId` from the body.

### A4. Normalization and service boundaries

Separate responsibilities:

- `AgentProfileInputNormalizer`: trim, remove blanks, and deduplicate lists with `LinkedHashSet`.
- `AgentToolPolicyValidator`: resolve aliases to canonical names and validate them against the tool
  registry.
- `AgentProfileService`: ownership, CRUD, transactions, and default-profile invariants.

Required behavior:

- `resolveDefault(userId)` locks the user's profiles and returns the default or creates
  `Generalist`.
- Marking another profile as default clears the previous default in the same transaction.
- Deleting the default promotes another profile deterministically or creates `Generalist`.
- If the profile is assigned to a `RUNNING` task, return a conflict.
- For `PENDING` or `BLOCKED` tasks in draft/paused plans, clear the assignment so routing can run
  again.

### A5. Controller

Create `controller/AgentProfileController.java` under `/api/agents`:

- `GET /api/agents`
- `GET /api/agents/{id}`
- `POST /api/agents`
- `PUT /api/agents/{id}`
- `DELETE /api/agents/{id}`

Every method uses `@AuthenticationPrincipal AuthPrincipal`, `@Valid`, `BaseResponse<T>`, and
`ApiResponses`. Validation/not-found/conflict errors use the existing `ControllerAdvice` contract.

### A6. Phase tests

- CRUD and JSON list mapping.
- User A cannot read, edit, or delete user B's profile.
- Two concurrent default changes finish with exactly one default.
- Deleting the default promotes or creates a replacement.
- Unknown tool names are rejected.
- Deleting an agent assigned to a `RUNNING` task returns a conflict.

---

## 2. Phase B - Assignment and Routing

### B1. Task fields

Add to `model/AgentTask.java`:

- `assignedAgentId`
- `agentRationale`

Keep a logical foreign key if that matches the module's current pattern. Do not introduce an eager
JPA relation to `AgentProfile`. Update `TaskResponse` and its mapper.

### B2. Dedicated assignment endpoint

Create `AgentAssignmentRequest` and:

```text
PUT /api/plans/{planId}/tasks/{taskId}/agent
```

- With `agentId`: validate profile and task ownership, save the manual assignment, and record a
  rationale.
- With `agentId=null`: clear the manual choice and run automatic routing.
- Reject changes while the task is `RUNNING`; allow them in draft/paused/pending/blocked states.

Do not turn the existing full `TaskUpdateRequest` into an ambiguous partial-update contract.

### B3. `AgentRoutingService`

Implement routing without model inference:

1. valid manual assignment;
2. normalized trigger score over `title`, `details`, and `targetFiles`;
3. lower-weight specialty score;
4. deterministic tie-break;
5. default-profile fallback.

Do not use `IntentEmbeddingClassifier` in this phase. Extract scoring into a pure testable component,
for example `AgentProfileScoreCalculator`.

### B4. Builder integration

After `PlanBuilderService` has validated and persisted tasks, route each task and save
`assignedAgentId` and `agentRationale`. A bad profile must not leave a partially created plan; use a
transaction and default fallback.

### B5. Phase tests

- Manual assignment wins over automatic routing.
- Trigger match wins over specialty match.
- Tie-break is stable.
- No match uses the default.
- A profile from another user is never selected.
- `agentId=null` reroutes.
- Assignment changes during execution are rejected.

---

## 3. Phase C - Persona, Model, and Tool Allow-List

### C1. Internal execution contract

Create `AgentExecutionOptions` in the execution package:

- `agentProfileId`
- `trustedSystemInstructions`
- `allowedToolNames` as an ordered immutable `Set<String>`
- `preferredModel`

This contract is backend-internal. Do not expose an arbitrary system-prompt field in the chat API.

### C2. Durable job snapshot

In `PlanExecutionService.taskPayload(...)`:

1. Resolve and validate the assigned profile.
2. Preserve the existing partitioned task message.
3. Add an `agentExecution` object to the payload containing the normalized profile ID,
   instructions, model, and tools.
4. Log only profile ID, model, and tool count. Do not log complete private instructions.

The snapshot keeps execution reproducible if the profile is edited after job submission.

### C3. Typed propagation

- `AgentRunWorker`: parse `agentExecution` into a record/mapper instead of spreading raw
  `JsonNode.path(...)` calls.
- `AgentExecutionEngine`: add a typed overload/parameter and forward it.
- `AgentService`: initialize `AgentRunState` with trusted execution context separate from user
  messages.

Preserve existing overloads as adapters that use `AgentExecutionOptions.defaults()` to limit blast
radius.

### C4. Real system-prompt integration

Change the backend identity helper to compose clearly delimited blocks:

1. permanent Avento identity and policy;
2. validated profile persona;
3. current task and authorized-workspace constraints.

Do not depend on the job's `system` message because history compaction removes it. Capture the final
provider request in a test and assert that the persona is present there.

### C5. Strict tool allow-list

Separate these concepts in `AgentRunState`:

- `requiredToolNames`: tools required by a skill/current round;
- `allowedToolNames`: maximum tool set defined by the profile.

Selection must follow:

```text
eligible = normal request-based selection
eligible = intersection(eligible, allowedToolNames) when the allow-list is non-empty
required = intersection(requiredToolNames, eligible)
result = required when applicable; otherwise eligible
```

An empty intersection returns zero tools and emits a clear event/message. It never restores the full
catalog. The permission engine still runs after the allow-list.

### C6. Preferred model

- Blank value uses the currently resolved default model.
- Available preferred model uses the profile value.
- Unavailable preferred model emits `agent.model.fallback` and uses the default. Never invent an
  alternative model name.

### C7. Phase tests

- Persona appears in the actual provider system prompt.
- A user-supplied system prompt remains ignored.
- Empty allow-list preserves current behavior.
- Non-empty allow-list is strict.
- Unknown names never reopen all tools.
- An allowed risky tool still requires approval.
- Editing a profile after submission does not mutate the durable snapshot.
- Model fallback emits an event and uses the default.

---

## 4. Phase D - Proven Serialization

Do not change `PlanExecutionConfig` values when they remain `core=1`, `max=1`, and bounded.

Add a two-plan `CountDownLatch` test:

1. Plan A enters execution and blocks on a latch.
2. Plan B is submitted.
3. Assert that B has not started.
4. Release A and assert that B starts afterward.

Document that this serializes plans **inside one backend instance**. It does not serialize every
Ollama/ComfyUI workload or multiple backend replicas. Do not introduce a global semaphore without a
memory measurement and product decision.

---

## 5. Phase E - Persistent Approval in Chat

### E1. Generalize persisted approvals

Preserve tool-call compatibility and add:

- enum `ApprovalKind { TOOL, PLAN_TASK }`;
- `approval_kind`, `plan_id`, and `task_id` columns;
- explicit `saveToolApproval(...)` and `savePlanTaskApproval(...)` methods;
- resolution methods always scoped by `userId`;
- idempotency: reuse an existing pending approval for the same task.

Never deserialize a `PLAN_TASK` approval into `PendingToolExecution`.

### E2. `ApprovalWorkflowService`

Move type-specific orchestration out of the controller:

- `approve(userId, approvalId, adjustment)`
- `reject(userId, approvalId, reason)`

For `TOOL`, preserve the current flow. For `PLAN_TASK`, validate ownership, call
`AgentPlanService.approveTask(...)`, close the approval, and resume through
`PlanExecutionService.run(...)`. Repeating the same approval request must be idempotent and must not
execute the task twice.

Task rejection records the reason and leaves the task `BLOCKED` and plan `PAUSED`.

### E3. Durable event and restoration

When `needsApproval` blocks a task:

1. mark the task `BLOCKED` and plan `PAUSED`;
2. persist a `PLAN_TASK` approval;
3. publish `plan.approval.required` on `plan_<id>` with a minimal payload;
4. expose pending approval in the active-plan/detail response.

Add optional chat filtering to `GET /api/plans/active?chatId={chatId}`. Validate that the chat belongs
to the authenticated user.

### E4. Frontend behavior

In `Home`:

- discover the selected chat's active plan;
- subscribe to that plan's SSE stream;
- map `plan.approval.required` to the existing `ApprovalCard` state;
- unsubscribe on chat change;
- rebuild the card from persisted state after return/reload;
- remove it when resolved without creating a new approval.

In `PlanExecutionPanel`, remove the task-approval button and show only
`Waiting for approval in chat`.

### E5. Phase tests

- Tool and plan-task approvals never resolve as the wrong type.
- User B cannot approve/reject user A's request.
- Reload/restart restores pending approval.
- Approval resumes exactly once.
- Duplicate calls are idempotent.
- Rejection leaves a recoverable plan.
- The event is tied to the correct chat and plan.

---

## 6. Phase F - Consolidated UX and Agent Management

### F1. Bind the chat card to the persisted draft

- Keep `ImplPlanCard` as the mandatory review/approval surface in chat.
- When the assistant finishes an `impl-plan` block, create one backend plan in `DRAFT` and persist its
  ID in the assistant message through an internal marker.
- Clicking the card opens `PlanExecutionPanel` focused on that exact plan ID. It must not execute.
- `Approve and execute` calls the plan run endpoint; it must not send a synthetic chat message that
  asks the model to implement free-form text.
- The side panel shows the full ordered task list, affected files, verification, and assigned/default
  agent before approval.

### F2. Plan panel

- Load profiles once and map profile IDs to names.
- Display an agent dropdown for every task and disable it during `RUNNING`.
- Call the dedicated assignment endpoint with per-task loading/error state.
- Preserve SSE, pause, resume, and cancel.
- For `BLOCKED`, state that approval is waiting in chat.

### F3. Agent management

Create `services/agentApi.ts` and a CRUD surface consistent with the existing frontend:

- searchable scrollable list;
- create/edit name, specialty, instructions, triggers, model, and tools;
- tool multi-select populated by the backend catalog, never a free-form CSV field;
- default-profile action with clear conflict feedback;
- deletion confirmation and a specific running-task conflict message.

Use the shared `apiClient` with cookie authentication. Do not store tokens in local storage or create
an independent Axios client.

### F4. Phase tests

- `planApi` and `agentApi` unwrap `BaseResponse` consistently.
- Agent selection updates only the intended task.
- The card and panel show the same persisted plan ID and opening the card does not execute it.
- No run endpoint is called before explicit approval.
- Approval reappears after returning to the chat and disappears after resolution.
- Rapid chat switching cannot deliver an event to the wrong chat.

---

## 7. Delivery Order

Complete and validate each phase before starting the next:

1. Phase A - profiles and data invariants.
2. Phase B - routing and assignment.
3. Phase C - trusted context and tool limits.
4. Phase D - serialization proof.
5. Phase E - persistent approvals and reconnection.
6. Phase F - UX consolidation.
7. Documentation and final audit.

When commits are authorized, keep them semantic and phase-focused. Do not mix a broad
`AgentService` refactor with visual changes in one commit.

---

## 8. Final Validation

```bash
cd back/avento
mvn spotless:apply
mvn -q compile
mvn test

cd ../..
npm --prefix front run test
npm --prefix front run typecheck
npm --prefix front run lint
npm --prefix front run build
git diff --check
```

Minimum manual scenario:

1. Create `Java Backend` and `React Frontend`; confirm there is exactly one default.
2. Create a mixed plan and inspect routing/rationale.
3. Manually change one task's agent.
4. Execute and confirm one task runs at a time.
5. Confirm through logs/provider mock that persona, model, and allow-list arrived.
6. Block a task, switch chats, reload, return, and approve inline in chat.
7. Confirm a single resume and an independent approval for any later risky tool.
8. Attempt cross-user access to a profile, plan, and approval; confirm denial.

---

## 9. Acceptance Criteria

- [ ] Existing execution behavior was extended, not rebuilt.
- [ ] Agent CRUD, concurrent default handling, and safe deletion are covered.
- [ ] Routing is deterministic, explainable, and requires no extra inference.
- [ ] Persona reaches the trusted backend prompt channel.
- [ ] Tool allow-list is strict and does not replace the permission engine.
- [ ] Preferred model has an observable fallback.
- [ ] Two plans cannot execute concurrently in one backend instance.
- [ ] Task approval is typed, persistent, idempotent, and shown in the correct chat.
- [ ] Reload, restart, and chat switching do not lose or duplicate approval.
- [ ] `ImplPlanCard` is a compact gate and the panel is its detailed view of the same persisted plan.
- [ ] A `DRAFT` plan cannot start merely because the card or panel was opened.
- [ ] Backend and frontend pass final validation.

## 10. Report for Claude

Provide:

1. baseline before and after;
2. files changed by phase;
3. evidence for ownership, serialization, strict allow-list, and idempotent approval tests;
4. one example of the payload -> worker -> engine -> service flow;
5. unresolved risks or product decisions;
6. confirmation that no push or policy-file versioning occurred without authorization.

## 11. Do Not

- Do not trust a system prompt supplied through the payload/frontend.
- Do not restore all tools after an empty allow-list intersection.
- Do not combine plan and tool approvals without `ApprovalKind`.
- Do not keep critical state only in React or in-memory maps.
- Do not create a second queue, verifier, or rollback mechanism.
- Do not change `num_ctx`, policies, or content behavior in this phase.
- Do not commit or push without explicit authorization.
