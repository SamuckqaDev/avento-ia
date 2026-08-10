# Acabar com as 24 classes duplicadas entre módulos

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.
> Rode tudo a partir de `back/avento`.

📖 **O `AGENTS.md` da raiz manda.** Ele roteia para `.agents/skills/avento-java-maintenance/SKILL.md`,
leitura obrigatória antes de editar Java neste repo. Onde esta spec e aquele padrão divergirem, o
padrão vence e você reporta a divergência.

**Tudo marcado como "medido" foi verificado rodando comando nesta máquina em 10/08/2026.** Confie
nesses números; reconfira apenas se algo não bater.


---

## ESTADO ATUAL — leia antes de decidir parar

**A T1 já foi feita numa execução anterior.** O inventário está em
`docs/agent-tasks/_dedup-inventory.md`, não rastreado pelo git. Resultado dele:

- **24 duplicatas reais**, todas byte a byte idênticas
- **2 falsos positivos** — `WhisperContext` e `WhisperSegment` são código VENDIDO do `whisper.cpp`,
  com pacotes diferentes. **Não toque neles.**
- **0 divergências**

**A T2 também já foi feita:** 7 DTOs de mídia movidos, suíte verde, mudanças na árvore de trabalho
sem commit. **Comece pela T3**, e o bloqueio do `Manifest`/`FileManifest` tem decisão escrita no
item 3.4.

A árvore está limpa e a suíte verde: **810 testes, 0 falhas**. Qualquer falha que aparecer daqui em
diante é sua, e vale a regra de parar e reportar.

---

## 1. O problema

O mesmo arquivo existe em dois módulos, com o mesmo pacote e o mesmo conteúdo. Medido:

```
find back -name "*.java" -not -path "*/target/*" -not -path "*/test/*" \
  -exec basename {} \; | sort | uniq -d
→ 26 nomes duplicados
```

Entre eles há coisa séria: `ToolExecutionContext` (o contexto que carrega a identidade autenticada
através das chamadas de ferramenta), `AgentTimelineEventRepository` (um repository JPA) e
`AgentTimelineEvent` (uma entidade).

**Medido: 24 pares são byte a byte idênticos, e NENHUM divergiu ainda.** É por isso que esta tarefa é
mecânica hoje e deixa de ser amanhã: cada dia sem consolidar é uma chance de alguém corrigir um lado
só. O `HANDOFF.md` já registrava 22 duplicadas; hoje são 26. Está crescendo.

### O destino, decidido e verificado

**`avento-core`.** Medido: os **oito** módulos declaram dependência dele
(`avento-agent`, `avento-mcp`, `avento-media`, `avento-rag`, `avento-workspace`, `avento-execution`,
`avento-voice`, `avento-auth`). É o único lugar de onde todos enxergam.

---

## 2. O que NÃO está quebrado

- **A suíte passa: 809 testes, 0 falhas** (`mvn clean test -Dtest='!DockerMcpGatewayLiveTest'`).
  Não altere teste existente para acomodar mudança de pacote.
- **Nenhuma cópia divergiu.** Você não precisa escolher "qual versão está certa" — elas são iguais.
  Se encontrar alguma que NÃO seja idêntica, **pare e reporte**: aí há decisão de conteúdo, e decisão
  de conteúdo não é o que esta tarefa faz.
- A estrutura de cinco pastas por módulo (`model`, `dto`, `controller`, `service`, `config`) é
  recente e proposital. Mantenha-a no destino.

---

## 3. Fatos que restringem a solução

**Leia antes de mover — a solução óbvia quebra a compilação.**

### 3.1. O `avento-core` NÃO tem JPA

Medido: o `pom.xml` do `avento-core` não declara nada de `spring-data-jpa` nem `jakarta.persistence`.

Duas das duplicadas dependem disso:

| Classe | O que é |
|---|---|
| `AgentTimelineEvent` | entidade JPA (`@Entity`) |
| `AgentTimelineEventRepository` | repository Spring Data |

Mover essas duas para o `avento-core` como ele está **não compila**. Você tem duas saídas, e a
escolha é sua com o motivo escrito no commit:

- **(a)** acrescentar a dependência de JPA ao `avento-core` — simples, mas engorda o módulo base que
  hoje é deliberadamente fino (628 linhas);
- **(b)** deixar esse par de fora desta tarefa e mover só as outras 22.

**Recomendo (b)**, e reporte. Entidade compartilhada entre módulos é decisão de arquitetura de
persistência, não limpeza de duplicata — e misturar as duas na mesma tarefa torna o diff
irrevisável.

### 3.2. O pacote não muda, o módulo sim

As cópias já têm o **mesmo pacote** (ex.: `com.avento.service.tools.ToolExecutionContext` existe em
`avento-agent` e em `avento-workspace`). Isso significa que mover para `avento-core` **não exige
reescrever import nenhum** — basta apagar as duas cópias e criar uma no core, no mesmo pacote.

Se você começar a reescrever imports, parou de fazer a tarefa certa.

### 3.3. Duas classes com o mesmo nome não são necessariamente a mesma classe

Confira o pacote antes de tratar como par. `Manifest.java` e `ScannedFile.java` aparecem em
`avento-rag` e podem existir em outro módulo com pacote diferente — nesse caso são classes distintas
que por acaso têm o mesmo nome de arquivo, e consolidá-las seria erro.

