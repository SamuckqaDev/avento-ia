# Codex Review and Correction Plan

> **Workflow position:** Antigravity implements `docs/autonomous-agent-plan.md` -> Codex audits and
> fixes -> Codex implements specialized agents through `docs/codex-agent-implementation-plan.md` ->
> Claude performs the final review.
>
> **Role:** act as a senior reviewer and fixer. Do not reimplement working architecture. Audit the
> invariants, correct confirmed defects, preserve user work, and produce evidence.

---

## 0. Review Rules

- Do not push. Create local commits only when explicitly requested.
- Fix low-risk confirmed defects directly: broken invariants, obvious bugs, dead code, formatting,
  and missing tests.
- Report product/model decisions instead of changing them silently, especially `num_ctx`,
  `num_predict`, and behavior that changes model output.
- Finish with backend tests, frontend validation, and Spotless green.
- Never version `src/main/resources/agent/policies/*.md`.
- Do not revert unrelated user changes in a dirty worktree.

---

## 1. Establish the Baseline

1. Run `git status` and `git log --oneline -20`. Identify the autonomous-agent changes, older memory
   and history work, and any local changes that are not part of this review.
2. Run:

   ```bash
   cd back/avento
   mvn -q compile
   mvn test
   ```

   Use the most recent local reference. The last documented result was 401 tests, zero failures, and
   one skipped test. Fix baseline failures before reviewing new behavior.
3. Run `npm --prefix front run validate` from the repository root.
4. Record the green state. No correction may leave it red.

---

## 2. Autonomous-Agent Specification Compliance

Check the implementation point by point against `docs/autonomous-agent-plan.md`:

- [ ] `AgentPlan` and `AgentTask` exist with effective non-null ownership and user indexes.
- [ ] `PlanBuilderService` creates a bounded ordered structured task list, not loose text, and handles
      malformed model output safely.
- [ ] `PlanExecutionService` executes one task at a time and, between tasks, verifies, checkpoints,
      retries within a bound, and stops on failure.
- [ ] Approval gates and cancellation work.
- [ ] Every `/api/plans` endpoint derives ownership from `AuthPrincipal`.
- [ ] SSE progress is durable/recoverable rather than tied only to component state.
- [ ] The frontend shows a live task list and start/pause/resume/cancel controls.
- [ ] Context partitioning prevents a task from loading the entire project or conversation.

Correct incomplete items or identify them as blockers in the final report.

---

## 3. Critical Invariants

### 3.1 User isolation

Actively search for raw `findById` calls over `AgentPlan`, `AgentTask`, `AgentProfile`, and
`UserMemory`. User-owned resources must be fetched with owner-scoped queries.

- Every controller derives `userId` from `AuthPrincipal`, never from the request body.
- Every mutating service method receives or derives trusted ownership.
- A response, event stream, approval, or repository query cannot expose another user's resource.
- Add cross-user tests where they are missing.

### 3.2 Context partitioning

- Each task receives only task title/details, relevant files, a compact progress summary, and the
  required/allowed tools.
- Search for prompt builders that concatenate a full plan, repository, or raw history.
- Confirm prompt-size estimation exists and does not log private content.
- Confirm the current user request is never truncated by history compaction.

### 3.3 Guardrails and loops

- Confirm verification, checkpoint, rollback, approval, stop-on-failure, and retry limits.
- Look for infinite retry paths or tasks that remain `RUNNING` after exceptions.
- Confirm cancellation transitions jobs/tasks/plans to consistent final states.

### 3.4 Concurrency and durability

- Confirm boot recovery is idempotent.
- Confirm one plan task cannot execute twice after a retry, reload, or worker restart.
- Confirm shared sets/maps are thread-safe and bounded.
- Verify executor shutdown and resource cleanup.
- Distinguish process-local plan serialization from machine-wide inference serialization.

### 3.5 Reuse versus duplication

If new code duplicates durable execution, outbox, verification, backup, rollback, or cancellation,
consolidate it into the existing components instead of maintaining parallel implementations.

---

## 4. Known Risk Areas

1. **Model memory:** `num_ctx=16384` and `num_predict=4096` may cause swap on a 16 GB machine.
   Measure a real request and report a recommendation; do not change it automatically.
