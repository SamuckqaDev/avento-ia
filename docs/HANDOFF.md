# Handoff — updated 10/08/2026

Current handoff for the v2 documentation pass. The historical notes below are retained as their
original record; this section supersedes their repository-state and open-item claims.

---

## Estado do repositório

- Branch `spike/spring-boot-4-spring-ai-2`.
- Avento is at version `2.0.0`.
- The working tree contains five uncommitted tasks from tonight.

## v2 state recorded here

- RAG has one fixed embedding model, `nomic-embed-text`, and the Redis index is
  `avento_index_nomic_embed_text` from `spring.ai.vectorstore.redis.index-name`. The per-profile
  switching machinery was removed. A manifest is keyed by project root and stores its `indexName`,
  so an index change deletes the previous manifest's chunks before rebuilding.
- Profile avatars are database data on `UserAccount`, not localStorage. They are validated
  PNG/JPEG/WebP/GIF data capped at 512 KiB; the profile response reports only `hasAvatar` and the
  authenticated avatar routes upload and serve the bytes.
- Only the theme remains local. Selected model, voice, and image preferences are one-year,
  browser-readable cookies with `SameSite=Lax` and `Path=/`; `Secure` follows the protocol.
  `autoApproveAll` has no browser mirror and remains server-owned through `/api/settings`.

## Current open items

1. **Nothing from tonight is committed** — five tasks remain in the working tree.
2. **The full suite has never run locally.** Only 23 tests across 4 classes ran on the real machine;
   the full green result came from the sandbox. See learning 13.
3. **`isolate-tests-from-real-infra.md`** remains open, with a sharper edge: the leak appears only
   when the suite runs on the owner's machine.
4. **`backend-owns-rag-cleanup.md`** remains open. Tonight's symptom was a `matsutech-sti` manifest
   declaring 1,887 chunks that no longer exist in Redis.
5. **Unbounded Redis streams** — `avento:jobs:agent` and `avento:dead-letter` are never trimmed, and
   the `avento-agent-workers` group has accumulated 83 consumers, one per boot. This is not
   dangerous today: the worker acknowledges and drops a job whose row is gone. It is growth without
   a ceiling; no spec exists yet.
6. **pt-BR remains in older code and docs** — log messages and comments predating tonight, plus 11
   learning pages. Sweeping them is an owner decision that has not been made.

---

## O que mudou nesta sessão

### O RAG foi ligado ao agente (a tarefa que estava marcada como "próxima")

O problema era ausência de ligação, não código errado: `RagService` tinha busca vetorial completa e
só o `RagController` (REST) a chamava, então o índice do projeto aberto ficava vazio para sempre,
enquanto a ferramenta do agente usava `CodebaseRagService` (`conteudo.contains(token)`).

O que foi feito:

- `WorkspaceAccessService` publica `WorkspaceRootRegisteredEvent` na **primeira** vez que uma raiz é
  registrada num escopo (a mesma pasta é re-registrada a cada mensagem; anunciar sempre viraria uma
  varredura por mensagem).
- `WorkspaceIndexingService` (novo, avento-rag) escuta e aquece o índice numa thread só, prioridade
  mínima, fora da requisição. Estados: `UNKNOWN`/`INDEXING`/`READY`/`FAILED` — `FAILED` é retentado,
  porque a causa comum é o modelo de embedding fora do ar.
- `CodeSearchService` (novo) é a junção: vetorial quando o índice está pronto, **literal** enquanto
  não está, quando o vetorial vem vazio, ou quando ele estoura. A resposta carrega `matching` dizendo
  qual dos dois respondeu — sem isso o modelo não sabe se vazio é "não existe" ou "não bate
  literalmente".
- Índice é por raiz de projeto; busca em subpasta consulta a raiz e recorta os resultados de volta.
- Reindexação disparada por **salvar arquivo** (`write_file`/`edit_file`), debounce de 15s. Não por
  mensagem.
- `vectorStore.add` passou a ir em lotes sequenciais de 32.
- Descrição de `search_code` atualizada para descrever os dois modos honestamente.

### O limiar de similaridade estava herdado de prosa

Medido com `nomic-embed-text` sobre 15 chunks reais deste repo e 4 perguntas:

| Busca | Score do chunk certo | Passava em 0.62? |
|---|---:|---|
| "onde verifico se um caminho está dentro do workspace" | 0,615 (1º lugar) | Não |
| "onde fica o backup antes de sobrescrever" | 0,734 (1º lugar) | Sim |
| `requireAuthorized` — nome exato do método | 0,489 (1º lugar) | Não |

