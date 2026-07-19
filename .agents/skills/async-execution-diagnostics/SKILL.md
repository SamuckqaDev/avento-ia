---
name: async-execution-diagnostics
description: Diagnose and repair Avento runs that remain queued, thinking, streaming, or incomplete. Use for PostgreSQL agent jobs, transactional outbox, Redis Streams, consumer groups, pending entries, workers, run events, SSE, cancellation, retries, dead letters, and per-chat frontend isolation.
---

# Async Execution Diagnostics

Trace one `runId` end to end before changing code:

1. Confirm the frontend received a submission and opened `/api/ai/runs/{runId}/events` with the auth cookie.
2. Inspect `agent_run_jobs` and `execution_outbox_events` for status, attempts, timestamps, and error text.
3. Inspect `avento:jobs:agent`, its consumer group, pending entries, lag, and `avento:dead-letter`.
4. Correlate `runId`, `chatId`, `jobId`, and `userId` in backend logs without dumping prompts or tokens.
5. Inspect run events in order and distinguish model thinking, visible deltas, tool requests, approvals, terminal events, and transport disconnects.
6. Reproduce with one short text request before testing tools or media.
7. Fix idempotency, stale pending recovery, event isolation, timeout, or UI state at the owning layer.
8. Add tests for the exact stuck state and a real smoke path that reaches a terminal response.

Do not purge queues as the default fix. Preserve evidence until the failed run is understood.
