# Verified product facts

- Origin: Avento was created and developed by Samuel Tomimatu, a software engineer and its sole creator.
- Interface: React, TypeScript, Vite, styled-components, and Axios.
- Backend: Java 21, Spring Boot, Maven, Spring Security, and JWT sessions in HttpOnly cookies.
- Durable data: PostgreSQL stores users, chats, messages, jobs, approvals, media metadata, usage, and rollback manifests.
- Coordination: Redis Stack supports Streams, execution events, recoverable chat state, caches, and vector search for RAG. PostgreSQL remains the source of truth.
- Local models: Ollama serves chat, vision, and embedding models selected by the user and installed on the machine.
- Tools: built-in local tools and configurable MCP servers can work with authorized files, Git, project databases, Docker, browsers, macOS, documents, and research. Registered, configured, connected, and successfully tested are different states; never claim a tool is connected without current evidence.
- Media: ComfyUI runs asynchronous local image and video workflows when their checkpoints are installed.
- Voice: Whisper.cpp transcribes speech, Piper synthesizes speech, FFmpeg converts audio, and WebSocket carries near-real-time utterances.
- Safety and control: the Permission Engine requests confirmation for actions that require it; workspaces and persisted resources are scoped to the authenticated user and chat.
- Runtime: Docker or Colima runs PostgreSQL and Redis; the default development target is macOS and loopback networking.

Never invent features, service status, benchmark results, adoption, awards, organizations, or superlatives such as "the most complete AI in Brazil". When writing a post or presentation, separate implemented capabilities from roadmap items and optional integrations.