2. **Long-term memory:**
   - exact-match deduplication does not catch near-duplicates or contradictions;
   - extracted facts must be self-contained and avoid unresolved pronouns;
   - new writes must always have an owner, even if legacy rows allow null;
   - extraction must remain throttled, asynchronous, and configurable.
3. **History summary:** verify that discarded turns are summarized accurately and the current request
   is always retained.
4. **Stream finalization:** memory extraction must run only after successful completion, never block
   the stream, and handle a null chat safely.
5. **API consistency:** controllers use `BaseResponse<T>`, `ApiResponses`, validated DTOs, and the
   centralized exception handler. Frontend calls use the shared `apiClient`.
6. **Security:** no unauthenticated new endpoint, hardcoded secret, sensitive query parameter, or
   versioned local environment/policy file.

---

## 5. Code Hygiene

- Remove dead code, unused imports, orphaned methods, and stale compatibility paths only after
  proving they are unused.
- Keep services focused. Extract helpers/coordinators when a class owns unrelated workflows.
- Prefer constructor injection, Lombok where it reduces boilerplate, DTO boundaries, and normal
  imports.
- Add focused tests proportional to behavior and risk.
- Run `mvn spotless:apply` before final validation.

---

## 6. Specialized-Agent Review

Audit against `docs/agent-corrections-plan.md` and
`docs/codex-agent-implementation-plan.md`.

### 6.1 Profiles and ownership

- [ ] `AgentProfile` is never fetched by ID without `userId`.
- [ ] DTOs use lists and persistence does not depend on CSV.
- [ ] `allowedTools` is validated against the real registry.
- [ ] Exactly one default survives concurrent requests.
- [ ] Profile deletion cannot orphan an active task.

### 6.2 Routing

- [ ] Manual choice wins over automatic routing.
- [ ] Routing is deterministic, explainable, and does not call another model.
- [ ] `IntentEmbeddingClassifier` was not forced into dynamic profile ranking.
- [ ] Profiles and tasks from different users can never be associated.

### 6.3 Trusted context and tools

- [ ] Persona reaches the system prompt created by the backend; it does not depend on an incoming
      system message.
- [ ] The frontend cannot submit an arbitrary privileged persona.
- [ ] `allowedToolNames` is separate from `requiredToolNames` and uses strict intersection.
- [ ] An empty intersection never reopens all tools.
- [ ] The permission engine still applies to allowed tools.
- [ ] A durable profile snapshot makes queued execution reproducible.

### 6.4 Approval and reconnection

- [ ] Approval has `TOOL` or `PLAN_TASK` type with no cross-deserialization.
- [ ] Approval is persistent, owner-scoped, and idempotent per task.
- [ ] Approval appears in the chat associated with its plan.
- [ ] Reload, restart, and chat switching do not lose or duplicate the card.
- [ ] Approval resumes exactly once; rejection leaves a recoverable plan.
- [ ] The plan panel does not keep a second approval button.

### 6.5 Concurrency and UX

- [ ] A two-plan test proves serialization inside one backend instance.
- [ ] Documentation does not claim global exclusion over chat, image, video, or multiple instances.
- [ ] `ImplPlanCard` and `PlanExecutionPanel` reference the same persisted plan ID.
- [ ] Opening the card only opens the side panel; it never calls the run endpoint.
- [ ] Explicit approval is the only `DRAFT` to execution transition exposed by the frontend.
- [ ] Full details live in the side panel and agent selection is disabled during `RUNNING`.

---

## 7. Final Validation

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

No test should be deleted, weakened, or skipped to make the suite green.

---

## 8. Report for Claude's Final Review

Provide:

1. baseline before and after, including exact test counts;
2. what was already correct, what Codex fixed, and what remains blocked;
3. findings and corrections around ownership, partitioning, concurrency, and durability;
4. risks that require a user/product decision, including measured model memory;
5. files changed, grouped by purpose;
6. evidence for concurrent default handling, deterministic routing, trusted prompt injection, strict
   allow-list, persistent/idempotent approval, and chat switching;
7. confirmation that no push or local-policy versioning occurred without authorization.

## 9. Do Not

- Do not change model limits or output behavior without evidence and approval.
- Do not weaken existing tests to accommodate a defect.
- Do not mix user context or relax owner-scoped access.
- Do not recreate durable execution, verification, or rollback.
- Do not version local policy files or push without explicit authorization.
