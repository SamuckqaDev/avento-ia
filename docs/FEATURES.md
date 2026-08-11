<p align="center">
  <a href="FEATURES.md"><img src="https://img.shields.io/badge/lang-English-2b7a78?style=for-the-badge" alt="English"></a>
  <a href="FEATURES.pt-BR.md"><img src="https://img.shields.io/badge/idioma-Portugu%C3%AAs-lightgrey?style=for-the-badge" alt="Português"></a>
</p>

# Features

The full inventory of what works in Avento today.

- Persisted conversations, isolated per user.
- Asynchronous agent execution with PostgreSQL, Outbox, Redis Streams, a worker, and authenticated SSE.
- Idempotent execution and approval: duplicate Redis entries never repeat tools or re-present decisions that were already resolved.
- An activity watchdog ends silent runs without leaving the chat or the single local worker stuck indefinitely.
- Recent context is cached in Redis and rebuildable; PostgreSQL remains the durable source of truth.
- Per-conversation isolated, recoverable streaming: switching chats or reloading the page restores processing from the run's durable state, without moving Thinking, response, media, or voice into another conversation.
- Reasoning from hybrid models (qwen3, etc.) is routed explicitly to the interface's Thinking block instead of relying on Ollama's default; the model stays loaded between messages to cut reload latency.
- A per-run context window with a predictable ceiling: the compacted tool history stays bounded regardless of how many rounds the task takes, avoiding blowing past the model's `num_ctx` on long analyses.
- A failure to load history when switching chats shows an explicit notice with automatic retry instead of rendering the conversation as empty; the messages remain intact in PostgreSQL.
- The task panel opens once when a plan appears and respects a manual close for the rest of the execution.
- Autonomous plans persist ordered tasks per chat, run them one at a time through the durable Redis backbone, verify each workspace, and resume idempotently after a backend restart.
- Chat model and visual-generation model selection in the header.
- Profile avatars are stored as validated PNG, JPEG, WebP, or GIF data on the logged-in user's account (512 KiB maximum); `/api/auth/me` exposes only `hasAvatar`, while dedicated authenticated routes upload and serve the image.
- The selected model, voice, and image preferences use one-year browser-readable cookies with `SameSite=Lax` and `Path=/`; only the theme stays in localStorage, and `autoApproveAll` remains server-owned through `/api/settings`.
- Token usage tracking per model, day, and chat, with a visual metrics dashboard.
- Analysis of stack, scripts, entrypoints, and workspace structure.
- Read, create, edit, search, and delete of authorized files.
- Direct attachment of PDF, Office, EPUB, ZIP, text, and code in the chat, with local extraction via plain text or MarkItDown and context persisted in the conversation.
- Action approval through the interface or by voice commands.
- MCP integration with Git, databases, Docker, filesystem, browser, and macOS.
- Querying the database discovered in the active project, including inside Docker.
- Asynchronous image generation via ComfyUI with RealVisXL SDXL, structural or identity reference, pose control, visual review, progress, estimate, cancellation, and parameters adjustable in the frontend.
- Automatic translation of the prompt to English before SDXL (CLIP only understands English) and per-model generation presets (sampler, steps, CFG, and resolution tuned to each checkpoint), overridable by a local file without recompiling.
- Video generation via ComfyUI with WAN 2.2 TI2V, animation of the most recent image in the chat, background execution, progress, estimate, and cancellation.
- Media returned inside the chat, with controls to minimize, expand, and copy; the side section is also collapsible and uses a compact per-conversation list. Each file is linked to the conversation in PostgreSQL and is deleted from disk along with the chat.
- Markdown tables, visual reports and inline SVG charts rendered in the chat (GFM and `ui-preview` blocks).
- PDF export from Markdown or HTML, linked to the conversation (`generate_pdf` tool).
- Internet research synthesized into a table or report with cited sources (`/research` skill).
- Voice transcription and synthesis with configurable support for Portuguese, English, and Spanish.
- Isolated TerminalCommandPolicy enforcing direct ProcessBuilder execution, strict command allowlists, and human-in-the-loop permission approvals without shell invocation.
- Dual-model architecture (Qwen 3.5 9B Planner + Granite 4.1 8B Executor) configurable via local profiles for fast tool execution.
- Permanent deletion of chats, messages, and related generated artifacts.
- Four independent model roles — chat, planning, vision, and image generation — are chosen in the interface rather than in a YAML file. Embeddings use the fixed `nomic-embed-text` model and Redis index `avento_index_nomic_embed_text`; embedding-model switching is not a feature.
- Provider layer that asks the address what it is: an Ollama behind an OpenAI-compatible endpoint is detected and served through the native path, where the context window can be negotiated per request.
- The context window distinguishes what a model DECLARES from what the running instance actually LOADED, so the prompt is sized against the real budget instead of being silently truncated.
- A reply that outlives the client: if the browser disconnects mid-stream, the answer the server produced is still persisted instead of being thrown away.


---
