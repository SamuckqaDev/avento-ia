<p align="center">
  <a href="FEATURES.md"><img src="https://img.shields.io/badge/lang-English-lightgrey?style=for-the-badge" alt="English"></a>
  <a href="FEATURES.pt-BR.md"><img src="https://img.shields.io/badge/idioma-Portugu%C3%AAs-2b7a78?style=for-the-badge" alt="Português"></a>
</p>

# Funcionalidades

O inventário completo do que funciona no Avento hoje.

- Conversas persistidas e isoladas por usuário.
- Execução assíncrona do agente com PostgreSQL, Outbox, Redis Streams, worker e SSE autenticado.
- Execução e aprovação idempotentes: entradas Redis duplicadas não repetem ferramentas nem reapresentam decisões já resolvidas.
- Um mascote do Avento aparece enquanto uma resposta em áudio está sendo reproduzida, com animação discreta que respeita a preferência de reduzir movimento.
- Watchdog de atividade encerra runs silenciosos sem deixar o chat ou o único worker local presos indefinidamente.
- Contexto recente em cache Redis reconstruível; PostgreSQL continua sendo a fonte durável.
- Streaming isolado e recuperavel por conversa: trocar de chat ou recarregar a pagina restaura o processamento pelo estado duravel do run, sem mover Thinking, resposta, midia ou voz para outra conversa.
- Raciocinio de modelos hibridos (qwen3, etc.) roteado de forma explicita para o bloco de Thinking da interface, sem depender do default do Ollama; o modelo permanece carregado entre mensagens para reduzir latencia de recarga.
- Janela de contexto por execucao com teto previsivel: o historico compactado de ferramentas fica limitado independente de quantas rodadas a tarefa tiver, evitando estourar o `num_ctx` do modelo em analises longas.
- Falha ao carregar o historico na troca de chat mostra um aviso explicito com nova tentativa automatica, em vez de renderizar a conversa como vazia; as mensagens permanecem integras no PostgreSQL.
- O painel de tarefas abre uma vez quando um plano surge e respeita o fechamento manual durante o restante da execucao.
- Planos autonomos persistem tarefas ordenadas por chat, executam uma por vez no backbone duravel com Redis, verificam cada workspace e retomam de forma idempotente apos reiniciar o backend.
- Seleção de modelo de chat e de geração visual no header.
- Avatares de perfil ficam armazenados como PNG, JPEG, WebP ou GIF validados na conta do usuário autenticado (máximo de 512 KiB); `/api/auth/me` expõe apenas `hasAvatar`, enquanto rotas autenticadas próprias enviam e servem a imagem.
- Modelo selecionado, voz e preferências de imagem usam cookies legíveis pelo navegador, de um ano, com `SameSite=Lax` e `Path=/`; só o tema continua no localStorage, e `autoApproveAll` continua pertencendo ao servidor por `/api/settings`.
- Rastreamento do consumo de tokens por modelo, dia e chat, com dashboard visual de métricas.
- Análise de stack, scripts, entrypoints e estrutura do workspace.
- Leitura, criação, edição, busca e exclusão de arquivos autorizados.
- Anexo direto de PDF, Office, EPUB, ZIP, texto e código no chat, com extração local por texto puro ou MarkItDown e contexto persistido na conversa.
- Aprovação de ações pela interface ou por comandos de voz.
- Integração MCP com Git, bancos, Docker, filesystem, navegador e macOS.
- Consulta ao banco descoberto no projeto ativo, inclusive em Docker.
- Geração assíncrona de imagens pelo ComfyUI ou por modelo direto com API de imagens compatível, com origem explícita no menu rápido, referência estrutural ou de identidade, controle de pose, revisão visual, progresso, estimativa, cancelamento e parâmetros ajustáveis no frontend.
- Tradução automática do prompt para inglês antes do SDXL (o CLIP só entende inglês) e presets de geração por modelo (sampler, passos, CFG e resolução ajustados a cada checkpoint), sobreponíveis por um arquivo local sem recompilar.
- Geração de vídeos pelo ComfyUI com WAN 2.2 TI2V, animação da imagem mais recente do chat, execução em background, progresso, estimativa e cancelamento.
- Retorno das mídias dentro do chat, com controles para minimizar, expandir e copiar; a seção lateral também é recolhível e usa uma lista compacta por conversa. Cada arquivo fica vinculado à conversa no PostgreSQL e é apagado do disco junto com o chat.
- Tabelas Markdown, relatórios visuais e gráficos SVG renderizados no chat (GFM e blocos `ui-preview`).
- Exportação de PDF a partir de Markdown ou HTML, vinculada à conversa (ferramenta `generate_pdf`).
- Pesquisa na internet com síntese em tabela ou relatório e citação de fontes (skill `/research`).
- Transcrição e síntese de voz com suporte configurável a português, inglês e espanhol.
- TerminalCommandPolicy isolada aplicando execução direta por ProcessBuilder, allowlists estritas de comandos e aprovações de permissão com controle humano em tempo real sem invocação de shell.
- Arquitetura de 2 modelos (Planejador Qwen 3.5 9B + Executor Granite 4.1 8B) configurável via perfil local para alta velocidade de execução de ferramentas.
- Exclusão permanente de chats, mensagens e artefatos gerados relacionados.
- Quatro papéis de modelo independentes — conversa, planejamento, visão e geração de imagem — cada um com seu próprio modelo escolhido na interface, não em arquivo YAML. Embeddings usam o modelo fixo `nomic-embed-text` e o índice Redis `avento_index_nomic_embed_text`; trocar o modelo de embedding não é uma funcionalidade.
- Camada de provedor que pergunta ao endereço o que ele é: um Ollama atrás de um endpoint compatível com OpenAI é detectado e atendido pelo caminho nativo, onde a janela de contexto pode ser negociada na própria requisição.
- A janela de contexto distingue o que o modelo DECLARA do que a instância realmente CARREGOU, então o prompt é dimensionado pelo orçamento real em vez de ser truncado em silêncio.
- Resposta que sobrevive ao cliente: se o navegador cair no meio do stream, o que o servidor produziu é gravado mesmo assim, em vez de ser jogado fora.
