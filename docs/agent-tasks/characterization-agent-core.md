# Testes de caracterização do miolo do AgentService

> ## ✅ EXECUTADA em 08/08/2026 — não despachar de novo
>
> Feita à mão, não pelo Antigravity. Resultado: **752 testes, 0 falhas** (eram 727), 25 novos em
> 3 classes + `AgentServiceCharacterizationHarness`. `AgentService.java` intacto — `git status` do
> `src/main` vazio, que era o critério de aceite.
>
> | Classe | Testes |
> |---|---:|
> | `AgentServiceToolSelectionCharacterizationTest` | 9 |
> | `AgentServiceFinishTurnCharacterizationTest` | 12 |
> | `AgentServiceRunTurnCharacterizationTest` | 4 |
>
> **Um ramo não coberto e dois achados** estão registrados na Fase 0 de
> `docs/PLANO_AGENTE_CONFIGURAVEL.md`. O mais importante: a orientação de chamada repetida é
> inalcançável para ferramenta que falha, porque a guarda de falha repetida vem antes e as duas
> contagens sobem juntas.
>
> O documento fica como registro do que foi pedido e por quê.

> Spec de execução para agente. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: `feat/rag-index-on-workspace-registration`.
> Módulo: `back/avento/avento-agent`. Rode tudo a partir de `back/avento`.

Este é o item 2 da Fase 0 de `docs/PLANO_AGENTE_CONFIGURAVEL.md`. É **bloqueante**: nenhuma
refatoração do agente pode começar antes disto estar verde.

---

## 1. O problema

`back/avento/avento-agent/src/main/java/com/avento/service/AgentService.java` tem 3.637 linhas e
122 métodos. Os 727 testes da suíte cobrem as bordas. **Os três métodos que decidem o
comportamento do agente têm zero cobertura:**

| Método | Linha | Tamanho | Testes hoje |
|---|---:|---:|---:|
| `runTurn` | 802 | 64 linhas | 0 |
| `selectToolsForCurrentRequest` | 1047 | 107 linhas | 0 |
| `finishTurn` | 1899 | 241 linhas | 0 |

Isso já custou caro e está documentado: na sessão de 02–08/08 dois consertos geraram defeito novo
(`min(…, carregado)` prendendo a janela de contexto em 4096, e o aviso de provedor disparando alarme
falso), e o aviso de provedor quebrou porque ninguém tinha rede embaixo.

**Caracterização, não validação.** Estes testes fixam o comportamento **ATUAL**, inclusive o que
parecer errado. Se você achar um bug enquanto escreve, **não conserte** — escreva o teste que
descreve o que o código faz hoje, e anote o achado na seção de relato. Um teste de caracterização
que "corrige" o comportamento não serve para nada: o objetivo é detectar mudança acidental depois.

### O que eu medi

Rodei `git log`, `grep` e `wc` neste repo em 08/08/2026. Os números da tabela acima são
reproduzíveis.

**A suíte foi rodada agora, na branch base, e está verde.** Este é o seu ponto de partida — se algo
falhar antes de você escrever a primeira linha, não é seu:

```
cd back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
→ tests: 727 · failures: 0 · errors: 0 · skipped: 8 · exit 0
```

`DockerMcpGatewayLiveTest` fica de fora porque exige o Docker MCP Gateway de pé — e ele está
quebrado nesta máquina (medido; ver `docs/PLANO_AGENTE_CONFIGURAVEL.md`). Não tente consertá-lo.

### O que eu NÃO medi

Não rodei os três métodos isoladamente — é justamente o que esta spec manda construir. Se algum
comportamento descrito abaixo não se reproduzir no teste, **o código manda, não esta spec**:
escreva o teste que descreve o que você observou e reporte a divergência.

---

## 2. O que NÃO está quebrado

- **A suíte atual passa.** Não mexa em teste existente para acomodar teste novo.
- `AgentServiceDirectAutomationTest`, `AgentServiceCloudNoticeTest` e `AgentServiceContextBudgetTest`
  já resolveram o problema difícil de instanciar o `AgentService`. **Reaproveite o padrão deles**
  (seção 4), não invente outro.
- `PromptPrefixStabilityTest` é o exemplo de como este repo escreve javadoc de teste: sintoma,
  causa-raiz, medição, e por que o teste precisa existir. Siga esse tom.
- O `AgentPermissionService` e o `RunToolPolicyRegistry` estão corretos e têm teste. Não encoste.

---

## 3. Fatos que restringem a solução

