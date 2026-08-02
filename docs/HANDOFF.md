# Handoff — sessão de 31/07 a 01/08/2026

Contexto para retomar em outra conversa. Escrito no fim de uma sessão longa; **tudo aqui foi
verificado rodando comando**, e onde não foi está marcado como não verificado.

---

## Estado do repositório

- Branch `master`, sincronizado com `origin/master`
- **653 testes, 0 falhas** (`cd back/avento && mvn clean test`)
- CI no GitHub Actions passando (`.github/workflows/ci.yml`)
- Frontend: 27 testes, `npm run validate` limpo

---

## O que mudou nesta sessão

### Ambiente
- **Ambiente espacial/VR removido** (~9.200 LOC, 65 arquivos). Backup em
  `~/avento-spatial-backup-2026-07-30.tar.gz`. Bundle caiu de 1,70 MB para 1,17 MB.
- **Colima desligado**, Docker Desktop virou o runtime. Volumes do Colima preservados (contêm 16
  chats antigos). Voltar: `colima start && docker context use colima`.
- ⚠️ **`~/.zshrc` linha 11** exporta `DOCKER_HOST` apontando para o socket do Colima, que está
  parado. Tem precedência sobre `docker context use`. O `dev-up.sh` contorna, mas a linha deveria
  sair.

### MCP
- **Docker MCP Gateway ligado**, com 7 servidores migrados para container: `fetch`, `time`,
  `memory`, `sequentialthinking`, `playwright`, `puppeteer`, `git`.
- **5 continuam nativos** por impossibilidade: `filesystem` e `markitdown` (exigem paths fixos
  montados; o Avento entrega workspace por chat), `macos-automator`, `apple`, `chrome-devtools`
  (não existem no catálogo Docker — dependem de AppleScript e do Chrome do host).
- O gateway é **plugin do Docker Desktop**: com Colima ele não sobe, nem com `--dry-run`.

### AgentService dividido
De 4.788 para 3.668 linhas. Extraídos: `ModelNames`, `MessageText`, `HistoryText`,
`TextualToolCallParser`, `ProviderErrorTranslator`, `ModelCatalogService`, `PromptAssemblyService`,
`ImageIntentService`, `TerminalCommandPolicy`.

Regra usada: função pura vira estática em `support/`; colaborador com configuração vira serviço.

---

## Bugs encontrados e corrigidos (6)

Todos com teste que **falha sem a correção** — verificado desligando cada conserto.

1. **Escape de workspace por symlink.** Escrita de arquivo novo através de link dentro do workspace
   ia parar fora da raiz autorizada. E o inverso: projeto sob `/tmp` ou `/var` (links no macOS)
   podia ser lido mas não escrito. → `WorkspaceSymlinkAuthorizationTest`
2. **`codebase_vector_search` lançava `Recursive update`** na primeira busca de qualquer workspace —
   `computeIfAbsent` com função que grava no próprio mapa. Quebrado desde que foi ligado.
3. **`schedule_task` inalcançável.** Duas classes `LocalToolNames` em módulos diferentes, divergindo
   por uma entrada; o classpath escolheu a defasada. → `NoDuplicateClassNamesTest` proíbe divergência
   (há 22 outras duplicatas, todas idênticas hoje).
4. **Docker MCP Gateway com flag inexistente.** Rodava `--profile`, que não existe no CLI.
5. **`capture_screen`** devolvia erro cru do macOS em vez de dizer que falta permissão de Gravação
   de Tela.
6. **Seletor de modelo ignorado.** Um literal `"qwen3.5:9b"` chumbado em `ModelProviderService`
   contradizia `avento.agent.default-model`, mais uma heurística que lia "pediu o default" como "não
   pediu nada". Escolher granite no seletor não trocava o modelo. → `ModelNames.chooseChatModel`

Documentados em `docs/aprendizados/03` a `07` (HTML) e como 5 skills acionáveis por sintoma
(`tool-registered-but-not-found`, `model-choice-ignored`, `workspace-write-refused`,
`slow-agent-round`, `docker-mcp-gateway-down`).

---

## Performance — o que foi MEDIDO

| Rodada | Prompt | Tempo |
|---|---:|---:|
| `tools(0)` | 4.327 tokens | 17,9s |
| `tools(12)` | 7.546 tokens | 32,0s |

**As 12 ferramentas custam 3.219 tokens e 14 segundos.** O custo dos servidores MCP não está na RAM
que ocupam (0,13 GB em 15 processos, contra 6 GB do Ollama) — está nos **schemas que injetam no
prompt**, reavaliados a 4-5 ms por token a cada rodada.

