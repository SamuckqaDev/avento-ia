# Extrair o laço de execução de ferramentas do `finishTurn`

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.
> Rode tudo a partir de `back/avento`.

📖 **O `AGENTS.md` da raiz manda.** Onde ele e esta spec divergirem, ele vence e você reporta.

**Medido nesta máquina em 10/08/2026.** O que é suposição está marcado.

---

## 1. O problema

`AgentService` tem **3.470 linhas**, e o `finishTurn` sozinho tem **241** — o maior método do
projeto. Medido, os cinco maiores:

```
241  finishTurn                 :1819
104  buildOllamaRequest         :945
 92  detectDirectSystemAutomationRequest
 81  executeApprovedTool
 76  streamChatResolved
```

Dentro do `finishTurn` há uma costura nítida:

| Linhas | O quê |
|---|---|
| 1819–1945 (~127) | **decisão** — repetir? avisar? parar? sintetizar? |
| 1946–2060 (~114) | **execução** — o laço `for (ToolCall toolCall : toolCalls)` |

São dois motivos de mudança diferentes no mesmo método. A decisão muda quando muda política de
falha/retry; a execução muda quando muda permissão, mídia ou limite de chamadas.

**Contexto honesto:** o `AgentService` **cresceu** de 3.322 para 3.470 linhas na sessão de 08–10/08,
porque a ligação do perfil foi parar dentro dele. Esta tarefa começa a devolver.

---

## 2. O que NÃO está quebrado

- **A suíte passa: 810 testes, 0 falhas.**
- **Existe rede densa para este método:** `AgentServiceFinishTurnCharacterizationTest` tem **12
  testes** que travam os ramos do `finishTurn`, incluindo a rejeição de path `/`, a guarda de falha
  repetida e a ordem entre as duas guardas. Eles são o critério.
- As extrações anteriores (`AgentToolSelector`, `TurnEndPolicy`) estabeleceram o padrão. **Siga o
  mesmo.**

---

## 3. Fatos que restringem a solução

**Leia antes — a solução "mais bonita" quebra a prova.**

### 3.1. O laço MUTA o estado, e isso não muda nesta tarefa

Medido: dentro das 114 linhas, o laço escreve em `state.executedToolCalls`,
`state.consecutiveToolFailures`, `state.lastFailedTool`, `state.lastToolCallSignature`,
`state.consecutiveIdenticalToolCalls`, e mexe em `planApprovedRuns`.

A tentação é "melhorar": devolver um resultado imutável e o chamador aplica. **Não faça.** Isso é
redesenho de propriedade de estado, não extração — muda comportamento em pontos que os testes não
cobrem, e você perde a única prova disponível de que nada mudou.

**Decisão: a classe nova recebe o `AgentRunState` e o muta, exatamente como hoje.** É mover, não
redesenhar. O redesenho é outra tarefa, depois, com a extração já feita e provada.

### 3.2. `AgentRunState` e `TurnCapture` são classes internas privadas

Medido: as duas são `private static class` dentro do `AgentService` (por volta de `:3579` e `:3619`
no arquivo original; confirme a linha atual). Uma classe fora do `AgentService` **não as enxerga**.

Duas saídas, e a escolha é sua com o motivo no commit:

- **(a)** tornar as duas package-private (tirar `private`) — mínimo, e o pacote é o mesmo;
- **(b)** extrair `AgentRunState` para arquivo próprio no mesmo pacote.

**Recomendo (a)** nesta tarefa: é a menor mudança que destrava, e mover o estado para arquivo
próprio é decisão que merece tarefa própria. **Não torne públicas.**

### 3.3. O laço emite no `FluxSink` — passe o sink, não invente evento

O laço chama `sink.next(...)` em vários pontos com textos exatos que os testes verificam (
`agent.limit.reached`, `tool.rejected`, `agent.tool.repeated_failure`). **Copie os textos palavra por
palavra.** Um "e" trocado por "ou" quebra teste e, pior, muda o que o usuário lê.

### 3.4. Mantenha o método privado no `AgentService` como delegador

Foi assim que as duas extrações anteriores foram **provadas**: o método privado fica, com a mesma
assinatura, chamando a classe nova. Os 12 testes de caracterização continuam batendo nele e passam
**sem uma linha alterada**.

Se você precisar mexer em teste para a extração passar, a extração mudou comportamento. **Pare e
reporte.**

---

## 4. Decisões de projeto — não renegociar

1. **Classe nova:** `com.avento.service.agent.ToolInvocationLoop`.
2. **Recebe e muta o `AgentRunState`** (ver 3.1). Nada de resultado imutável nesta tarefa.
3. **Textos de evento copiados literalmente** (ver 3.3).
4. **Delegador fica no `AgentService`** (ver 3.4).
5. **Nenhum teste alterado.** É o critério de aceite, não uma preferência.

---

## 5. Tarefas, em ordem

### T1 — Destravar a visibilidade

Aplique a saída escolhida em 3.2. Só isso, e compile.

Commit próprio. `mvn -pl avento-agent -am test-compile` verde.

### T2 — Mover o laço

Crie `ToolInvocationLoop` com um método que receba o que o laço usa hoje: `messages`, `state`,
`sink`, a lista de `ToolCall`, e os colaboradores (`permissionService`, `timelineService`,
`planApprovedRuns`, e os métodos auxiliares de mídia/permissão que hoje são privados do
`AgentService`).

**Os auxiliares que só o laço usa vão junto** — `usesFilesystemRoot`, `isMediaGenerationTool`,
`isSuccessfulMediaGeneration`, `emitGeneratedMediaCompletion`, `emitMediaGenerationFailure`,
`recordToolOutcome`. **Os que o resto do `AgentService` também usa ficam lá** e são passados ou
chamados de volta; diga no relato quais foram cada caso.

O `finishTurn` passa a chamar a classe nova no lugar do `for`.

**Critério:** os 12 testes de `AgentServiceFinishTurnCharacterizationTest` passam **sem alteração**, e
a suíte fica em 810.

### T3 — Verificação e relato

Rode a suíte completa. Relate linhas do `AgentService` e do `finishTurn` antes e depois.

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

---

## 6. Validação

- **810 testes, 0 falhas**
- `git diff --stat` **não mostra nenhum arquivo de teste** — é o critério mais importante
- `AgentService` menor; `finishTurn` reduzido a ~127 linhas

---

## 7. Fora de escopo — não faça

- **Não redesenhe a propriedade do estado.** Ver 3.1. Resultado imutável é outra tarefa.
- **Não altere teste nenhum.** Se precisou, o comportamento mudou — pare e reporte.
- **Não mude texto de evento nem de mensagem ao usuário.** Ver 3.3.
- **Não torne `AgentRunState` público.** Package-private basta e limita o estrago.
- **Não extraia a parte de DECISÃO do `finishTurn` junto.** Ela já tem casa (`TurnEndPolicy`) e
  misturar as duas torna o diff irrevisável. Uma extração por vez.
- **Não anote `@Disabled` nem afrouxe assert.** Aqui seria especialmente grave: os 12 testes são a
  única prova de que 241 linhas de comportamento sobreviveram.
- **Não commite `src/main/resources/agent/policies/`.**

---

## 8. Entrega

Conventional commits, **em inglês**:

```
refactor(agent): make the run state reachable from the same package
refactor(agent): extract the tool execution loop out of finishTurn
```

No relato:

- linhas do `AgentService` e do `finishTurn`, antes e depois
- quais auxiliares foram junto e quais ficaram, com o motivo
- confirmação de que **nenhum** arquivo de teste foi tocado
