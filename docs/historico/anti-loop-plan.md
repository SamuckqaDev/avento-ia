# Plano de Implementação — Guarda Anti-Repetição (loop de ferramenta "bem-sucedida")

> **Para o Antigravity implementar.** Depois: Codex revisa, Claude faz a revisão final.
> **Padrões obrigatórios:** código novo em **inglês** (comentários, logs, nomes); **mensagens que
> aparecem pro usuário no chat em PT-BR**. Clean code, performático (`HashMap`/`Set`). Doc + testes
> sempre. Não commitar `agent/policies/*.md`. Não dar push.

---

## 0. FASE 0 — Varredura (antes de codar)

1. `git status` + `git log --oneline -8`. Reconcilie mudanças locais.
2. `cd back/avento && mvn -q compile && mvn test` → **espere ~420 verdes, 0 falhas**. Se falhar, pare e reporte.
3. Leia o que **já existe** e NÃO reimplemente:
   - `AgentService.recordToolOutcome(...)` + `REPEATED_TOOL_FAILURE_LIMIT` → guarda que já para quando a **mesma ferramenta falha 2× seguidas**. Você vai ADICIONAR uma guarda irmã para **repetição bem-sucedida**.
   - `AgentService.finishTurn(...)` → onde as ferramentas são executadas e onde o loop decide continuar (`forward(runTurn(..., round + 1), ...)`). É aí que a nova guarda entra.
   - `AgentService.executeToolCall(...)` → executa uma ferramenta e devolve o resultado.
   - `AgentRunState` (classe interna estática no fim de `AgentService`) → estado por run.
   - `PlanExecutionService` → executa tarefas do plano; `MAX_ATTEMPTS = 2` (re-tenta a tarefa 2×).

---

## 1. O problema (verificado nos dados reais, não no diagnóstico simplificado)

Um plano travou no passo 7 (`Update environment configuration for auth`). Dados do
`agent_timeline_events`:

- O modelo gravou **6 vezes o MESMO arquivo** (`.../standard-auth/app-integration-example.ts`) —
  todas com **sucesso**.
- Intercalado com `search_files` e um `read_file` que **falhou** (`.env` na raiz não existe — é
  monorepo, o env fica no microsserviço). Ou seja, é **flailing**, não repetição idêntica consecutiva.
- **Por que nenhuma guarda pegou:**
  - A guarda existente (`recordToolOutcome`) só conta **falhas consecutivas**. Como o `write_file`
    **deu sucesso** toda vez, o contador zerava.
  - O teto de rodadas (`maxToolRounds`) para UM run, mas o plano **re-tenta** a tarefa (`attempts`),
    e cada tentativa recomeça com orçamento novo → o loop reabre.

**Causa raiz:** o modelo local não converge no passo (não resolveu o caminho do `.env`), fica
repetindo uma ação **bem-sucedida** sem progresso, e **não existe guarda para repetição de sucesso**.

---

## 2. O fix — Guarda de repetição de ação (por run)

Detectar quando a **mesma ferramenta com o mesmo alvo** é chamada N vezes **no mesmo run** (sucesso
OU falha, consecutivas OU não) e parar, avisando o usuário. Complementa a guarda de falha existente.

### 2.1 Assinatura da chamada (fingerprint)
Crie um método privado em `AgentService`:
```java
// Stable signature of a tool call for loop detection: tool name + its main target argument
// (path/url/appName) when present, otherwise the normalized full arguments.
private String toolCallSignature(ToolCall toolCall) { ... }
```
- Para ferramentas de arquivo (`write_file`, `edit_file`, `read_file`, `read_document`,
  `create_directory`, `delete_file`, `delete_directory`): use `name + ":" + path`.
- Para `open_url`, `open_browser_tab`, `close_browser_tab`: `name + ":" + url`.
- Para `open_app`, `close_app`: `name + ":" + appName`.
- Caso geral: `name + ":" + <argumentos normalizados como string estável>`.
- **Importante:** para `write_file`, a assinatura NÃO deve incluir o `content` — escrever o mesmo
  caminho 3× é o sinal de loop, mesmo que o conteúdo mude um pouco a cada rodada.

### 2.2 Contagem no estado do run
Em `AgentRunState`, adicione:
```java
final java.util.Map<String, Integer> toolCallCounts = new java.util.HashMap<>();
```

### 2.3 Registrar após cada execução
Nos **dois** pontos onde `executeToolCall(...)` é chamado dentro de `finishTurn`, logo depois de
`recordToolOutcome(...)`, incremente a contagem:
```java
int repeats = state.toolCallCounts.merge(toolCallSignature(toolCall), 1, Integer::sum);
```
Guarde o maior `repeats`/assinatura atingido para decidir depois (ou verifique o mapa ao final).

