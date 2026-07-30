# Feature list · Lista de funcionalidades

The full inventory of what Avento does today. The README keeps the short version;
this file is the exhaustive one, kept out of the front page so it stays readable.

O inventário completo do que o Avento faz hoje. O README fica com a versão curta;
este arquivo é o exaustivo, fora da página inicial para ela continuar legível.

---

## English

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
- Permanent deletion of chats, messages, and related generated artifacts.


---

## Português

- Conversas persistidas e isoladas por usuário.
- Execução assíncrona do agente com PostgreSQL, Outbox, Redis Streams, worker e SSE autenticado.
- Execução e aprovação idempotentes: entradas Redis duplicadas não repetem ferramentas nem reapresentam decisões já resolvidas.
- Watchdog de atividade encerra runs silenciosos sem deixar o chat ou o único worker local presos indefinidamente.
- Contexto recente em cache Redis reconstruível; PostgreSQL continua sendo a fonte durável.
- Streaming isolado e recuperavel por conversa: trocar de chat ou recarregar a pagina restaura o processamento pelo estado duravel do run, sem mover Thinking, resposta, midia ou voz para outra conversa.
- Raciocinio de modelos hibridos (qwen3, etc.) roteado de forma explicita para o bloco de Thinking da interface, sem depender do default do Ollama; o modelo permanece carregado entre mensagens para reduzir latencia de recarga.
- Janela de contexto por execucao com teto previsivel: o historico compactado de ferramentas fica limitado independente de quantas rodadas a tarefa tiver, evitando estourar o `num_ctx` do modelo em analises longas.
- Falha ao carregar o historico na troca de chat mostra um aviso explicito com nova tentativa automatica, em vez de renderizar a conversa como vazia; as mensagens permanecem integras no PostgreSQL.
- O painel de tarefas abre uma vez quando um plano surge e respeita o fechamento manual durante o restante da execucao.
- Planos autonomos persistem tarefas ordenadas por chat, executam uma por vez no backbone duravel com Redis, verificam cada workspace e retomam de forma idempotente apos reiniciar o backend.
- Seleção de modelo de chat e de geração visual no header.
- Rastreamento do consumo de tokens por modelo, dia e chat, com dashboard visual de métricas.
- Análise de stack, scripts, entrypoints e estrutura do workspace.
- Leitura, criação, edição, busca e exclusão de arquivos autorizados.
- Anexo direto de PDF, Office, EPUB, ZIP, texto e código no chat, com extração local por texto puro ou MarkItDown e contexto persistido na conversa.
- Aprovação de ações pela interface ou por comandos de voz.
- Integração MCP com Git, bancos, Docker, filesystem, navegador e macOS.
- Consulta ao banco descoberto no projeto ativo, inclusive em Docker.
- Geração assíncrona de imagens pelo ComfyUI com RealVisXL SDXL, referência estrutural ou de identidade, controle de pose, revisão visual, progresso, estimativa, cancelamento e parâmetros ajustáveis no frontend.
- Tradução automática do prompt para inglês antes do SDXL (o CLIP só entende inglês) e presets de geração por modelo (sampler, passos, CFG e resolução ajustados a cada checkpoint), sobreponíveis por um arquivo local sem recompilar.
- Geração de vídeos pelo ComfyUI com WAN 2.2 TI2V, animação da imagem mais recente do chat, execução em background, progresso, estimativa e cancelamento.
- Retorno das mídias dentro do chat, com controles para minimizar, expandir e copiar; a seção lateral também é recolhível e usa uma lista compacta por conversa. Cada arquivo fica vinculado à conversa no PostgreSQL e é apagado do disco junto com o chat.
- Tabelas Markdown, relatórios visuais e gráficos SVG renderizados no chat (GFM e blocos `ui-preview`).
- Exportação de PDF a partir de Markdown ou HTML, vinculada à conversa (ferramenta `generate_pdf`).
- Pesquisa na internet com síntese em tabela ou relatório e citação de fontes (skill `/research`).
- Transcrição e síntese de voz com suporte configurável a português, inglês e espanhol.
- Exclusão permanente de chats, mensagens e artefatos gerados relacionados.

