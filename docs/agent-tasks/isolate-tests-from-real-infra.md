# Impedir que a suíte escreva na infraestrutura real

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.
> Rode tudo a partir de `back/avento`.

📖 **O `AGENTS.md` da raiz manda.** Onde ele e esta spec divergirem, ele vence e você reporta.

**Medido nesta máquina em 10/08/2026, com Postgres e Redis de pé.**

---

## 1. O problema

A suíte grava em banco e Redis **de produção do desenvolvedor**. Não é sujeira: um dos vazamentos
agenda trabalho autônomo real.

### O caso grave: tarefas do Cowork

O `ToolIntegrationTest` criava tarefas agendadas de verdade na tabela `scheduled_tasks`:

```
name: teste-integracao   cron: 0 0 3 * * *   status: ACTIVE
prompt: "Resumir o dia."  next_run_at: amanhã 03:00
```

**Uma por rodada da suíte.** Havia **oito** acumuladas, todas `ACTIVE`. Com o Avento de pé às 3h, o
`CronTaskScheduler` executaria as oito — cada uma disparando run autônoma em modo Cowork, que
**auto-aprova ferramentas** (`RunToolPolicyRegistry.markAutonomous`).

As oito foram apagadas à mão em 10/08. **A causa continua:** rodar a suíte cria de novo.

### O caso volumoso: Redis

**477 manifestos + 477 versões** de RAG, todos de `@TempDir` do JUnit
(`/private/var/folders/.../T/junit-…`). Amostrei 60; os 60 eram temporários. **Não expiram.**

### Já resolvido, serve de modelo

O `ToolIntegrationTest` já aponta o cache de schemas MCP para caminho temporário, via `properties` no
`@SpringBootTest`. Mesmo padrão vale aqui.

---

## 2. O que NÃO está quebrado

- **A suíte passa: 811 testes, 0 falhas.** Esta tarefa não pode mudar isso.
- **Os testes estão certos no que verificam.** O defeito é onde eles gravam, não o que afirmam.
- O `ToolIntegrationTest` só roda com a infra de pé (`@EnabledIf("infrastructureIsUp")`) — **mantenha
  essa condição.**

---

## 3. Fatos que restringem a solução

### 3.1. Rollback não alcança o Redis

`@Transactional` desfaz o que foi ao Postgres. **O Redis não participa da transação JPA** — o que foi
gravado lá fica.

Então são **dois mecanismos**, e confundi-los deixa metade do lixo:

| Onde | Mecanismo |
|---|---|
| Postgres (`scheduled_tasks`, e o que mais o teste criar) | `@Transactional` com rollback |
| Redis (`avento:rag:manifest:`, `avento:rag:version:`) | `@AfterEach` apagando as chaves criadas |

**Decisão do dono, tomada:** usar os dois.

### 3.2. `@Transactional` em `@SpringBootTest` com `RANDOM_PORT` não funciona como se espera

O teste sobe servidor web em porta real e chama por HTTP. A requisição roda em **outra thread**, com
transação própria — a transação do método de teste **não a envolve**, e o rollback não desfaz nada.

**Suposição minha, meça antes de escolher:** não verifiquei se o `ToolIntegrationTest` cria a tarefa
por HTTP ou chamando o serviço direto. **Leia o teste primeiro.**

- Se cria **chamando o serviço/repositório direto** → `@Transactional` funciona, use.
- Se cria **por HTTP** → rollback não alcança; use `@AfterEach` apagando por identificador, ou aponte
  o teste para um banco separado.

**Reporte qual dos dois casos é**, com a linha do teste. Essa é a informação mais importante da T1.

### 3.3. Apague por identificador, nunca por tabela

A tentação é `DELETE FROM scheduled_tasks` no `@AfterEach`. **Não faça.** Esse banco tem dados do
desenvolvedor; apagar a tabela apaga o trabalho dele.

Apague **só o que o teste criou**, por chave própria: id devolvido na criação, ou nome exclusivo do
teste. O mesmo vale no Redis — apague as chaves daquele `projectKey`, não `avento:rag:*`.

