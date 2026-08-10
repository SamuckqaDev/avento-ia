# Fazer cada decisão morar em um lugar só

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.

📖 **O `AGENTS.md` da raiz manda.** Onde ele e esta spec divergirem, ele vence e você reporta.

**Medido nesta máquina em 10/08/2026.** O que é suposição está marcado.

---

## 1. O problema

`docs/` tem **23 arquivos**. Medido por data do último commit:

| Quando pararam | Quantos | Linhas |
|---|---:|---:|
| 19/07 (dia 1 do repo) | 8 | 1.797 |
| 20–25/07 | 5 | 1.313 |
| 31/07 | 3 | 927 |
| agosto (vivos) | 5 | 1.574 |

**Mais da metade são planos congelados na primeira semana do projeto.** Muitos já foram executados,
alguns foram substituídos, e nenhum diz qual dos dois é.

### O dano é real, não estético

Isto já produziu uma recomendação errada nesta sessão. O `PLANO_AGENTE_CONFIGURAVEL.md` recomendava
inverter uma trava de segurança **sem saber que ela era deliberada** — a decisão estava escrita em
`agent-corrections-plan.md:157`, e os dois docs decidiam o mesmo ponto em direções opostas.

Documentação que se contradiz é **pior que ausente**: dá confiança falsa.

### E os docs congelados são citados DE DENTRO DO CÓDIGO

Medido:

| Doc | Citado por |
|---|---|
| `agent-corrections-plan.md` | **`AgentService.java`**, `AgentProfileToolPolicyTest.java`, e 4 docs |
| `historico/codex-review-plan.md` | 4 arquivos |
| `REDIS_EXECUTION.md` | 7 arquivos |
| `historico/IMPLEMENTATION_PLAN.md`, `historico/codex-agent-implementation-plan.md`, `historico/autonomous-agent-plan.md`, `INTERFACE_PROTOTYPING.md` | 3 cada |

**Um doc que o código aponta não é morto — é registro de decisão.** Arquivar sem atualizar o ponteiro
troca um problema por outro.

---

## 2. O que NÃO está quebrado

- **A suíte passa: 810 testes, 0 falhas.** Esta tarefa não toca em código de produção, exceto
  comentários que citam caminho de doc.
- **`ARCHITECTURE.md`, `HANDOFF.md`, `SETUP.md`, `FEATURES.md` e `PLANO_AGENTE_CONFIGURAVEL.md` estão
  vivos** — foram atualizados em agosto. **Não os arquive.**
- **`docs/aprendizados/` é o formato certo do projeto** e está em uso. Não encoste.

---

## 3. Fatos que restringem a solução

**Leia antes — duas das saídas óbvias destroem informação.**

### 3.1. Não apague nada

Estes docs contêm medições que custaram caro e não estão em outro lugar: números de latência,
decisões de segurança, caixas de verificação ainda abertas. Apagar perde o **porquê**, que é
exatamente o que este projeto vem tratando como o ativo mais valioso.

**Mover para `docs/historico/` sim. Apagar não.**

### 3.2. O `agent-corrections-plan.md:157` tem metade atual e metade revogada

Este é o caso mais delicado, e o motivo de a tarefa não ser "arquivar os antigos". O parágrafo diz:

> *"An empty `allowedTools` preserves the current eligible tool set. A non-empty list is a strict
> cap: eligible tools intersected with profile tools. An empty intersection remains empty; it never
> falls back to all."*

- **"never falls back to all"** — **continua valendo**, e o `AgentService.java:1278` cita esta linha
  como a autoridade da guarda que implementa isso.
- **"strict cap / intersected"** — **foi revogado** pela Fase 1 do `PLANO_AGENTE_CONFIGURAVEL.md`: o
  perfil passou a definir o universo ANTES da seleção, em vez de filtrar depois.

**Não arquive este doc.** Emende-o: marque a parte revogada, dizendo por quem e quando, e **preserve
a regra que continua valendo** — porque há código de produção apontando para ela.

