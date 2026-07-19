---
name: avento-finalize-change
description: Finish a completed Avento code, configuration, UI, infrastructure, or documentation change. Use when the requested behavior is implemented and must be validated, documented, reviewed for secrets and unrelated files, and committed semantically without pushing unless explicitly requested.
---

# Avento Finalize Change

1. Re-read the newest request and inspect `git status`, the focused diff, and recent commits.
2. Keep user-owned or unrelated changes unstaged. Never revert them to make the diff cleaner.
3. Run the narrowest relevant tests first, then the broader module checks warranted by the blast radius.
4. Update `README.md`, `docs/`, or `docs.html` when setup, architecture, behavior, API, operations, or UI changed.
5. Search the staged diff for credentials, tokens, personal paths, generated binaries, logs, and build output.
6. Confirm the result through the real boundary: API, UI, Redis stream, database, provider, or script.
7. Stage only the completed change and create one semantic commit matching the repository history.
8. Do not push, publish, or rewrite history unless the user explicitly asks.

Report the behavior delivered, validation commands, commit hash, and any residual risk. Do not commit a partially working change merely to close the task.