Critério: **mesmo nome + mesmo pacote + conteúdo idêntico** = duplicata. Qualquer outra combinação,
pare e reporte.

### 3.4. Dependência arrastada: mova junto, mas só se for record puro

**Descoberta na T2.** O `Manifest` é duplicata, mas usa `FileManifest`, que existe **só** em
`avento-workspace` e não é duplicado. Mover só o `Manifest` deixa o `avento-core` sem compilar.

Os dois são records de uma linha, no mesmo pacote `com.avento.dto`:

```java
public record Manifest(String projectRoot, Map<String, FileManifest> files) {}
public record FileManifest(String fileHash, List<String> chunkIds) {}
```

**Decisão: mova os dois.** O `FileManifest` já é contrato compartilhado de fato — o `avento-rag` o
alcança hoje através da dependência em `avento-workspace`. Levá-lo para o core não amplia escopo,
completa o movimento de um contrato coeso: `Manifest` não significa nada sem ele. E de quebra o
`avento-rag` deixa de precisar do `avento-workspace` por causa de um record de uma linha.

**A regra, e ela é estreita:** quando uma duplicata arrasta uma classe não-duplicada, mova junto
**apenas se** a arrastada for um `record` de dados puro, no mesmo pacote, sem anotação e sem
comportamento. Qualquer outra coisa — classe com método, entidade, serviço, algo anotado — **pare e
reporte**, porque aí é decisão de arquitetura e não limpeza.

**Reporte cada arrastada que mover**, com o nome e o motivo. A lista final tem de deixar claro o que
entrou no core por ser duplicata e o que entrou por ser dependência.

---

## 4. Decisões de projeto — não renegociar

1. **Destino é `avento-core`**, no mesmo pacote de origem.
2. **Conteúdo não muda.** Nem formatação, nem javadoc, nem nome de campo. Copiar e apagar.
3. **Entidade e repository ficam de fora** (ver 3.1), salvo se você escolher (a) e justificar.
4. **Em lotes**, com commit por lote. Um commit de 22 movimentações é irrevisável.
5. **O compilador é o critério.** Se compila e a suíte passa, a movimentação está certa.

---

## 5. Tarefas, em ordem

### T1 — Inventário verificado

Liste os 26 nomes duplicados e, para cada um, os caminhos, o pacote e se o conteúdo é idêntico
(ignorando espaço em branco). Classifique em: **duplicata real**, **nome igual mas pacote
diferente**, **já divergiu**.

Grave o resultado em `docs/agent-tasks/_dedup-inventory.md` e **reporte antes de mover qualquer
coisa**. Se aparecer algo nas duas últimas categorias, isso muda o escopo.

Commit próprio.

### T2 a T4 — Mover em lotes de ~7

Para cada duplicata real: crie o arquivo em `avento-core` no mesmo pacote, apague as duas cópias,
compile.

Agrupe por afinidade (DTOs de mídia, DTOs de workspace, contexto de execução) para o diff ficar
legível. Um commit por lote, com `mvn clean test-compile` verde ao fim de cada um.

### T5 — Verificação final, commit separado

```
find back -name "*.java" -not -path "*/target/*" -not -path "*/test/*" -exec basename {} \; | sort | uniq -d
```

Deve devolver **apenas** o que você deixou de fora com justificativa (ver 3.1).

Rode a suíte inteira. **Se um teste que passava antes falhar, pare e reporte** — não ajuste o teste.

---

## 6. Validação

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test-compile
```

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

Critério de aceite:

- duplicatas reais: **zero** (ou só as justificadas)
- testes ≥ 809 e **nenhum anterior falhando**
- `git diff --stat` mostra deleções nos módulos e criações só no `avento-core`

---

## 7. Fora de escopo — não faça

- **Não mude conteúdo de classe nenhuma.** Copiar e apagar. "Aproveitar para melhorar" transforma um
  diff verificável pelo compilador num diff que precisa de revisão linha a linha.
- **Não reescreva imports.** Ver 3.2: o pacote já é o mesmo. Se você está mexendo em import, parou de
  fazer a tarefa.
- **Não resolva divergência de conteúdo por conta própria.** Se duas cópias diferem, é decisão de
  produto — pare e reporte.
- **Não mova entidade/repository sem justificar** (ver 3.1).
- **Não anote `@Disabled` nem afrouxe assert para fechar lote.** Teste desabilitado para a suíte
  passar cria confiança falsa, que é pior que teste ausente.
- **Não apague o teste que "impede as duplicadas divergirem"**, se existir, antes da T5 — ele é a
  rede enquanto o trabalho não termina.
- **Não commite `src/main/resources/agent/policies/`.**

---

## 8. Entrega

Um commit por lote, conventional commits, **em inglês**.

```
docs(agent-tasks): inventory the cross-module duplicates before moving them
refactor(core): move the shared execution context into avento-core
refactor(core): move the media DTOs into avento-core
...
```

No relato final:

- quantas duplicatas reais existiam e quantas restaram
- **qualquer par que não fosse idêntico**, com o diff — é o item mais importante do relato
- o que você deixou de fora e por quê
- contagem de testes antes e depois
