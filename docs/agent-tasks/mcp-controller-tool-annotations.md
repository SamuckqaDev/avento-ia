# Trocar os 42 schemas artesanais do McpController por anotações `@Tool`

> Spec de execução. Escopo fechado: **só** o que está aqui.
> Repo: `avento-ia` (`/Users/sr.tomimatu/projetcs/avento-ia`), branch base: **`spike/spring-boot-4-spring-ai-2`**.
> Rode tudo a partir de `back/avento`.

⚠️ **Confira a branch antes de começar.** Esta spec depende de Spring AI 2.0.0 e Jackson 3, que só
existem nesta branch. Na `master` nada aqui compila.

📖 **O `AGENTS.md` da raiz manda.** Ele aponta para `.agents/skills/avento-java-maintenance/SKILL.md`,
que é leitura obrigatória antes de editar Java neste repo. Onde esta spec e aquele padrão
divergirem, o padrão do repo vence e você reporta a divergência.

**As medições abaixo foram feitas nesta máquina em 08–10/08/2026.** Onde eu presumi em vez de medir,
está escrito. Você não precisa refazer o que está marcado como medido — precisa confiar e verificar
só se algo não bater.


---

## ESTADO ATUAL DA ÁRVORE — leia antes de decidir parar

⚠️ **A árvore de trabalho já contém trabalho de uma execução anterior, e há UMA falha conhecida e
esperada. Ela NÃO é motivo para parar — é a tarefa.**

Já feito e correto (não refaça):

- `LocalToolSchemaCharacterizationTest.java` + `src/test/resources/tool-schemas-baseline.json` — a
  T1, com 810 testes verdes quando foi criada
- `LocalToolDefinitions.java` — primeiro lote parcial
- `McpController.java` — integração parcial com `@Tool` e o normalizador

**Estado após a segunda execução:** a conversão está feita — o `McpController` caiu de 2.240 para
**1.847 linhas** e `stringProperty(` não ocorre mais nele. O `edit_file` já usa snake_case (item 3.5).

**As quatro `items.description` já foram atualizadas no baseline** (item 3.6, autorizado).

**O que resta** é ordem de `required` divergindo em uma ou mais ferramentas — conserto no **item
3.7**: reordenar os parâmetros do método, sem tocar no baseline. Aplique em todas que divergirem,
rode a suíte completa e conclua.

A regra de "pare e reporte" vale para falha **nova**, que sua mudança causou. Esta é anterior,
diagnosticada, e tem conserto escrito.

---

## 1. O problema

`back/avento/avento-agent/src/main/java/com/avento/controller/McpController.java` tem **2.242
linhas**, e entre as linhas **341 e 721** monta **42 schemas JSON na unha**:

```java
allTools.add(tool(
        "read_file",
        "Le o conteudo de um arquivo dentro de um workspace autorizado.",
        Map.of("path", stringProperty("Caminho absoluto do arquivo autorizado.")),
        List.of("path")));
```

Com helpers próprios em `:737-790` — `tool(...)`, `stringProperty`, `numberProperty`,
`booleanProperty`, `arrayProperty`, `arrayNameProperty`. É uma reimplementação artesanal do que o
`@Tool` do Spring AI gera a partir da assinatura do método.

O `AGENTS.md` do próprio repo diz *"keep controllers thin"*. Um controller de 2.242 linhas com
construção de schema dentro contraria o padrão escrito do projeto.

### Medido nesta máquina (08–10/08/2026)

**A forma do schema gerado difere da artesanal, e isso custa prompt.** Sobre o `read_file`:

| | Tamanho |
|---|---:|
| artesanal | 162 chars |
| gerado pelo `@Tool`, como vem | 280 chars (**+72%**) |
| gerado **normalizado** | 162 chars (**byte a byte igual**) |

O gerado vem formatado em várias linhas e com a chave `$schema`. No teto de 18 ferramentas do ramo
de projeto, usar como vem custaria **cerca de 2.100 caracteres a mais em TODA rodada**.

**Já existe solução, pronta e testada:**
`back/avento/avento-agent/src/main/java/com/avento/service/tools/ToolSchemaNormalizer.java`, com
`ToolSchemaNormalizerTest` provando que normalizado é idêntico ao artesanal.

### O defeito que a investigação encontrou de brinde

**17 das 42 ferramentas têm 2+ propriedades e usam `Map.of(...)`.** O `Map.of` do Java tem ordem de
iteração **randomizada por execução da JVM**. Medido rodando o mesmo programa cinco vezes:

```
fourth,projectPaths,serverId,third
projectPaths,fourth,third,serverId
fourth,projectPaths,serverId,third
fourth,third,serverId,projectPaths
fourth,third,serverId,projectPaths
```