### 2.4 Parar quando repetir demais
Constante: `private static final int REPEATED_TOOL_CALL_LIMIT = 3;`

Depois do laço de execução das ferramentas, **antes** de `forward(runTurn(model, messages, state,
round + 1), sink, state)`, adicione uma guarda no mesmo estilo da guarda de falha repetida:
```java
if (max repeats de qualquer assinatura >= REPEATED_TOOL_CALL_LIMIT) {
    planApprovedRuns.remove(state.runId);
    sink.next(eventChunk("agent.tool.repeated_call",
        "Ação repetida sem progresso",
        "A mesma ação (" + assinatura + ") foi repetida " + repeats + "× sem avançar; parando."));
    sink.next(contentChunk("\n> Repeti a mesma ação `" + toolName + "` no mesmo alvo "
        + repeats + " vezes sem avançar — o passo não está convergindo. Parei aqui em vez de entrar"
        + " em loop. Verifique o alvo (ex.: o caminho pode não existir) e me diga como seguir.\n"));
    sink.complete();
    return;
}
```
(Mensagem ao usuário em PT-BR; código/log em inglês.)

---

## 3. Reforço no plano (opcional, mas recomendado): não reabrir o loop no retry

O `MAX_ATTEMPTS = 2` do `PlanExecutionService` já limita a re-tentativa, e a guarda por run corta
cada tentativa cedo. Para não desperdiçar a 2ª tentativa repetindo o mesmo loop:

- Em `PlanExecutionService`, quando um run termina porque bateu na guarda de repetição, **não
  re-tente cegamente**: marque a tarefa como `FAILED` com um `resultSummary` explicando o loop e
  **pause o plano** (em vez de gastar a 2ª tentativa no mesmo buraco).
- Como sinalizar: a run pode publicar/registrar um marcador de "stuck-repeat" (ex.: um evento
  `agent.tool.repeated_call` que o executor detecta), ou o executor inspeciona o resultado do run.
  Escolha o caminho mais limpo que já se encaixe no fluxo de eventos existente. Se não for trivial
  sem tocar muita coisa, **deixe só a guarda por run** (item 2) — ela já resolve 90% do problema.

---

## 4. Ação operacional imediata — cancelar o run travado

O plano 5 (passo 7) ainda está `RUNNING` em loop. Cancele para destravar:
- Pelo painel: botão **Cancelar** no plano.
- Ou via API: `POST /api/plans/5/cancel` (autenticado como o dono).
- Não é parte do código; é para o usuário destravar o chat agora.

---

## 5. Testes obrigatórios

- Torne o `toolCallSignature(...)` e a lógica de contagem **testáveis** (método package-private ou
  um pequeno componente), como foi feito com `MemoryExtractionService.parseFacts`.
- `signatureIgnoresWriteFileContentButKeepsPath`: dois `write_file` no mesmo path com conteúdo
  diferente → **mesma** assinatura.
- `signatureDiffersByTargetPath`: `write_file` em paths diferentes → assinaturas diferentes.
- `repeatedCallGuardTripsAtLimit`: registrar a mesma assinatura 3× → a guarda dispara (o run para);
  2× → não dispara.
- Não quebre os testes da guarda de falha existente.

---

## 6. Critérios de aceite

- [ ] Repetir a **mesma ferramenta no mesmo alvo 3×** num run (mesmo com sucesso) → o run **para** e
      explica no chat (PT-BR).
- [ ] A guarda de **falha consecutiva** existente continua funcionando (não regrediu).
- [ ] Assinatura de `write_file` ignora `content`, considera `path`.
- [ ] `mvn test` verde (≥ testes atuais) + `npm --prefix front run validate` verde; `spotless:apply`.
- [ ] Código novo em inglês; mensagens de chat em PT-BR.

## 7. NÃO faça
- ❌ Contar só falhas (o bug é repetição de **sucesso**).
- ❌ Exigir que as repetições sejam **consecutivas** (elas vêm intercaladas com search/read).
- ❌ Incluir o `content` do `write_file` na assinatura (senão o loop escapa mudando 1 caractere).
- ❌ Reescrever a guarda de falha existente — **adicione** a irmã.
- ❌ Commitar política; dar push.

## 8. Relatório de saída (para a revisão final)
Baseline verde (números), o que implementou (guarda por run; e se fez o item 3), como testou, e o
run travado foi cancelado? Nada commitado/pushado sem pedir.
