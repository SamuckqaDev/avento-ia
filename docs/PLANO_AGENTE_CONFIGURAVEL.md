# Plano — agente configurável por usuário

> **Status:** não iniciado · escrito em 08/08/2026 · revisado em 08/08/2026 após leitura do código
> **Pré-requisito:** commitar os 58 arquivos da sessão de 02–08/08 antes de começar.
> **Branch atual:** `feat/rag-index-on-workspace-registration`

Tirar do código o que é conteúdo (identidade, diretrizes, ferramentas permitidas) e deixar em código
apenas o que é execução. As ferramentas passam a vir de containers MCP oficiais.

**Ordem de execução: 0 → 1 → 2 → 3 → 4 → 5.** A revisão trocou a antiga Fase 1 (fundação de código)
com a antiga Fase 3 (o perfil manda): a fundação não é pré-requisito de nada e estava bloqueando a
única fase que responde à queixa original. Os nomes antigos ficam anotados em cada título.

---

## Fatos medidos (não repetir a investigação)

Tudo abaixo foi verificado rodando comando nesta máquina, em 08/08/2026.

### Docker MCP: o Toolkit está quebrado, os containers não

| Verificação | Resultado |
|---|---|
| Docker Desktop → MCP Toolkit (interface) | **"No MCP servers added"** |
| `docker mcp server ls` | lista 8 servidores (resíduo do `registry.yaml`) |
| `~/.docker/mcp/registry.yaml` | os 8 com **`ref: ""`** — referência vazia |
| Catálogo `docker-mcp` | 101 servidores, **nenhum** aponta para `mcp/*` |
| `docker mcp gateway run --servers fetch,time --static` | `MCP server not found` → **0 tools** |
| `mcp-add(fetch)` pelo gateway dinâmico | `Server 'fetch' not found in catalog` |
| Imagens `mcp/*` no disco | **8 imagens, 5,7 GB**, tag `<none>` mas digests íntegros |
| `docker run mcp/fetch` | reanexou a tag sem baixar nada (mesmo digest) |
| **`docker run --rm -i mcp/fetch` + protocolo MCP** | ✅ **`initialize` → `tools/list` → `fetch` executou e devolveu conteúdo real** |

**Conclusão:** o caminho é `docker run --rm -i mcp/<servidor>` direto, como mais um servidor no
catálogo interno do Avento. O Toolkit, o catálogo do Docker e o gateway dinâmico são **irrelevantes**
para o objetivo. Não gastar tempo com eles.

Colima descartado como hipótese: parado, sem contexto registrado, contexto ativo é `desktop-linux`.

### A ligação perfil → ferramentas JÁ EXISTE — só não passa pelo chat

Esta é a correção mais importante da revisão. A versão anterior deste plano dizia que o
`AgentService` ignorava os campos do perfil. **Ele não ignora — ele nunca é avisado.** A cadeia
completa está construída e testada:

| Passo | Onde | O que faz |
|---|---|---|
| 1 | `PlanExecutionService:312-315` | lê `agent.getAllowedTools()`, escreve `allowedTools` no payload |
| 2 | `AgentRunWorker:228-230` | lê o payload e chama `toolPolicyRegistry.allow(runId, …)` |
| 3 | `AgentService:950` | `tools = applyAgentToolPolicy(tools, state)` |
| 4 | `AgentService:1160-1165` | filtra o toolset pela allow-list daquela run |

O javadoc de `RunToolPolicyRegistry` descreve exatamente o objetivo deste plano: *"the agent's
allowedTools are registered here so the agent loop can restrict the exposed toolset to that agent's
scope"*.

**O que falta:** só `PlanExecutionService` e `PlanBuilderService` chamam `AgentRoutingService.pick()`.
O caminho de **chat** entra no `AgentRunWorker` sem `allowedTools` no payload → registry vazio →
`applyAgentToolPolicy` devolve tudo inalterado. A Fase 1 é ligar esse fio, não construí-lo.

### ⚠️ Conflito de design a resolver ANTES da Fase 1

`applyAgentToolPolicy` roda **depois** da seleção (linha 950) e é uma **interseção**: só sabe
subtrair. Isso **não é acidente**. Está decidido e escrito em `docs/agent-corrections-plan.md:157`:

> *"An empty `allowedTools` preserves the current eligible tool set. A non-empty list is a strict
> cap: eligible tools intersected with profile tools. An empty intersection remains empty; it never
> falls back to all."*

