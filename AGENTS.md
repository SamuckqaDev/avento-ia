# Avento Repository Instructions

## Java changes

For every Java or Spring task, read and follow
`.agents/skills/avento-java-maintenance/SKILL.md` before analyzing or editing code. Its detailed
standard in `references/java-standards.md` is mandatory for architecture and review decisions.

Core expectations:

- use layered dependency direction and DTOs at external boundaries;
- prefer immutable DTO records and careful Lombok usage;
- inject final dependencies through constructors, normally with `@RequiredArgsConstructor`;
- keep controllers thin and services cohesive;
- split large services by real capability or use case;
- preserve domain behavior while removing proven dead code;
- validate, document, and commit completed changes semantically.

Do not perform broad mechanical refactors outside the requested scope. Record larger findings as
technical debt and handle them in a dedicated change with focused tests.

## Specialized changes

Read the matching repository skill before working in these areas:

- `.agents/skills/spring-security-maintenance/` for auth, JWT cookies, sessions and permissions;
- `.agents/skills/database-migration/` for schema, Flyway and destructive data operations;
- `.agents/skills/mcp-integration-maintenance/` for MCP lifecycle, tools and workspace scope;
- `.agents/skills/async-execution-diagnostics/` for Redis jobs, workers, SSE and stuck runs;
- `.agents/skills/avento-frontend-maintenance/` for React, responsive UI and per-chat state;
- `.agents/skills/media-pipeline-maintenance/` for ComfyUI image and video generation;
- `.agents/skills/voice-pipeline-maintenance/` for Whisper, Piper and browser playback;
- `.agents/skills/rag-knowledge-maintenance/` for ingestion, embeddings and retrieval;
- `.agents/skills/dependency-modernization/` for dependency upgrades;
- `.agents/skills/avento-finalize-change/` when a change is ready to validate and commit;
- `.agents/skills/release-readiness/` before publication or release.