Ou seja: o objeto `properties` do schema sai com as chaves em **ordem diferente a cada restart**, e o
payload de ferramentas está no PREFIXO do prompt. Isso invalida o cache do llama.cpp a cada
reinicialização, para essas 17 ferramentas.

**A migração conserta isso** — o `@Tool` gera as propriedades na ordem dos parâmetros do método, que
é determinística. Não é efeito colateral: é meio motivo para fazer.

### Estado atual, medido

```
cd back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
→ tests: 809 · failures: 0 · errors: 0
```

---

## 2. O que NÃO está quebrado

- **A suíte passa.** Não altere teste existente para acomodar teste novo.
- **A execução das ferramentas está correta.** O `executeLocalTool(name, payload)` em `:844` e os 42
  métodos `executeXxx` funcionam. **Esta spec não os toca.**
- **As descrições estão certas.** Elas são o contrato com o modelo — o texto que ele lê para decidir
  se chama a ferramenta. Copie **palavra por palavra**.
- O `ToolSchemaNormalizer` e o `AventoMcpServer` são recentes e testados. Não encoste.

---

## 3. Fatos que restringem a solução

**Leia antes de escrever código — três das saídas óbvias quebram o sistema.**

### 3.1. Um teste de caracterização ingênuo vai piscar

A tentação natural é fotografar os 42 schemas atuais como STRING e comparar depois. **Isso produz um
teste instável**, pelo motivo do `Map.of` acima: para as 17 ferramentas com 2+ propriedades, a string
muda entre execuções da JVM sem nada ter mudado no código.

A comparação tem de ser **semântica**: parse dos dois lados e comparação de árvore JSON
(`JsonNode.equals`), que ignora ordem de chaves de objeto. Comparar `toString()` vai te fazer
perseguir um defeito que não existe.

### 3.2. Não reescreva a execução

O `@Tool` do Spring AI pode ser o próprio método executor. **Não faça isso aqui.** Converter schema é
mecânico e verificável; reescrever a execução de 42 ferramentas é outra tarefa, com outro risco, e
misturar as duas torna o diff irrevisável.

Os métodos anotados devem **delegar** para o caminho que já existe:

```java
@Tool(name = "read_file", description = "Le o conteudo de um arquivo dentro de um workspace autorizado.")
public String readFile(@ToolParam(description = "Caminho absoluto do arquivo autorizado.") String path) {
    return String.valueOf(mcpController.execute("read_file", Map.of("path", path)));
}
```

### 3.3. O schema TEM de sair normalizado

Se o `ArrayNode` de ferramentas passar a carregar o schema como o Spring AI o entrega, o prompt de
toda rodada cresce ~72% na parte de ferramentas. Passe **sempre** pelo `ToolSchemaNormalizer`.

### 3.4. Parâmetro opcional não entra em `required`

No artesanal, o `required` é uma lista explícita (`List.of("path")`) e várias ferramentas têm
propriedades opcionais fora dela — veja `list_mcp_servers` em `:358`, com `projectPaths` opcional e
`List.of()` vazio. No `@Tool`, a obrigatoriedade vem de `@ToolParam(required = false)`. Errar isso
muda o schema e o modelo passa a mandar campo que não devia, ou a omitir campo obrigatório.

### 3.5. `edit_file` usa snake_case e o `@ToolParam` NÃO renomeia

**Descoberto durante a primeira execução desta spec, e ela estava incompleta sem isto.**

O Spring AI gera o nome da propriedade do schema a partir do **nome do parâmetro Java**. E o
`@ToolParam` do Spring AI 2.0.0 tem só dois atributos — `required()` e `description()`. **Não existe
`name()`.** Verificado com `javap`.

Medido no HEAD: **três** parâmetros usam snake_case, todos no `edit_file`:

```
old_string   new_string   replace_all
```

Os outros 49 parâmetros distintos já são camelCase e convertem sem atrito.

**Decisão, tomada e não negociável:** nomeie os parâmetros Java como `old_string`, `new_string` e
`replace_all`. Underscore é identificador válido em Java, e o método existe **só para declarar
schema** — a feiura fica contida num adaptador de borda.

```java
@Tool(name = "edit_file", description = "…copiada literalmente…")
public String editFile(
        @ToolParam(description = "Caminho absoluto do arquivo existente a editar.") String path,
        @ToolParam(description = "Trecho exato…") String old_string,
        @ToolParam(description = "Trecho que deve substituir old_string…") String new_string,
        @ToolParam(required = false, description = "…") Boolean replace_all) { … }
```

