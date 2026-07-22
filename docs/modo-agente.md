# Modo Agente, Memória e Auto-connect — como o fluxo funciona

Referência do subsistema construído para o Avento operar como um "Codex local": planeja, executa
tarefa por tarefa, com agentes especializados, memória de longo prazo e conexão de ferramentas sob
demanda — tudo local e sequencial (um modelo por vez, respeitando a RAM).

## Dois caminhos de entrada

O botão 🤖 no campo de mensagem decide o caminho.

### A. Chat normal (🤖 desligado)
1. Monta o prompt de sistema: identidade + política + **`[Memória do usuário]`** (fatos ATIVOS) +
   workspace + resumo do histórico antigo. — `AgentService.withBackendIdentityPrompt`
2. **Auto-connect por intenção**: conecta os servidores MCP que o pedido precisa (git/dbhub/…) que
   estão disponíveis e desconectados. — `McpAutoConnectService`, chamado em `ToolExecutionGateway.listTools`
3. **Seleção de ferramentas**: só as relevantes vão pro modelo (contexto enxuto). — `AgentService.selectToolsForCurrentRequest`
4. **Loop do modelo** com guardas:
   - rodada vazia → 1 retry e para;
   - **mesma ferramenta falha 2x → para e explica** (`recordToolOutcome` / `REPEATED_TOOL_FAILURE_LIMIT`);
   - teto de rodadas/ferramentas.
5. Resposta transmitida.
6. **Pós-turno (background)**: extração de memória — propõe fatos duráveis como PENDENTES. — `MemoryExtractionService`

### B. Modo agente (🤖 ligado)
1. O pedido vira um **plano** via chamada limpa ao modelo (SEM ferramentas → confiável). — `PlanBuilderService`
2. Cada tarefa recebe um **agente** (roteamento: manual > gatilho/especialidade > padrão). — `AgentRoutingService`
3. Execução **sequencial, uma tarefa por vez** (`planTaskExecutor` corePoolSize=1). — `PlanExecutionService`
   Para cada passo: contexto **particionado** (persona do agente + tarefa + resumo + só os arquivos
   dela) → **checkpoint** → executa no backbone durável → **verifica** (só falha se havia o que
   verificar E falhou) → sucesso/próximo | falha: 2 tentativas e **PAUSA**; passo arriscado → gate.

## Agentes especializados

- Entidade `AgentProfile` (persona por usuário: nome, especialidade, instruções, ferramentas, gatilhos,
  modelo, padrão). CRUD em `/api/agents`, tela em ⚙️ → **Agentes**.
- Na execução de uma tarefa, o agente atribuído fornece: **instruções (persona)** + **modelo preferido**
  + **allow-list de ferramentas** (`RunToolPolicyRegistry`, por runId → o loop restringe o toolset).

## Memória híbrida

- Manual (aba Memória / botão 🧠 no chat) = **ativa na hora**; extração automática + ferramenta
  `remember` = **pendente até o usuário confirmar**. Só as ATIVAS entram no prompt. — `UserMemoryService`

## Auto-connect por intenção

- Gatilhos (id do servidor → palavras-chave) em `resources/agent/mcp-auto-connect.md`, por categoria.
- Casa por **palavra inteira**, sem acento. Só conecta servidores **disponíveis** (não pendura em
  binário ausente). O catálogo (`McpServerCatalogService`) permanece como camada de orquestração —
  conecta o que precisa em vez de expor tudo (que estouraria o contexto local).

## Invariantes

- **Isolamento por usuário** em memória, agentes, planos e tarefas.
- **Local e sequencial**: um modelo por vez.
- **Textos fora do código**: prompts em `resources/agent/prompts/*.md`, gatilhos em
  `resources/agent/mcp-auto-connect.md`, config em `application.yml`, exceções via `ApiExceptionHandler`.

## Pendente

- **Aprovação inline no chat** (#2): hoje o passo bloqueado espera aprovação no **painel**; o alvo é
  surgir como card no **chat**.
