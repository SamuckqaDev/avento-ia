# Handoff — sessão de 08 a 10/08/2026

Contexto para retomar em outra conversa. **Tudo aqui foi verificado rodando comando**, e onde não foi
está marcado como não verificado.

---

## Estado do repositório

- Branch **`spike/spring-boot-4-spring-ai-2`**, **local, nunca enviada ao GitHub**
- **28 commits** à frente de `feat/rag-index-on-workspace-registration`; `master` intacta
- **811 testes, 0 falhas** (`cd back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'`) — eram 727
- Árvore limpa

⚠️ **A branch nunca foi para o GitHub.** São 28 commits de migração de major mais refatoração
estrutural. Fazer push ou merge é decisão do dono e ainda não foi tomada.

---

## O que mudou

### Migração de plataforma

**Spring Boot 3.5.16 → 4.1.0** e **Spring AI 1.1.8 → 2.0.0**. O 1.1.8 é o último da linha do Boot 3,
então "atualizar o Spring AI" era, na prática, migrar o Boot.

O grosso foi **Jackson 2 → 3** em 117 arquivos. As armadilhas, todas medidas:

| Jackson 2 | Jackson 3 |
|---|---|
| `fields()` `fieldNames()` `elements()` | `properties()` `propertyNames()` `values()` — coleções, não iteradores |
| `isContainerNode()` · `TextNode` | `isContainer()` · `StringNode` |
| `with(String)` | `withObjectProperty(String)` — **não** `withObject`, que lê o argumento como caminho |
| `deepCopy()` covariante | perdeu o genérico, exige cast |
| `ObjectMapper.copy()` | imutável, o método sumiu |
| exceções verificadas | não-verificadas → `catch (IOException)` vira código morto |

**As anotações NÃO mudaram de pacote** — `com.fasterxml.jackson.annotation` continua. Rename em bloco
quebra dois DTOs.

Fora do Jackson: Jedis fixado em **7.4.1** (o Boot 4.1 gerencia 6.0.0, e o `spring-ai-redis-store`
2.0.0 precisa da API `RedisClient`, que só existe do 7 em diante); `@EntityScan` foi para
`org.springframework.boot.persistence.autoconfigure`; `mcp-json-jackson2` → `jackson3`.

### MCP nos dois sentidos

**Consome** — quatro servidores agora sobem de container: `fetch`, `time`, `memory`,
`sequential-thinking`. Verificado à mão com o comando exato que o código monta.

**Serve** — `AventoMcpServer` publica quatro ferramentas de **leitura** via `@McpTool`, desligado por
padrão (`avento.mcp.server.enabled`).

⚠️ **O `git` fica no host, por medição.** Neste repo (37.725 arquivos), o handshake do container leva
0,6s e um `rev-parse` 0,28s — mas `git status` completo **não retornou em 3 minutos**, contra 0,056s
no host. É o custo por `stat` do bind mount do Docker Desktop no macOS. Ferramenta que anda na árvore
do host pertence ao host — o mesmo vale para `filesystem`.

### Agente configurável

O perfil do usuário passou a mandar no **chat**, não só no modo plano: ferramentas, `system_instructions`
e `model`. A allow-list virou **fonte do universo antes da seleção**, não filtro depois — antes ela só
sabia subtrair.

`GET /api/agents/tools` lista o que é escolhível (local, container, servidor MCP) **sem subir
container**, alimentado por um cache de schemas por digest de imagem.

### Estrutura

| | Antes | Depois |
|---|---:|---:|
| `McpController` | 2.265 | **1.847** |
| `AgentService` | 3.470 | **3.412** |
| `finishTurn` | 241 | **127** |
| Classes duplicadas | 24 | **3** (bloqueadas por JPA, com motivo) |
| `docs/` | 23 misturados | **14 vivos + 10 em `historico/`** |

Cinco pastas por módulo (`model`, `dto`, `controller`, `service`, `config`), com subpastas por tema.

---

## Defeitos encontrados que ninguém sabia que existiam

1. **`Map.of` randomiza a ordem de iteração por execução da JVM.** 17 das 42 ferramentas declaravam
   propriedades assim, então o schema saía com chaves em ordem diferente a cada restart — invalidando
   o cache de prompt do llama.cpp. Medido: três ordens diferentes em cinco execuções. A migração para
   `@Tool` conserta, porque gera na ordem dos parâmetros.

2. **O limiar de similaridade do RAG mentia.** O default no código era `0.62` — o valor medido como
   errado — e só o `application.yml` dizia `0.45`. Qualquer contexto sem aquele YAML usava o ruim em
   silêncio.

3. **`create_skill.triggers` dizia ao modelo que cada gatilho é "Caminho absoluto."** — herdado do
   helper genérico de array. São frases-gatilho, não caminhos.

4. **O Mockito se auto-anexava** e avisava que isso deixaria de funcionar. Já tinha falhado dentro do
   sandbox do Codex. Agora carrega como `-javaagent`.

5. **O `VectorStoreResolver` nunca funcionou.** Ver abaixo — é o item aberto mais importante.

---

## ⚠️ Aberto e urgente: testes escrevem na infraestrutura real

**Descoberto no fim da sessão, e é o motivo deste handoff existir.**

### O pior caso: tarefas do Cowork

O `ToolIntegrationTest` criava tarefas agendadas **reais** no Postgres:

```
name: teste-integracao   cron: 0 0 3 * * *   status: ACTIVE
prompt: "Resumir o dia."  next_run_at: amanhã 03:00
```

Uma por rodada da suíte. **Oito acumuladas**, todas `ACTIVE`. Se o Avento estivesse de pé às 3h, o
`CronTaskScheduler` executaria as oito — cada uma disparando run autônoma em modo Cowork, que
**auto-aprova ferramentas**.

**As 8 foram apagadas em 10/08 20:3x** (`DELETE 8`, zero restantes). Mas a causa continua: rodar a
suíte cria de novo.

### Redis: 954 chaves de teste

477 manifestos e 477 versões de RAG, **todos de `@TempDir` do JUnit**
(`/private/var/folders/.../T/junit-…`). Amostrei 60, os 60 eram temporários. Não expiram.

### Já corrigido

`~/.avento/mcp-tool-schemas.json` — o `ToolIntegrationTest` agora aponta para caminho temporário.

---

## Itens abertos, em ordem

### 1. Isolar os testes da infraestrutura real ⚠️

Spec: **`docs/agent-tasks/isolate-tests-from-real-infra.md`**

Decisão do dono já tomada: **`@Transactional` com rollback** no `ToolIntegrationTest`, e
**`@AfterEach`** apagando o que cada teste criar onde rollback não alcançar (Redis não participa de
transação JPA).

Enquanto isso não for feito, **qualquer medição no Redis ou no Postgres vem contaminada**.

### 2. Limpeza do RAG tem de ser do backend

Spec: **`docs/agent-tasks/backend-owns-rag-cleanup.md`**

Hoje depende do front: `Home/index.tsx:1289` compara a lista de projetos num `useEffect` e chama
`/api/rag/clear`. Duas fragilidades — **só funciona com a página aberta**, e é
`catch(console.error)`, então falha em silêncio e nunca tenta de novo.

O backend tem `WorkspaceRootRegisteredEvent` para registro, mas **não há evento de remoção**. Essa é a
peça que falta.

### 3. `VectorStoreResolver` — conserto feito, prova pendente

O bean de `RedisClient` foi publicado (commit `67da45e`) e há teste de contexto. Mas o `FT._LIST`
continua mostrando só `avento_index`, porque **a aplicação nunca subiu com esta árvore**.

**Não verificado:** que um `avento_index_<perfil>` nasça de verdade. Exige subir a app com um perfil
de embedding ativo.

Por que importa: hoje é `nomic-embed-text` (**768 dim**). O `bge-m3` tem **1024**. Com o resolver
inerte, os dois apontam para o mesmo índice e o Redis **recusa** — o schema fixa `dim 768`.

### 4. Resto da dívida

- `buildOllamaRequest` (104 linhas) → `ModelTransport`, que já existe
- Lombok em 368 das 399 classes — decisão do dono, volume puro
- Tela de agentes no front — o back está pronto e testado
- Medir se o prefixo do prompt se mantém estável com `ChatClient`, antes de decidir a reescrita do
  laço em advisors

---

## Como trabalhar nesta sessão

**O dono projeta com o Claude; o Codex executa.** Skill `codex-handoff`, launcher em
`tools/codex-task.sh`:

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia && ./tools/codex-task.sh docs/agent-tasks/<spec>.md
```

Aprendido na prática, e vale repetir:

- **O Codex não commita** — o sandbox monta `.git` somente leitura. Ele executa, você revisa e
  commita. Isso acabou sendo a divisão certa.
- **Re-despachar em árvore suja trava ele**: a regra "pare se um teste falhar" o para na porta. A spec
  precisa dizer qual é o estado atual e qual falha é esperada.
- **Ele para em bloqueio real e pergunta** em vez de chutar. Nas cinco rodadas da consolidação de
  duplicatas, parou cinco vezes, e em todas tinha razão.
- **Não confie no relato final dele sem verificar.** Um veio truncado com rascunho vazando; o trabalho
  estava certo, mas só dava para saber conferindo.

**Sessões ficam em** `~/.codex/sessions/<ano>/<mês>/<dia>/` — não aparecem no app do Codex.

---

## Convenções do projeto

- **Inglês** em branch, commit e código novo. Texto de chat em PT-BR informal.
- Texto fora do código: listas em `agent/heuristics/*.txt`, prompts em `agent/prompts/*.md`.
- **`policies/maximum.md` nunca entra num `git add -A`** — a versão experimental vive em
  `~/.avento/policies/`.
- Aprendizado caro vira **um HTML por caso** em `docs/aprendizados/`, com sintoma → causa → conserto.
- O objetivo do projeto é ser **peça de portfólio de engenharia de IA**.

---

## Ambiente

- Inferência numa Fedora alcançada por Tailscale, `OPENAI_COMPATIBLE` promovido a `OLLAMA` pela
  detecção automática
- **Postgres** e **Redis Stack** em container, de pé há dias — `avento-postgres`, `avento-redis-stack`
- Redis: **6.022 chaves**, índice `avento_index` com **5.037 documentos**, `dim 768`, `COSINE`, HNSW
- Máquina de 16 GB com outros projetos rodando: **não subir serviço sem combinar**
- Java: o `mvn` roda em **25.0.1** enquanto o PATH tem 21. O enforcer agora exige `[21,)`