**Por que não o contrário** — renomear o schema para camelCase seria mais bonito e está **errado**:
o `edit_file` está no kit fixo do chat com projeto conectado, é das mais usadas, e a própria
descrição cita `old_string` pelo nome. Mudar o schema mudaria o contrato com o modelo, e a decisão 1
da seção 4 proíbe tocar em descrição.

### 3.6. Descrição de item de array: perda aceita, e só esta

**Descoberta na segunda execução.** O `arrayProperty` artesanal punha descrição no ITEM do array; o
Spring AI gera `items: {"type":"string"}`, sem descrição. O normalizador não resolve — não há de onde
tirar o texto.

Medido: são **três** parâmetros de array em todo o catálogo, e em todos a descrição do **pai** já
carrega a mesma orientação:

| Parâmetro | Descrição do pai | Item dizia |
|---|---|---|
| `projectPaths` (list_mcp_servers) | "Workspaces **absolutos** opcionais…" | "Caminho absoluto." |
| `projectPaths` (connect_mcp_server) | "Workspaces **absolutos** necessarios…" | "Caminho absoluto." |
| `tools` (activate_tools) | "Nomes **exatos** das ferramentas a ativar." | "Nome exato da ferramenta." |
| `triggers` (create_skill) | "Frases-gatilho que ativam a skill (ex.: ['cotacao', …])." | **"Caminho absoluto."** ← errado |

Nas três primeiras a informação que o modelo precisa — caminho absoluto, nome exato — **sobrevive no
pai**, e a perda é redundância.

**A quarta é diferente: perder é CONSERTAR.** O `create_skill.triggers` usa o `arrayProperty`
genérico, cujo item é fixo em `"Caminho absoluto."` — mas `triggers` são frases-gatilho, não
caminhos. O schema de hoje diz ao modelo que cada gatilho é um caminho absoluto. É defeito de
copiar-e-colar do helper, e a migração o remove de graça.

(Encontrada pelo executor, não por mim: minha varredura contou três e eram quatro.)

**Decisão:** aceite a diferença e **regenere o baseline apenas nessas quatro entradas**, registrando o
motivo no commit.

⚠️ **Isto NÃO é licença para regenerar o baseline.** A permissão é estreita e literal: apenas a chave
`items.description` dessas quatro propriedades de array. **Qualquer outra diferença continua sendo
motivo para parar e reportar** — inclusive outra chave nas mesmas três.

A distinção que importa: afrouxar teste para esconder defeito é proibido; atualizar um baseline
porque o contrato mudou **de propósito, com o motivo escrito e a perda medida**, é decisão de projeto.
A diferença entre as duas é se alguém verificou o que se perdeu. Aqui foi verificado.

### 3.7. Ordem de `required`: reordene o MÉTODO, nunca o baseline

**Descoberta na terceira execução**, no `write_file`:

```
baseline:  "required": ["path","content"]
gerado:    "required": ["content","path"]
```

O `required` gerado sai na ordem dos parâmetros do método. Em JSON Schema isso é um conjunto e a
ordem não muda o significado — mas o payload de ferramentas entra no **prefixo do prompt**, e ali
byte diferente é prefixo diferente.

**Aqui não se cede nada, porque a ordem dos parâmetros Java é arbitrária.** Reordene a assinatura do
método para casar com o baseline. Custa mover uma palavra e mantém o schema byte a byte idêntico.

```java
// baseline pede ["path","content"] — entao 'path' vem primeiro na assinatura
public String writeFile(
        @ToolParam(description = "…") String path,
        @ToolParam(description = "…") String content) { … }
```

**Vale para toda ferramenta em que a ordem divergir**, não só o `write_file`. E o baseline **não é
tocado** por este item: a permissão da 3.6 continua valendo só para `items.description` das quatro
entradas listadas lá.

---

## 4. Decisões de projeto — não renegociar

1. **Descrições copiadas literalmente.** São o contrato com o modelo. Uma "melhoria" de texto muda o
   comportamento do agente e não é o que esta tarefa faz.
2. **Uma classe nova**, `com.avento.service.tools.LocalToolDefinitions`, com os 42 métodos anotados.
   Não espalhe as anotações pelo controller.
3. **O `McpController` continua sendo quem executa.** A classe nova só declara e delega.
4. **Schema sempre normalizado** pelo `ToolSchemaNormalizer`.
5. **Em lotes, com commit por lote.** Um commit de 42 conversões é irrevisável.

---

## 5. Tarefas, em ordem

### T1 — A rede: caracterizar os 42 schemas atuais

Antes de converter qualquer coisa. Crie
`avento-agent/src/test/java/com/avento/controller/LocalToolSchemaCharacterizationTest.java` que:

- chama `getAvailableToolsInternal()` (privado — use reflexão, é o padrão do repo; veja
  `AgentServiceDirectAutomationTest`)
- guarda nome, descrição e `inputSchema` das 42
- grava um snapshot em `src/test/resources/tool-schemas-baseline.json`

**A comparação é semântica, não textual** — ver 3.1. Use `mapper.readTree(a).equals(mapper.readTree(b))`.

Este teste tem de passar **antes** de qualquer conversão. Ele é o que prova, depois, que a migração
não mudou schema nenhum.

Commit próprio.

### T2 — Primeiro lote: as 6 do `project-toolkit`

`directory_tree`, `read_file`, `write_file`, `edit_file`, `delete_file`, `terminal_run`.

Começa por elas porque são o kit fixo do chat com projeto conectado — as mais usadas, e as que mais
importam para o cache de prompt.

Crie `LocalToolDefinitions` com esses 6 métodos anotados, delegando como em 3.2. Faça
`getAvailableToolsInternal()` montar essas 6 a partir do `MethodToolCallbackProvider` + normalizador,
e manter as outras 36 pelo caminho antigo.

**O T1 tem de continuar verde.** Se não continuar, o schema mudou — pare e reporte a diferença.

### T3 a T7 — Os lotes restantes, ~9 ferramentas por lote

Mesma mecânica. Um commit por lote, T1 verde ao fim de cada um.

Agrupe por afinidade (arquivo, terminal, MCP, mídia, sistema) para o diff ficar legível.

### T8 — Remover o artesanal

Só quando as 42 estiverem convertidas e o T1 verde: apague `tool(...)`, `stringProperty`,
`numberProperty`, `booleanProperty`, `arrayProperty`, `arrayNameProperty` e o bloco `:341-721`.

O compilador confirma que nada mais os usa.

### T9 — Verificação final, commit separado

Rode a suíte inteira. **Se algum teste que passava antes falhar, pare e reporte** — não ajuste o
teste antigo, não anote `@Disabled`, não afrouxe assert.

Relate a contagem de linhas do `McpController` antes e depois.

---

## 6. Validação

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn -pl avento-agent -am test-compile
```

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn -pl avento-agent -am test -Dtest='LocalToolSchemaCharacterizationTest'
```

```bash
cd /Users/sr.tomimatu/projetcs/avento-ia/back/avento && mvn clean test -Dtest='!DockerMcpGatewayLiveTest'
```

Critério de aceite:

- `LocalToolSchemaCharacterizationTest` verde em todos os lotes
- Total de testes ≥ 809 e **nenhum teste anterior falhando**
- `McpController` visivelmente menor; relate o número
- `grep -c "stringProperty(" McpController.java` → **0** ao fim

Relate o resultado real, incluindo falhas.

---

## 7. Fora de escopo — não faça

- **Não reescreva a execução das ferramentas.** Ver 3.2. Outra tarefa, outro risco.
- **Não melhore descrição nenhuma.** Elas são o contrato com o modelo; mudar texto muda
  comportamento e não é o que esta tarefa faz.
- **Não anote `@Disabled` para fechar lote.** Teste desabilitado para a suíte passar é pior que
  teste ausente: cria confiança falsa, que é a causa-raiz de metade dos defeitos que este projeto
  encontrou em si mesmo.
- **Não "simplifique" o `ToolSchemaNormalizer`.** Ele parece cosmético e não é — sem ele a migração
  engorda o prompt de toda rodada em ~72% na parte de ferramentas.
- **Não compare schemas por string.** Ver 3.1: o teste vai piscar e você vai perseguir um defeito
  que não existe.
- **Não converta as 42 num commit só.** Irrevisável.
- **Não mexa em `AventoMcpServer`, `ToolSchemaNormalizer` nem no RAG.** São de outra frente.
- **Não commite `src/main/resources/agent/policies/`.** Convenção do repo.

---

## 8. Entrega

Um commit por lote, conventional commits, **em inglês** (`CONTRIBUTING.md` e `AGENTS.md`).

Sugestão:

```
test(tools): pin the 42 hand-built tool schemas before converting them
refactor(tools): declare the project toolkit with @Tool instead of hand-built JSON
refactor(tools): declare the file tools with @Tool
...
refactor(tools): delete the hand-built schema builders
```

No relato final:

- contagem de testes antes e depois
- linhas do `McpController` antes e depois
- **qualquer schema que saiu diferente** e por quê — se houver, é o item mais importante do relato
- qualquer ferramenta cuja obrigatoriedade de parâmetro você teve de decidir (ver 3.4)