Faixa observada: 0,317–0,734, mediana 0,484. **0.62 descartava a resposta certa em metade das
buscas**, inclusive com o nome exato de um método escrito no arquivo. Novo padrão: **0.45**.

**Medido e descartado:** `nomic-embed-text` é treinado com prefixo de tarefa (`search_query:` /
`search_document:`) e o Spring AI não aplica nenhum. Com prefixo todos os scores sobem alguns
centésimos mas a separação **piora** — margem média entre o chunk certo e o melhor errado cai de
+0,039 para +0,012. Não vale implementar.

### Onde as chaves novas moram (cuidado)

As chaves `avento.rag.*` foram para `application.yml` (base). O app roda com
`--spring.profiles.active=local`, então `application-local.yml` **sobrescreveria** o base — mas hoje
ele não tem bloco `rag:` nenhum (verificado), então os valores do base valem. Se um dia aparecer um
`rag:` no local, é lá que `similarity-threshold`, `embedding-batch-size` e `auto-index` passam a ser
decididos. Já aconteceu antes com `num-ctx` e `default-model`: editar só o base não mudou nada.

### Colima saiu do caminho

- `~/.zshrc`: removido o `export DOCKER_HOST` apontando para o socket do Colima (pendência do
  handoff anterior). O contexto ativo é `desktop-linux`.
- `dev-up.sh`: removido o fallback que subia Colima quando o Docker Desktop não está instalado. Ele
  não atende o plugin `docker mcp`, então subir Colima ali deixava a stack de pé com sete servidores
  MCP faltando em silêncio. Agora falha dizendo o que está errado.
- A VM do Colima continua parada e **os volumes seguem no disco** (`~/.colima/default`) — contêm 16
  chats antigos. Não apagar sem decidir o que fazer com eles.

### Medições de embedding (nesta máquina, M2 Pro 16 GB)

| Lote | Tempo | Por chunk |
|---:|---:|---:|
| 1 | 0,10s | 100,3 ms |
| 8 | 0,41s | 50,6 ms |
| 32 | 1,55s | 48,4 ms |

Platô a partir de ~8. Daí `embedding-batch-size: 32` e lote sequencial: paralelizar faria o modelo
de embedding disputar RAM com o de chat, e o chat é quem o usuário está esperando.

---

### 03/08 — O agente respondia saudação a qualquer pergunta