**Leia antes de escrever código — as três saídas óbvias quebram o trabalho.**

### 3.1. Não torne os métodos públicos, e não extraia classe

A tentação é "isso seria testável se fosse público" ou "extrai `TurnOutcome` e testa direto". Ambas
mudam o código de produção — e o propósito destes testes é ser a rede **antes** de qualquer mudança.
Extrair é a Fase 5 do plano, e ela depende desta Fase 0.

Use reflexão, como o repo já faz:

```java
Method method = AgentService.class.getDeclaredMethod("selectToolsForCurrentRequest",
        ArrayNode.class, ArrayNode.class, /* AgentRunState */ Class.forName("com.avento.service.AgentService$AgentRunState"));
method.setAccessible(true);
```

Confirme a assinatura real no código antes de escrever cada `getDeclaredMethod` — errar o tipo dá
`NoSuchMethodException` em runtime, não erro de compilação.

### 3.2. `finishTurn` e `runTurn` são mutuamente recursivos — e reentram por `Flux`

`runTurn:853` chama `finishTurn` no callback de conclusão do stream. `finishTurn` chama
`forward(runTurn(...), sink, state)` em **quatro** pontos distintos (retry de turno vazio, retry com
toolset completo, síntese final por limite, e o avanço normal de rodada).

Consequência prática: **um teste ingênuo de `finishTurn` pode disparar uma rodada de verdade contra
o `ollamaBaseUrl`.** O harness existente já resolve isso apontando para `http://localhost:9` (porta
fechada) — mantenha. E prefira montar o `state` de forma que o caminho sob teste **não** recurse:
por exemplo, para testar o aviso de "nenhuma ferramenta executada", ligue `state.retriedEmptyTurn` e
`state.retriedWithFullToolset` antes, senão o método vai tentar outra rodada em vez de emitir o
aviso.

### 3.3. `selectToolsForCurrentRequest` chama embedding de verdade

`runTurn:805-807` avisa nos comentários: a montagem do request chama
`selectToolsForCurrentRequest` → `intentRouter.classify()`, **que faz uma chamada de embedding
síncrona**. O harness existente constrói
`new IntentRouter(toolRegistry, new IntentEmbeddingClassifier(null, 0.55, 2000), true)` — com
`null` no cliente, justamente para não sair chamando Ollama. **Copie isso.** Se um teste ficar
lento ou pendurado, é aqui.

### 3.4. Memória desta máquina

M2 Pro 16 GB, com outros projetos rodando. **Não suba container, não suba Ollama, não suba Redis**
para esta tarefa. Tudo aqui é teste unitário com mock. Se você achar que precisa de infra, você
entendeu errado a tarefa — pare e reporte.

---

## 4. Decisões de projeto — não renegociar

1. **Nenhuma linha de `AgentService.java` muda.** Zero. Se um teste só passa mudando produção, ele
   está errado ou achou um bug — nos dois casos, pare e reporte.
2. **Três arquivos novos, um por método**, em `back/avento/avento-agent/src/test/java/com/avento/service/`:
   - `AgentServiceRunTurnCharacterizationTest.java`
   - `AgentServiceToolSelectionCharacterizationTest.java`
   - `AgentServiceFinishTurnCharacterizationTest.java`
3. **O harness de construção do `AgentService` vira classe-base compartilhada**,
   `AgentServiceCharacterizationHarness.java`, no mesmo pacote. Hoje o construtor de 38 argumentos
   está copiado em `AgentServiceDirectAutomationTest:79-118`; copiá-lo mais três vezes é como o
   `withCloudNotice` acabou escrito duas vezes e esquecido numa delas.
   **Não altere `AgentServiceDirectAutomationTest` para usar a base** — isso é refatoração de teste
   existente, fora de escopo. A base nasce para os três novos.
4. **Javadoc obrigatório em cada teste**, no tom do `PromptPrefixStabilityTest`: o que trava, e por
   que alguém no futuro não deve "simplificar" o assert.
5. **Em caso de dúvida sobre o comportamento, o código manda.** Rode, observe, escreva o que
   observou.

---

## 5. Tarefas, em ordem

### T1 — `AgentServiceCharacterizationHarness`

Classe-base abstrata no pacote `com.avento.service`, em `src/test/java`. Extraia o corpo de
`AgentServiceDirectAutomationTest:55-118` (colaboradores mockados + a chamada
`new AgentService(...)` com os 38 argumentos) para campos `protected`.