Ali a allow-list é uma **trava de segurança**. Aqui, o objetivo declarado ("o perfil substitui o
`project-toolkit`") faz dela uma **fonte de configuração**. As duas coisas são incompatíveis, e a
diferença é observável: com trava, um perfil que peça uma ferramenta fora do kit **não a recebe**.

**Decisão do dono, em aberto:** o perfil é trava ou é fonte?

- **Trava** (o que está escrito e implementado): o `project-toolkit` continua definindo o que é
  elegível, e o perfil só reduz. Custa pouco, mas **não resolve a queixa original** — o conteúdo
  continua no YAML.
- **Fonte** (o que este plano pedia): o perfil define o conjunto, o `project-toolkit` vira default de
  quem não tem perfil. Resolve a queixa e **exige revisar a decisão do `agent-corrections-plan`**,
  porque deixa de haver teto vindo do código.

Seja qual for a escolha, **um defeito real sobra nos dois caminhos**: interseção vazia devolve
`ArrayNode` vazio sem guarda nenhuma, e turno vazio por ferramenta ausente é o defeito documentado
três vezes nos comentários desta região (`AgentService:1073`, `1186-1189`). A regra escrita ("never
falls back to all") está certa em recusar o fallback — cair no kit inteiro seria escalar privilégio
por configuração errada. Mas mandar `tools: []` também não serve. **O caminho correto é falhar a run
com erro visível**, nomeando o perfil e as ferramentas que não existem no registry.

### Restrição que não pode ser perdida: o prefixo estável

O kit fixo existe por motivo medido — prefixo estável de prompt para o cache do llama.cpp
(`AgentService:1073`). Com `allowed_tools` virando lista livre por usuário, essa propriedade morre a
menos que a lista seja **canonicalizada em ordem estável** antes de virar payload. Sem isso a Fase 1
devolve latência que o projeto já tinha ganho.

### Estruturas que já existem

| Existe | Situação |
|---|---|
| Tabela `agent_profiles` | 1 registro ("Generalista"); campos `allowed_tools`, `system_instructions`, `model`, `triggers` |
| `AgentRoutingService` | escolhe o perfil — chamado só pelos dois serviços de plano, nunca pelo chat |
| `RunToolPolicyRegistry` | allow-list por run, com teste; alimentada só pelo caminho de plano |
| `AgentPermissionService` + `agent_permission_rules` | motor de permissões correto, no lugar certo |
| `avento.agent.project-toolkit` (YAML) | 14 ferramentas fixas, **iguais para todos os agentes** |

### Estado do AgentService

Reproduzível com `grep`/`wc` sobre
`back/avento/avento-agent/src/main/java/com/avento/service/AgentService.java`:

```
3.637 linhas · 122 métodos declarados · 487 linhas de comentário
41 campos private final — 26 deles configuração via @Value, ~15 colaboradores

finishTurn                    241 linhas   0 testes
selectToolsForCurrentRequest  107 linhas   0 testes
runTurn (o laço)               64 linhas   0 testes
```

Os 727 testes cobrem as bordas. **O miolo do agente não tem teste nenhum.**

---

## Decisões já tomadas pelo dono do projeto

- Mapper **genérico** com `BeanUtils` do Spring — um só, para qualquer classe. Recusado MapStruct.
- **Lombok em todas as classes** (hoje: 31 de 389 arquivos `.java` de produção).
- Objetos **separados por pasta**.
- Agente **Generalista padrão** para todo usuário; demais agentes por usuário.
- Ferramentas **vêm do Docker MCP**; quem cria o agente escolhe quais.

⚠️ Risco registrado do `BeanUtils`: ele casa campo por reflexão em runtime. Renomear um campo para de
copiar **sem erro de compilação** — o valor chega `null` e ninguém percebe. É a mesma família de
defeito que consumiu a sessão de 02–08/08 (`index` vs `index-name`, três modelos gravados sem
chamador, `num_ctx` descartado na tradução). Decisão do dono, registrada para quando morder.

---

## Fase 0 — Rede de segurança (bloqueante) ✅ CONCLUÍDA em 08/08/2026

Sem isto, qualquer refatoração é no escuro. Foi assim que o aviso de provedor quebrou em 08/08.

- [x] Commitar os 58 arquivos pendentes, em blocos separados — 12 commits
- [x] Testes de **caracterização** de `finishTurn`, `selectToolsForCurrentRequest` e `runTurn` —
      25 testes em 3 classes + `AgentServiceCharacterizationHarness`
- [x] Confirmar suíte verde: **752 testes, 0 falhas, 8 pulados** (eram 727)

`AgentService.java` não teve **uma linha** alterada — a rede foi construída inteiramente por
reflexão, de propósito: mudar visibilidade para testar já seria a mudança que a rede deveria estar
cobrindo.

### Dois achados registrados como teste, não corrigidos

1. **A assinatura de chamada repetida inclui contexto injetado.** `withExecutionContext` acrescenta
   `_userId` e `_runId` aos argumentos **antes** de `recordToolOutcome` montar a assinatura. Ela não
   é, portanto, função apenas do que o modelo pediu.
2. **⚠️ A orientação de chamada repetida é inalcançável para ferramenta que falha.** O `if` de
   `consecutiveIdenticalToolCalls >= 2` está **depois** do de `consecutiveToolFailures >= 2`
   (`AgentService:2110` e `2124`), e as duas contagens sobem juntas. Uma ferramenta chamada de novo
   com os mesmos argumentos e que falha de novo **sempre** retorna na guarda de falha. A orientação
   só roda para ferramenta que teve sucesso duas vezes idênticas.

   Não corrigido — caracterização não conserta. O teste
   `theRepeatedFailureGuardIsCheckedBeforeTheRepeatedCallGuidance` trava a **ordem** das duas
   guardas: se alguém inverter, ele falha e a decisão volta à mesa.

### Ramo não coberto

O corpo da orientação de chamada repetida (o texto do nudge) não é exercitado, pela razão acima.
Cobri-lo exigiria uma ferramenta que tenha sucesso no harness, e nenhuma de arquivo tem —
`WorkspaceAccessService` não é injetado. Montar o serviço de workspace inteiro é mais
infraestrutura do que uma rede de segurança justifica.

## Fase 1 — O perfil manda no agente *(era Fase 3)*

Esta é a fase que resolve a queixa original ("está tudo no código"). Subiu para primeira porque a
maior parte dela já está construída — ver "A ligação perfil → ferramentas JÁ EXISTE".

**Passo 0 — resolver o conflito trava vs. fonte** (ver a seção acima). Nada abaixo faz sentido antes.

- [ ] **Decisão do dono:** o perfil é trava de segurança ou fonte de configuração?
- [ ] Se **fonte**: atualizar `docs/agent-corrections-plan.md:157`, que hoje decide o contrário. Doc
      contradizendo doc é como este plano nasceu errado
- [ ] Se **fonte**: `applyAgentToolPolicy` deixa de ser filtro de saída — `filterToolsByName(catálogo
      completo, allowed)` no lugar do ramo do `project-toolkit`
- [ ] Se **trava**: o `project-toolkit` **fica** no YAML, e a Fase 1 entrega bem menos do que a queixa
      original pedia. Registrar isso em vez de fingir que resolveu

**Nos dois caminhos:**

- [ ] Interseção/resolução vazia **falha a run com erro visível**, nomeando o perfil e as ferramentas
      inexistentes. Nem `tools: []` (turno vazio), nem fallback pro kit inteiro (escala privilégio)
- [ ] Validar `allowed_tools` contra o registry real **na gravação do perfil** — já é item não marcado
      em `docs/codex-review-plan.md:145`. Barrar na escrita é melhor que descobrir na run
- [ ] Canonicalizar a ordem da lista do perfil antes de montar o payload — preserva o cache de prompt
- [ ] Teste de caracterização primeiro: hoje, allow-list que não intersecta o kit devolve vazio.
      Fixar esse comportamento antes de mudá-lo

**Ligar o caminho de chat (o fio que falta):**

- [ ] Chat resolve o perfil via `AgentRoutingService` como o caminho de plano já faz
- [ ] Registrar a allow-list na run: `toolPolicyRegistry.allow(runId, …)`
- [ ] `system_instructions` do perfil entram na montagem do prompt (espelhar `PlanExecutionService:279`)
- [ ] `model` do perfil participa da cadeia de resolução (hoje: provedor → seletor → default)

**Fechamento:**

- [ ] Criar o **Generalista** no cadastro de usuário (espelhar `RootUserSeeder`)
- [ ] Motor de permissões passa a ver o perfil: "esta ferramenta pertence a este agente?"
- [ ] Decidir o comportamento quando `allowed_tools` nomeia ferramenta de servidor MCP desconectado
      (ignorar em silêncio vs. avisar) — **em aberto**
- [ ] Remover `avento.agent.project-toolkit` do YAML **por último**, quando o perfil já cobrir o caso

## Fase 2 — Servidores MCP em container

⚠️ **Os oito não são iguais.** A versão anterior deste plano tratava como um bloco só. Em
`McpServerCatalogService`:

| Servidor | Lançamento hoje | Troca |
|---|---|---|
| `fetch` (`:323`), `time` (`:293`), `memory` (`:286`), `duckduckgo`, `sequentialthinking` (`:292`) | `uvx` / `npx` | ✅ limpa |
| `git` (`:331`) | `uvx mcp-server-git --repository <root>` | ⚠️ caminho do host não existe no container |
| `filesystem` (`:267`) | `npx … <roots>` | ⚠️ idem |
| `playwright` (`:316`), `puppeteer` (`:322`) | `npx` | ⚠️ em container = navegador sem tela |

- [ ] Trocar primeiro os **cinco limpos** por `docker run --rm -i mcp/<nome>`
- [ ] `git` e `filesystem`: decidir a montagem de volume das raízes do workspace. **Isto é decisão de
      segurança, não detalhe de linha de comando** — montar pasta do usuário dentro de container
      precisa de escopo explícito. Não fazer junto com os cinco limpos
- [ ] `playwright`/`puppeteer`: manter em `npx` até decidir o que acontece com as ferramentas de aba
      de navegador (`open/close_browser_tab`, hoje nunca exercitadas)
- [ ] Guarda: se o Docker não responder, cair no `npx`/`uvx` (o servidor continua disponível)
- [ ] Teste de integração com `mcp/fetch` — o ciclo já foi provado à mão, falta prendê-lo

## Fase 3 — Interface de agentes *(era Fase 4)*

- [ ] Tela de criação/edição de agente
- [ ] Seletor de ferramentas alimentado pelo catálogo real (locais + servidores MCP conectados)
- [ ] Aviso de custo: cada ferramenta custa ~250 tokens de schema **em toda rodada**

## Fase 4 — Fundação de código *(era Fase 1)*

Desceu para quarta: **não é pré-requisito de nenhuma outra fase**. É a mais longa, a de maior risco
(ver o aviso do `BeanUtils`) e a de menor retorno. Pode correr como trilho paralelo, sem bloquear.

- [ ] `BeanCopier` (utilitário genérico com `BeanUtils.copyProperties`), em `com.avento.support`
      - `<T> T to(Object origem, Class<T> destino)` e `<T> List<T> toList(...)`
      - guarda sugerida: log em `debug` dos campos do destino que ficaram nulos
- [ ] Lombok nas classes restantes (358), começando por `model/` e `dto/`
- [ ] Padronizar pastas: decidir entre por-tipo (`dto/`, `model/`) e por-funcionalidade
      (`service/provider/`, `service/plan/`) — hoje é misto. **Em aberto**

## Fase 5 — Extração do AgentService (opcional, depois das anteriores)

Extrair por **motivo de mudança**, não por tamanho:

| Sai | Vira | Muda quando |
|---|---|---|
| `finishTurn` (241 linhas) | `TurnOutcome` | muda comportamento de falha/retry |
| `selectToolsForCurrentRequest` | `ToolSelection` | muda catálogo ou política de custo |
| `buildOllamaRequest` (98 linhas) | `ModelTransport` (já existe) | entra provedor novo |
| `executeToolCall` | `ToolInvocation` | muda permissão ou gateway |

Sobra o laço: mandar, ver, decidir se continua. Deve caber em ~300 linhas.

⚠️ **Não reescrever do zero.** Os 487 linhas de comentário são autópsias de defeitos reais em
produção (escape de workspace por symlink, cache de prompt quebrado por lista variável, turno vazio
por ferramenta ausente). Reescrever joga fora as lições e elas voltam. Extrair leva o "porquê" junto,
para o javadoc da classe pequena ou para `docs/aprendizados/`.

---

## O que NÃO fazer

- Não pôr o **laço** em tabela. Conteúdo que o modelo lê vai para o banco; decisão que o sistema toma
  fica em código. Lógica em tabela custa compilador, teste e depurador.
- Não perseguir o Docker MCP Toolkit. Medido: catálogo não contém os `mcp/*`, e a interface não
  reconhece o `registry.yaml`. O `docker run -i` resolve sem ele.
- Não refatorar antes da Fase 0.
- **Não ligar o chat ao `RunToolPolicyRegistry` antes de decidir trava vs. fonte.** A interseção atual
  só subtrai; ligar como está produz toolset silenciosamente menor — e possivelmente vazio.
- **Não fazer a interseção vazia cair no kit inteiro.** `agent-corrections-plan.md:157` está certo:
  fallback pra tudo é escalada de privilégio por configuração errada. Falhar com erro visível.
- Não trocar `git`/`filesystem` para container junto com os cinco limpos. São decisão de segurança.
- Não deixar dois docs decidindo o mesmo ponto em direções opostas. Foi assim que este plano nasceu
  recomendando inverter uma trava de segurança sem saber que ela era deliberada.
