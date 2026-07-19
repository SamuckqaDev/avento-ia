---
name: release-readiness
description: Audit Avento before a public release, tag, deployment, or GitHub publication. Use for the complete release gate across backend, frontend, Docker, migrations, local AI dependencies, MCP setup, docs, secrets, licenses, smoke tests, and repository cleanliness.
---

# Release Readiness

1. Define the release scope from commits and user-visible changes; identify migrations and compatibility requirements.
2. Run backend tests, frontend lint/build, scripts validation, Docker config validation, and authenticated smoke tests.
3. Start from a clean local state and verify PostgreSQL, Redis, Ollama, ComfyUI, MCP discovery, auth, one chat response, document upload, and configured media/voice paths.
4. Review migrations, defaults, environment variables, ports, filesystem paths, retention, and upgrade instructions.
5. Scan tracked files and history in scope for secrets, personal data, large models, generated media, caches, and obsolete branding.
6. Confirm README and HTML documentation explain architecture, setup, optional dependencies, troubleshooting, and current limitations.
7. Inspect dependency licenses and security findings that affect public distribution.
8. Require a clean focused diff and semantic commits. Tag or push only when the user explicitly approves publication.

Produce a pass/fail checklist with commands and evidence. A warning that can break startup, auth, data, or core chat blocks release.