**Copie os valores literais exatamente como estão lá** — inclusive `"http://localhost:9"`, o
`projectToolkit` `"directory_tree,read_file,write_file,edit_file,delete_file,terminal_run"`, o
`maxToolRounds` 6 e o `maxProjectTools` 18. Estes números fazem parte do comportamento que você está
caracterizando; trocar um por "um valor mais redondo" invalida o teste.

Exponha helpers `protected`:

```java
protected ArrayNode userMessages(String... contents)      // igual ao de PromptPrefixStabilityTest
protected ArrayNode toolsNamed(String... names)           // ArrayNode de {"name": ...} p/ alimentar a seleção
protected Object newRunState(...)                         // AgentRunState via reflexão, com os campos que o teste precisa
protected Object invokePrivate(String name, Class<?>[] sig, Object... args)
```

`AgentRunState` é classe interna — confirme o nome binário exato (`AgentService$AgentRunState` ou
aninhada em outro lugar) antes de escrever, e o modificador dos campos que você vai setar.

Commit próprio. Sem teste ainda; só tem de compilar: `mvn -pl avento-agent test-compile`.

### T2 — `AgentServiceToolSelectionCharacterizationTest`

O mais isolado dos três — comece por ele. Alvo: `selectToolsForCurrentRequest` (linha 1047).

Fixe, no mínimo, estes comportamentos observáveis (confirme cada um lendo 1047–1153 antes de
escrever o assert):

- Última mensagem de usuário **vazia ou ausente** → devolve o conjunto já selecionado, sem
  classificar (linha ~1064).
- **Mensagem casual** (`MessageText.isCasualUserMessage`) → devolve sem classificar (~1070).
- **Pedido de mockup/interface** (`imageIntentService.wantsInterfacePrototype`) → devolve
  **conjunto vazio**, e em particular **sem `generate_image`**. O comentário em 1077-1082 explica:
  é para o modelo escrever o bloco `ui-preview` em vez de gerar imagem cara e feia.
- **Com `state.workspaceRoots` não vazio** → o kit fixo do `projectToolkit` entra **inteiro**, e
  extras entram limitados por `PROJECT_TOOLKIT_EXTRA_LIMIT`. Trave que o kit sobrevive.
- **Prioridade não é exclusiva**: pedido de imagem prioriza `generate_image` **sem** excluir as
  demais (comentário em 1084-1087 registra que o retorno exclusivo anterior quebrava pedidos
  compostos). Trave isso — é regressão conhecida.
- **`capProjectKit`**: com o kit acima do teto (18), o `projectToolkit` inteiro sobrevive e o corte
  cai só sobre o que veio depois. Este é o defeito que saiu com 22 schemas em produção.

### T3 — `AgentServiceRunTurnCharacterizationTest`

Alvo: `runTurn` (linha 802). O método devolve `Flux<String>`; use `StepVerifier` se o
`reactor-test` já estiver no classpath — **verifique antes**; se não estiver, **não adicione
dependência** (fora de escopo), use `blockFirst(Duration)` como `AgentServiceDirectAutomationTest`
faz.

Fixe:

- A primeira coisa emitida é o evento `agent.round.started`, com o número da rodada e o nome do
  modelo no texto.
- Com o provedor inalcançável (`http://localhost:9`), o caminho de erro chama `handleStreamError` e
  **o Flux termina** — não fica pendurado. Trave o fim com timeout curto.
- `sink.onCancel` / `sink.onDispose` descartam **também** `state.subscriptions`. O comentário em
  855-858 diz por quê: sem o composite, cancelar a run deixava a requisição da próxima rodada viva
  no Ollama para sempre. Se não der para observar isso sem mudar produção, **escreva o teste que
  observa o que dá** e anote a limitação no javadoc — não mude produção para facilitar.

### T4 — `AgentServiceFinishTurnCharacterizationTest`

O maior. Alvo: `finishTurn` (linha 1899). Monte o `TurnCapture` e o `AgentRunState` por reflexão e
cubra os ramos **sem** deixar recursão real acontecer (ver 3.2).

Ramos a fixar, na ordem em que o método os avalia:

1. **Sem tool calls + texto vazio + `retriedEmptyTurn == false`** → emite `agent.round.retry` com o
   título "Resposta vazia — tentando de novo" e monta a mensagem de nudge com o pedido original.
2. **Sem tool calls + ação anunciada sem execução** → mesmo `agent.round.retry`, título "Ação
   anunciada sem execução — tentando de novo". Este ramo existe porque a guarda antiga exigia texto
   em branco e deixava passar o "vou criar o arquivo" sem criar nada.