### 3.4. `@TempDir` muda a cada execução

As chaves do Redis derivam de um `projectKey` que é hash do caminho, e o caminho é um `@TempDir`
diferente a cada rodada. **Guarde o `projectKey` durante o teste** para apagar no `@AfterEach` — não
tente adivinhar depois por padrão.

---

## 4. Decisões de projeto — não renegociar

1. **Dois mecanismos:** rollback para Postgres onde funcionar, `@AfterEach` para Redis. (3.1)
2. **Apagar por identificador do teste**, jamais por tabela ou prefixo largo. (3.3)
3. **Não afrouxar asserção nenhuma.** O que os testes verificam continua igual.
4. **Manter `@EnabledIf("infrastructureIsUp")`.**
5. **Se `@Transactional` não alcançar** (3.2), diga no relato e use limpeza explícita — não finja que
   resolveu.

---

## 5. Tarefas, em ordem

### T1 — Ler e reportar antes de mexer

Para cada teste que toca infra real, diga **o que ele cria e por qual caminho**:

`ToolIntegrationTest`, `RagServiceBatchingTest`, `UserSettingsServiceTest`, `ToolCatalogServiceTest`,
`ConversationContextCacheTest`, `AgentRunWorkerTest`, `RunEventStreamServiceTest`,
`LocalFallbackTest`, `EffectiveProviderKindTest`.

Diga em especial: **quem cria as tarefas agendadas, e se por HTTP ou por chamada direta** (3.2).

Grave em `docs/agent-tasks/_test-leaks-inventory.md` e **reporte antes de alterar código**.

Commit próprio.

### T2 — Fechar o vazamento das tarefas agendadas

É o mais grave. Aplique o mecanismo que a T1 indicou.

**Critério:** rodar a suíte duas vezes seguidas e a contagem de `scheduled_tasks` **não muda**.

```bash
docker exec avento-postgres psql -U avento -d avento -c "SELECT count(*) FROM scheduled_tasks;"
```

### T3 — Fechar o vazamento do Redis

`@AfterEach` apagando manifesto e versão do `projectKey` que o teste usou (3.4).

**Critério:** rodar a suíte duas vezes e a contagem de `avento:rag:manifest:*` **não muda**.

```bash
docker exec avento-redis-stack redis-cli --scan --pattern 'avento:rag:manifest:*' | wc -l
```

### T4 — Limpar o que já ficou

As 477+477 chaves de `@TempDir` continuam lá. Apague **apenas** as cujo `projectRoot` aponta para
diretório temporário (`/private/var/folders/` ou `junit-`).

**Confira antes de apagar** e diga quantas eram. As tarefas agendadas já foram removidas à mão.

### T5 — Verificação

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

---

## 6. Validação

- **≥811 testes, 0 falhas**
- duas rodadas seguidas: `scheduled_tasks` **não cresce**
- duas rodadas seguidas: `avento:rag:manifest:*` **não cresce**
- nenhuma asserção afrouxada

---

## 7. Fora de escopo — não faça

- **Não apague tabela nem prefixo largo.** (3.3) O banco tem dados do desenvolvedor.
- **Não desabilite teste para parar o vazamento.** `@Disabled` para a suíte não sujar troca um
  problema por confiança falsa, que é pior.
- **Não remova `@EnabledIf("infrastructureIsUp")`.**
- **Não troque o que os testes verificam.** O defeito é onde gravam.
- **Não suba a aplicação** para testar à mão. A máquina tem 16 GB com outros projetos.
- **Não commite `src/main/resources/agent/policies/`.**

---

## 8. Entrega

```
docs(agent-tasks): inventory what each test writes to real infrastructure
test(app): stop the suite from scheduling real Cowork tasks
test(rag): delete the Redis keys each test created
chore: remove the test leftovers already in Redis
```

No relato:

- **por qual caminho a tarefa agendada é criada** — decide tudo (3.2)
- as duas contagens, antes e depois de duas rodadas
- quantas chaves antigas foram apagadas na T4