Schemas dos servidores tirados do boot: playwright ~4.858 tokens (24 ferramentas), memory ~2.875,
sequential-thinking ~1.176, puppeteer ~648, fetch ~290. Total ~9.800 tokens, 42 ferramentas.

**Não medido:** comparação direta antes/depois das mudanças. Várias coisas mudaram juntas.

### Alavanca não usada
Rodando `qwen3.5:9b` com `num-ctx: 32768`. A config define `granite4.1:8b` como padrão, e a
anotação do usuário registra `granite4.1:8b @ 8192 ≈ 2,8s em regime` contra `qwen3.5:9b @ 16384 =
34-46s`. Trocar o modelo no seletor **agora funciona** (bug 6 corrigido), mas exige restart do
backend. O `num-ctx` precisa de edição no yml.

---

## TAREFA SEGUINTE: consertar o RAG

### O problema
Existem dois caminhos de busca no código e eles não se encontram:

- **`RagService`** (avento-rag) — RAG vetorial de verdade: Redis VectorStore, `nomic-embed-text`,
  chunks de 500 tokens, similaridade ≥ 0.62, topK 30 → 5 resultados, cache por query, indexação
  **incremental por hash de arquivo** com IDs determinísticos.
  **Só o `RagController` (REST) chama `searchContext` e `indexProject`. O agente não alcança, e o
  índice está vazio.**
- **`CodebaseRagService`** — o que a ferramenta do agente usa. Pontua com
  `if (conteudo.contains(token)) score += 1.0`. Busca literal, não vetorial.

### O que já foi feito
A ferramenta foi renomeada de `codebase_vector_search` para **`search_code`**, com descrição
honesta (casa termo literal, não linguagem natural; aponta `find_symbol` para definição e
`search_files` para nome de arquivo). Antes a descrição prometia "busca semântica (RAG) em
linguagem natural" e entregava casamento de token — mentia para o modelo.

### O plano acordado
**Ligar a indexação ao registro do workspace**, assíncrona, e fazer a busca tentar o vetorial e cair
no literal enquanto o índice não estiver pronto.

Pontos que importam:
- A indexação é **incremental por hash** — a primeira passada é cara (milhares de chunks), as
  seguintes só tocam arquivos editados. O custo por busca é **um** embedding, o da pergunta.
- Mandar embeddings em lote, concorrência 1 ou 2 — mais que isso compete com o modelo de chat pela
  RAM numa máquina de 16 GB.
- Gatilho de reindexação deve ser salvar arquivo, não mandar mensagem.
- O limiar 0.62 precisa ser calibrado com código, que embeda diferente de prosa.
- Não adicionar ferramenta nova: aproveitar o `search_code` que já existe, para não crescer o
  orçamento de prompt.

**Pré-requisito:** Ollama de pé, para validar que `nomic-embed-text` responde e medir a primeira
indexação.

---

## Pendências menores

- `~/.zshrc` linha 11: remover o `export DOCKER_HOST` do Colima
- `capture_screen`: precisa de permissão de Gravação de Tela nos Ajustes do Sistema
- FIXME em `image-prompt-signals.txt`: quatro literais com "pitbull" — fixture de teste que vazou
  para produção. O certo é um padrão `<verbo> <artigo> <assunto> que (eu) pedi`.
- Gatilho duplicado pré-existente: `research.md` e `web-research.md` compartilham
  "buscar na internet" e "pesquise na web"
- 22 classes duplicadas entre módulos (idênticas hoje, o teste impede divergirem)
- Ferramentas nunca exercitadas: `generate_video`, `open/close_browser_tab`, `create_vite_project`,
  `run_shortcut`

---

## Convenções do projeto (importantes)

- **Inglês** em nome de branch, mensagem de commit e código novo. Texto que o usuário lê no chat
  fica em PT-BR informal.
- **Texto fora do código**: listas de palavras vão para `agent/heuristics/*.txt`, prompts para
  `agent/prompts/*.md`.
- **`policies/maximum.md` nunca entra num `git add -A`** — a versão experimental do usuário vive em
  `~/.avento/policies/`.
- Aprendizado que custou caro vira **um arquivo HTML por caso** em `docs/aprendizados/`, contando
  sintoma → causa raiz → conserto.
- O objetivo do projeto é ser **peça de portfólio de engenharia de IA**.
