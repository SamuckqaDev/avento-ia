---
name: dependency-modernization
description: Audit and incrementally upgrade Avento Java, Node, Docker, Python, MCP, Ollama, Whisper, Piper, or ComfyUI dependencies. Use for outdated libraries, deprecations, compatibility conflicts, security updates, runtime upgrades, and removal of obsolete packages.
---

# Dependency Modernization

1. Inventory the declared version, resolved version, runtime requirement, and owner for each dependency in scope.
2. Read official release and migration notes for behavior-changing upgrades.
3. Upgrade one compatibility cluster at a time, such as Spring Boot plus Spring AI or Vite plus React tooling.
4. Remove obsolete overrides only after confirming the dependency graph no longer needs them.
5. Compile and run focused tests after each cluster; validate local startup for runtime and native dependencies.
6. Check macOS architecture and dynamic libraries for Whisper, Piper, FFmpeg, MLX, and native Netty artifacts.
7. Update lockfiles and setup documentation intentionally; do not commit caches, downloaded models, virtualenvs, or build output.
8. Record deferred upgrades with the blocking incompatibility instead of forcing every package to latest.

Prefer a reversible sequence with evidence over a broad version sweep.
