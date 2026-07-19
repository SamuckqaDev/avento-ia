---
name: mcp-integration-maintenance
description: Add, diagnose, review, or modernize Avento Model Context Protocol integrations. Use for MCP server catalog entries, SDK clients, tool discovery, resources, prompts, scopes, workspace roots, permissions, lifecycle, timeouts, reconnects, and tool result verification.
---

# MCP Integration Maintenance

1. Confirm the capability belongs in MCP and identify the server, transport, protocol version, executable, environment, and local/free requirements.
2. Register configuration in the catalog instead of hardcoding server-specific branches in orchestration.
3. Start the replacement client and complete initialize, capability discovery, and health checks before disconnecting a working client.
4. Preserve JSON Schema for tool inputs and structured results. Adapt provider contracts at the MCP boundary.
5. Restrict filesystem, Git, database, and desktop tools to authorized roots and validate scope again in the backend.
6. Route risky actions through the Permission Engine; read-only discovery must not silently become mutation.
7. Bound startup, calls, and shutdown with timeouts and actionable logs that omit secrets.
8. Verify a real list-tools and call-tool round trip, reconnect behavior, and failure reporting. Never claim a tool ran from model text alone.

Prefer official SDK behavior and protocol capabilities over prompt-only routing or hand-written MCP wire messages.
