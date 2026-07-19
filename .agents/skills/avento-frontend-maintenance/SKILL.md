---
name: avento-frontend-maintenance
description: Implement, refactor, review, or diagnose the Avento React and TypeScript frontend. Use for responsive header and sidebars, chat streaming, per-chat state, media panels, Axios authentication, accessibility, mobile layouts, notifications, and modern visual consistency.
---

# Avento Frontend Maintenance

1. Inspect the existing component, styled-component, hook, API contract, and responsive behavior before editing.
2. Keep server state and transport logic in hooks/services; keep components focused on rendering and interaction.
3. Use the shared Axios client for ordinary API calls and include credentials. Use `fetch` only where browser streaming requires it.
4. Scope generating, thinking, approval, media, and error state by `chatId` and `runId`; switching chats must not move an active run.
5. Build responsive layouts with flex/grid constraints, stable control sizes, overflow boundaries, and no viewport-edge popovers.
6. Use existing icons, tokens, and visual language. Preserve keyboard access, labels, focus, reduced motion, and readable contrast.
7. Render loading, empty, error, offline, cancelled, and completed states explicitly; never leave an endless spinner after a terminal error.
8. Run TypeScript/build tests and verify desktop plus narrow mobile behavior with screenshots when layout changed.

Do not hide backend contract failures with frontend-only state resets.