### 3.3. Mover quebra ponteiro, inclusive em Java

Se um doc for para `docs/historico/`, **todo** lugar que o cita precisa apontar para o novo caminho —
inclusive comentários em `.java`. Um ponteiro quebrado num comentário não falha o build, e é por isso
que passa despercebido.

**Suposição minha, confira:** listei os citadores com `grep -rl`. Reconfira antes de mover, porque a
lista pode ter crescido.

---

## 4. Decisões de projeto — não renegociar

1. **Três destinos, e o critério é este:**
   - **fica onde está** — doc atualizado em agosto, ou que descreve estado atual
   - **emendado** — contém regra ainda em vigor, ou é citado do código como autoridade
   - **`docs/historico/`** — plano executado ou substituído que ninguém cita como autoridade
2. **Nada é apagado** (3.1).
3. **Todo ponteiro é atualizado**, `.md` e `.java` (3.3).
4. **`docs/historico/README.md`** diz, para cada arquivo, o que era e o que o substituiu. Sem isso o
   histórico vira depósito.
5. **Nenhuma decisão nova.** Se dois docs discordam e você não sabe qual vale, **pare e reporte** —
   resolver contradição é decisão de projeto, não arrumação.

---

## 5. Tarefas, em ordem

### T1 — Classificar, e reportar antes de mover

Para cada um dos 23, diga: destino, motivo, e quem o cita. Grave em
`docs/agent-tasks/_docs-inventory.md` e **reporte**.

Marque especialmente: **qualquer par que decida o mesmo ponto em direções diferentes**. Essa lista é
a parte mais valiosa do relato — é o defeito que a tarefa existe para acabar.

Commit próprio.

### T2 — Emendar os que têm regra viva

Começando pelo `agent-corrections-plan.md` (ver 3.2). Marque o revogado, nomeie quem revogou,
preserve o que vale.

Se a T1 encontrar outros, trate igual.

### T3 — Mover o histórico e consertar os ponteiros

Crie `docs/historico/` com o `README.md` do item 4.4, mova, e atualize **todos** os ponteiros —
`.md` e `.java`.

**Verificação:** nenhum caminho `docs/<arquivo-movido>.md` sobra fora do `historico/`.

### T4 — Relato

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

Deve continuar em **810 testes, 0 falhas** — você só mexeu em comentário.

---

## 6. Validação

- `ls docs/*.md` mostra só os vivos e os emendados
- `docs/historico/README.md` explica cada arquivo movido
- `grep -rn "docs/<movido>.md"` fora de `historico/` → **zero**
- **810 testes, 0 falhas**

---

## 7. Fora de escopo — não faça

- **Não apague doc nenhum.** Ver 3.1: o porquê é o ativo.
- **Não resolva contradição por conta própria.** Ver 4.5. Liste e reporte; decidir é do dono.
- **Não reescreva conteúdo** dos docs vivos. Emenda é acrescentar nota de revogação, não editar o
  texto original.
- **Não mova `ARCHITECTURE.md`, `HANDOFF.md`, `SETUP.md`, `FEATURES*.md`,
  `PLANO_AGENTE_CONFIGURAVEL.md` nem `docs/aprendizados/`.**
- **Não toque em código além de comentário com caminho de doc.** Se você editou uma linha de lógica,
  saiu do escopo.
- **Não deixe ponteiro quebrado.** Ver 3.3: comentário quebrado não falha build, e é por isso que
  sobrevive por meses.

---

## 8. Entrega

Conventional commits, **em inglês**:

```
docs: inventory every document and where its decisions live
docs: mark what the corrections plan decided and what superseded it
docs: move the executed plans to historico and fix every pointer
```

No relato:

- a tabela de classificação
- **os pares contraditórios que você encontrou** — o item mais importante
- quantos ponteiros foram atualizados, e quantos estavam em `.java`