3. **Texto vazio com `retriedEmptyTurn == true`** → emite o aviso em markdown
   ("encerrou o turno sem produzir resposta") e completa.
4. **`shouldWarnAboutNoToolExecution`** → evento `agent.no_tool_warning`.
5. **Mensagem casual com tool calls** → evento `tool.ignored` + a resposta "Oi! Estou por aqui." e
   completa **sem executar ferramenta**.
6. **Limite de rodadas atingido, `finalSynthesis == false`** → evento `agent.limit.reached` e uma
   última rodada **sem ferramentas** pedindo o fechamento. Trave que o flag impede isso de acontecer
   duas vezes — antes daqui saía só "Limite atingido" e minutos de leitura de arquivo eram jogados
   fora.
7. **`usesFilesystemRoot`** → evento `tool.rejected`, recusa em texto, `sink.complete()`. **Isto é
   segurança** (path `/`): se este teste for difícil, ele é o mais importante da lista.
8. **`consecutiveToolFailures >= REPEATED_TOOL_FAILURE_LIMIT`** → evento
   `agent.tool.repeated_failure` e parada.
9. **`consecutiveIdenticalToolCalls >= 2`** → acrescenta a mensagem de orientação em `messages`
   antes de avançar.
10. **`mediaGenerationAttempted`** → `agent.round.completed` e completa, **sem** rodada extra do
    modelo (o comentário registra: nenhuma explicação inventada pelo modelo é adicionada).

Se algum ramo exigir mudar produção para ser alcançável, **pule esse ramo, anote no javadoc da
classe e reporte**. Nove ramos travados valem mais que dez com uma mudança de produção junto.

### T5 — Verificação final (commit separado, com trava)

Rode a suíte inteira. **Se qualquer teste que passava antes falhar agora, pare e reporte** — não
ajuste o teste antigo, não anote `@Disabled`, não afrouxe assert. Um teste novo que quebra um velho
significa que você mudou comportamento sem querer, e essa informação vale mais que a tarefa fechada.

---

## 6. Validação

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn -pl avento-agent test-compile
```

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn -pl avento-agent test -Dtest='AgentService*CharacterizationTest'
```

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

Critério de aceite:

- Os três testes novos passam.
- A contagem total sobe (727 + os novos) e **nenhum teste anterior falha**.
- `git diff --stat` da entrega **não contém** `AgentService.java`.

Relate o número real de testes antes e depois, e cole a saída do último comando — inclusive se
falhar.

---

## 7. Fora de escopo — não faça

- **Não mude `AgentService.java`.** Nem formatação, nem import, nem javadoc. O `git diff` é o
  critério.
- **Não extraia `TurnOutcome`, `ToolSelection` nem `ToolInvocation`.** É a Fase 5 do plano e depende
  desta rede existir primeiro.
- **Não refatore `AgentServiceDirectAutomationTest`** para usar a base nova. Mexer em teste que
  passa, durante a tarefa de criar rede de segurança, é exatamente o risco que a rede existe para
  cobrir.
- **Não adicione dependência nova** (`reactor-test`, AssertJ extra, Testcontainers). Se faltar
  ferramenta, use o que tem e reporte.
- **Não anote `@Disabled` para fechar a tarefa.** Teste desabilitado para a suíte passar é pior que
  teste ausente: cria confiança falsa, que é a causa-raiz desta tarefa existir.
- **Não "conserte" comportamento estranho que encontrar.** Caracterize e reporte. Um teste de
  caracterização que descreve o comportamento corrigido não detecta regressão nenhuma.
- **Não suba container, Ollama, Redis ou o app.** A máquina tem 16 GB e outros projetos.
- **Não commite nada em `src/main/resources/agent/policies/`.** Convenção do repo: a versão
  experimental vive em `~/.avento/policies/`.

---

## 8. Entrega

Um commit por tarefa, conventional commits, **em inglês** (convenção do repo — ver `CONTRIBUTING.md`
e `AGENTS.md`).

Sugestão:

```
test(agent): share one harness for AgentService characterization tests
test(agent): pin how the agent picks tools for a request
test(agent): pin what a round emits and how it ends
test(agent): pin the eleven ways a turn can finish
```

No relato final, informe:

- contagem de testes antes e depois;
- **os ramos que você não conseguiu alcançar**, e o que faltou;
- **qualquer comportamento que te pareceu bug** — com `arquivo:linha`. Não conserte; só aponte.
  Esta lista é metade do valor da tarefa.
