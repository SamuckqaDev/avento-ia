---
name: spring-security-maintenance
description: Diagnose, implement, review, or test Avento authentication and authorization. Use for Spring Security, JWT cookies, login, refresh, logout, session revalidation, CSRF, CORS, ownership, permissions, token audit, and development versus production cookie behavior.
---

# Spring Security Maintenance

1. Trace the request through `SecurityConfig`, the JWT cookie filter, controller DTOs, auth service, session repository, and frontend Axios client.
2. Treat PostgreSQL session state as durable truth. Never expose or persist raw passwords or token values in logs.
3. Keep the access token in the configured HttpOnly cookie; do not introduce browser storage as a second authentication source.
4. Make login, refresh, session validation, and logout explicit state transitions. Logout must revoke server state and clear cookies without triggering revalidation UI.
5. Validate ownership and permissions in the backend for every protected resource.
6. Keep development cookie settings usable over local HTTP while preserving configurable production `Secure` and `SameSite` settings.
7. Use request and response DTOs, constructor injection, focused exception mapping, and auditable security events.
8. Test valid, expired, revoked, missing, malformed, and cross-user cases, including frontend refresh and logout races.

Do not weaken authentication to hide a failing test. Fix the contract at the layer that owns it.