Cinco defeitos de comportamento, todos encontrados puxando um único sintoma ("ele fica pensando para
sempre"):

- **Exemplo do prompt virava tarefa.** `execution.md:27` usava `"create a NestJS project in folder X"`
  como ilustração de formato; o modelo executava o exemplo em vez da pergunta. Exemplo removido, mais
  uma guarda geral dizendo que nada nas instruções é um pedido.
- **Plano virava promessa.** O modelo escrevia o bloco ` ```plan ` e encerrava o turno. Agora está
  escrito que o plano abre a resposta e a primeira ferramenta é chamada logo depois — e que anunciar
  `activate_tools` num passo de plano não ativa nada.
- **Spinner sem estado terminal.** `MessageBubble.hasVisibleContent` remove o bloco `plan`, e a
  condição do indicador olhava "tem conteúdo?" em vez de "o run acabou?". Run `COMPLETED` com resposta
  salva no banco e a bolha girando para sempre. Agora mostra os passos e avisa que o agente parou ali.
- **Three model fields were stored and never read at the time** — planning, image, and embedding.
  This historical finding is superseded by the v2 state above: embedding is no longer a configurable
  role.
- **Formulário de provedor recolhe depois de salvo**, com o papel de cada modelo descrito ao lado do
  campo.

### 03/08 — A alucinação era truncamento silencioso de contexto

O caso completo está em `docs/aprendizados/10-o-numero-morria-na-traducao.html`. Em resumo:

- O `num_ctx` que o Avento calculava era **descartado pelo transporte** (o protocolo da OpenAI não tem
  esse campo), então o Ollama subia com seus 4096 padrão.
- Medido: mesmo modelo, mesma pergunta — **0/4 acertos com janela 4096 contra 4/4 com 32768**. O
  prompt de 5970 tokens era cortado para 2050. O modelo respondia `porta 80` onde a config dizia 8417.
- `/api/show` dá o **teto** do modelo (262144); `/api/ps` dá o que foi **carregado** (4096). Confundir
  os dois quebrava dos dois lados.
- `ProviderKind` ganhou três eixos: `managesItsOwnContext`, `canRequestContextWindow`,
  `hasOwnModelNamespace`.
- **O seletor de modelos estava morto** com provedor remoto: `isLocalModelName` respondia "sim" para
  qualquer nome com dois-pontos, e todo modelo do Ollama tem dois-pontos. Comparar modelos na
  interface era comparar um modelo com ele mesmo.
- `effectiveKind` detecta Ollama atrás de um endereço "compatível com OpenAI" e roteia pelo caminho
  nativo, onde a janela pode ser pedida. **Não precisa mexer no servidor remoto** — o endpoint é a
  própria requisição (verificado: pedindo 4096 carrega 4096, pedindo 32768 carrega 32768).

⚠️ Dois consertos meus geraram defeito novo e foram corrigidos na mesma sessão: `min(…, carregado)`
criava um laço que prendia a janela em 4096 para sempre, e a detecção automática fez o aviso de
"provedor sem transporte" disparar alarme falso. Ambos com teste que falha sem a guarda.

### 03/08 — O aviso de provedor tinha três defeitos, não um

Encontrados conferindo por que ele aparecia mesmo com tudo funcionando:

1. **Premissa envelhecida** — "sem transporte = caiu no local" deixou de valer com a detecção
   automática. Guarda por `effectiveKind`.
2. **Nomeava a escolha errada** — dizia "Você selecionou (qwen3.5:35b)" enquanto a pessoa tinha
   escolhido `qwen3.5:9b` no seletor do cabeçalho; ele lê o modelo gravado em *Provedores*. Texto
   trocado para "O provedor **configurado** (…)".
3. **Aparecia em metade das respostas** — era montado dentro do `streamChatResolved`, e o caminho de
   skill retorna antes. Um "oi" avisava, uma busca na web não. Agora é montado no
   `streamChatDispatch`, antes da bifurcação, e a colagem vive em `withCloudNotice` — um método só,
   com teste, em vez de escrita duas vezes e esquecida numa delas.

**Verificado no log**: desde o restart de 09:52 todos os runs mandam `model=qwen3.5:9b` (orquestrador
e AgentService finalmente concordam), e o 9b está carregado na Fedora com **janela 32768**. O
truncamento acabou.

### 03/08 — A alucinação que sobrou NÃO é truncamento

Com a janela em 32768, o agente buscou de verdade e devolveu **nove cotações com erro 0,00%** contra
a fonte. Mas fabricou a coluna "Variação (%)" — a `open.er-api.com` não fornece variação nenhuma
(verificado: fora de `rates` só há metadados). E inverteu a direção em dois de três "Destaques"
("€ 1,15 por dólar" em vez de "US$ 1,15 por euro").

Isso é uma falha de **aderência a instrução**, não mecânica: `tools.md:40` já proíbe exatamente isso
("nunca preencha uma lacuna com um número plausível"), a instrução coube no contexto e o `qwen3.5:9b`
não a seguiu. **Não medido**: se o `qwen3.5:35b` resiste onde o 9b falhou — é o próximo teste, e
agora dá para fazer porque o seletor funciona.

---

### 03/08 — Onze minutos de trabalho jogados fora

O run `run_8c4ab823` rodou 11min19s em três rodadas, completou às 15:12:42 — e a resposta **não
existe**. O cliente tinha desistido às 15:11:33 (`Broken pipe` no SSE), e quem persiste a resposta é
o FRONTEND, depois de consumir o stream. Sem ouvinte, o servidor fez o trabalho e o descartou.

O stack trace que apareceu era o *tratador de exceção falhando*, não a falha: ele tentou responder
`BaseResponse` JSON num canal já marcado como `text/event-stream`, não achou conversor, e estourou —
soterrando o erro original.

Três consertos:

- **`OrphanReplyRescue`** (novo): acumula o texto que vai para a tela e, ao fim do run, agenda uma
  checagem. Passado o prazo (`avento.agent.orphan-reply-grace`, 20s), se a última mensagem do chat
  ainda for do usuário, grava a resposta. É REDE DE SEGURANÇA — o caminho normal continua sendo o
  frontend gravar, e o prazo existe para não duplicar.
- **Desconexão de SSE virou caminho normal**: `AsyncRequestNotUsableException` tem tratador próprio,
  responde 204 sem corpo e loga em DEBUG. Sem corpo não há o que converter, então o segundo erro
  deixa de existir.
- **Teto no ramo de projeto conectado**: ele devolvia o kit sem passar pelo `capToolCount` — as
  ativadas por `activate_tools` se acumulam em Redis rodada após rodada e a rodada 3 saiu com **22
  schemas** contra o teto declarado de 12. Novo `avento.agent.max-project-tools` (18): o kit fixo
  sobrevive inteiro, o que cresce em cima dele tem fim.

---

## NÃO medido / não validado

- ~~Primeira indexação de um projeto real de ponta a ponta.~~ **Medido**: o log do backend registra
  `Vector index ready for /Users/sr.tomimatu/projetcs/avento-ia in 11612 ms` — 11,6s para o repo
  inteiro, com `nomic-embed-text` local. Falta medir com `bge-m3` na Fedora, que é o caminho novo.
- **Nenhuma busca vetorial rodou de verdade pelo agente.** Os testes cobrem a lógica de decisão com
  o `RagService` mockado; o caminho Redis + Ollama real não foi exercitado nesta sessão.
- O limiar 0.45 vem de uma amostra pequena (15 chunks, 4 perguntas). É melhor que 0.62 com margem
  larga, mas não é um número calibrado com rigor.
- **A recuperação de resposta órfã nunca rodou em produção.** Os testes cobrem as quatro regras, mas
  o caminho real (cliente cai, prazo passa, mensagem aparece no chat) não foi exercitado com o app de
  pé.
- **O ciclo completo com a detecção automática não rodou.** Os testes cobrem a lógica; a confirmação
  de que o backend reiniciado usa o caminho nativo e carrega 32768 não foi feita com o app de pé.
- O `presence_penalty 1.5` embutido no modelo continua sem ser sobrescrito pelo Avento. Medido que
  **não** afeta ancoragem (5/5 com e sem), mas o efeito dele em respostas longas não foi avaliado.

---

## Ambiente atual

A inferência **saiu desta máquina**: roda numa Fedora Silverblue (48 GB, RTX 3050) alcançada por
Tailscale em `http://<tailnet-host>:11434`, configurada como `OPENAI_COMPATIBLE` — que a detecção
automática promove a `OLLAMA`. Modelos lá: `qwen3.5:35b`, `qwen3.5:9b`, `qwen2.5:32b`,
`llama3.3:70b`, `bge-m3`, entre outros.

O túnel efêmero anterior (`lhr.life`) morria junto com a sessão SSH e sorteava um subdomínio novo a
cada vez — não usar. Tailscale dá endereço fixo e não expõe nada na internet.

---

## Próximo trabalho: agente configurável

O plano está em **`docs/PLANO_AGENTE_CONFIGURAVEL.md`**, com todos os fatos já medidos para não
repetir a investigação. O essencial:

- **Docker MCP Toolkit está quebrado** (interface diz "No MCP servers added"; catálogo de 101 não
  contém os `mcp/*`; `registry.yaml` com `ref: ""`). **Não perseguir.**
- **`docker run --rm -i mcp/fetch` funciona** — provado falando MCP direto com a imagem: `initialize`
  → `tools/list` → `fetch` devolveu conteúdo real. As 8 imagens (5,7 GB) já estão no disco.
- **A ligação perfil → ferramentas já está construída** e o plano anterior errava ao dizer que o
  `AgentService` ignorava o perfil. A cadeia `PlanExecutionService:312` → `AgentRunWorker:228` →
  `AgentService:950` → `applyAgentToolPolicy` existe e tem teste. O que falta é o caminho de **chat**
  nunca chamar `AgentRoutingService.pick()`, então a allow-list chega vazia e nada é restringido.
- ⚠️ **Não ligar esse fio antes de inverter a ordem da política.** `applyAgentToolPolicy` é uma
  interseção aplicada DEPOIS da seleção: só sabe subtrair, nunca adicionar, e interseção vazia devolve
  `tools: []` — o turno vazio que os comentários da região já documentam.
- **O miolo do agente não tem teste**: `finishTurn` (241 linhas), `selectToolsForCurrentRequest`
  (107) e `runTurn` (o laço) têm ZERO. Fase 0 do plano é cobrir isso antes de mexer.
- **Ordem das fases mudou**: a fundação de código (BeanCopier/Lombok) desceu para a Fase 4 — não é
  pré-requisito de nada e estava bloqueando a fase que resolve a queixa original.

## Pendências

- `capture_screen`: precisa de permissão de Gravação de Tela nos Ajustes do Sistema
- FIXME em `image-prompt-signals.txt`: quatro literais com "pitbull" — fixture de teste que vazou
  para produção. O certo é um padrão `<verbo> <artigo> <assunto> que (eu) pedi`.
- Gatilho duplicado pré-existente: `research.md` e `web-research.md` compartilham
  "buscar na internet" e "pesquise na web"
- 22 classes duplicadas entre módulos (idênticas hoje, o teste impede divergirem) — entre elas
  `Manifest`, `ScannedFile` e `DocumentReadResult`, duplicadas entre avento-workspace e avento-rag
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
