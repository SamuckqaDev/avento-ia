# Plano de Implementação — Saída Visual Rica (nível júnior, passo a passo)

> **Como ler este documento:** você é o dev júnior. Faça **exatamente** o que está escrito, na
> ordem escrita. Onde há bloco de código, é para copiar e adaptar só o indicado. Onde diz
> **⚠️ QUEBRA O BUILD**, é um ponto que não compila se você esquecer — preste atenção. Se algo aqui
> divergir do código real (nome de método, assinatura), **pare e pergunte** antes de inventar.
>
> **Baseline atual:** `mvn -f back/avento/pom.xml test` = 329 testes, 0 falhas. Não pode baixar
> desse número.

---

## 0. Regras que valem para TODAS as entregas (leia antes de tocar em qualquer arquivo)

1. **Formatação:** rode `mvn -f back/avento/pom.xml spotless:apply` antes de todo commit. Formato
   palantir; fora do padrão o build falha.
2. **Resposta REST:** todo endpoint JSON retorna `BaseResponse<T>` via `ApiResponses.ok(...)`. O
   front consome pelo Axios de `front/src/services/apiClient.ts`, que **desembrulha `data` sozinho**.
   **NUNCA** ponha `responseType: 'text'` ou `'json'` numa chamada que retorna envelope — joga JSON
   cru na tela. **Binário (PDF/imagem) usa `responseType: 'blob'`** — isso é o correto para baixar
   arquivo.
3. **DTOs:** todo record/classe novo vai em `com.avento.api.dto` (API) ou `com.avento.service.dto`
   (interno). **Nunca** declare record dentro de controller/service.
4. **NÃO TOQUE:** `back/avento/src/main/resources/agent/policies/*.md` e qualquer coisa em
   `~/.avento/`. São do usuário.
5. **npm:** pacote novo só com `npm --prefix front install --save-exact --ignore-scripts <pkg>@<major>`.
   Mostre o diff do `package-lock.json` antes de commitar. Prefira reusar o que existe.
6. **Doc:** mudou comportamento/config? Atualize `README.md`, `README.en.md`, o `docs/*.md`
   relevante **e** `back/avento/src/main/resources/static/docs.html` (com um selo de status novo).
7. **Testes:** `mvn -f back/avento/pom.xml test` e `npm --prefix front run validate` verdes. Toda
   entrega adiciona teste.
8. **Commit:** um por entrega, mensagem em inglês (conventional commits), terminando com
   `Co-Authored-By: <você>`. **NÃO faça push.** O usuário aprova.

**Decisões de arquitetura já tomadas (não reabra):**
- **PDF aceita Markdown** (o modelo já gera tabela Markdown fácil), convertido no backend com
  `commonmark-java` + extensão de tabelas; também aceita HTML puro. (Entrega C)
- **Categoria de tool nova: `DOCUMENT`.** (Entrega C) — exige editar um `switch` exaustivo, código
  exato abaixo.
- **PDF é síncrono** (renderiza em <1s), **sem** job/worker assíncrono. (Entrega C)

---

## Entrega A — Tabelas Markdown inline (remark-gfm)

### Contexto
O `react-markdown` sozinho segue CommonMark, que **não tem tabela**. Por isso a tabela sai numa
linha só. O plugin `remark-gfm` adiciona tabela, `~~riscado~~` e listas de tarefa.

### Arquivos
- `front/package.json`, `front/package-lock.json`
- `front/src/modules/chat/MessageBubble/index.tsx`
- o arquivo de styled-components do MessageBubble (mesma pasta; veja os imports no topo do
  `index.tsx` para achar o nome — provavelmente `styles.ts`)

### Passo 1 — instalar
```bash
npm --prefix front install --save-exact --ignore-scripts remark-gfm@4
```
(`remark-gfm@4` é a linha compatível com `react-markdown@10`, que já está no projeto. Mesmo autor do
react-markdown. JS puro, sem `postinstall`.)

### Passo 2 — ligar o plugin
Em `MessageBubble/index.tsx`, no topo:
```ts
import remarkGfm from 'remark-gfm';
```
No `<ReactMarkdown ...>` (hoje por volta da linha 308, procure a tag `<ReactMarkdown`), adicione a
prop **sem remover nada do que já existe**:
```tsx
<ReactMarkdown
  remarkPlugins={[remarkGfm]}
  components={{
    /* ...tudo que já existe aqui fica igual... */
```

### Passo 3 — estilizar a tabela
Ainda dentro de `components={{ ... }}`, **adicione** estes componentes (não mexa em `pre`, `code`,
`img`):
```tsx
table({ children }) {
  return <TableScroll><StyledTable>{children}</StyledTable></TableScroll>;
},
th({ children }) { return <StyledTh>{children}</StyledTh>; },
td({ children }) { return <StyledTd>{children}</StyledTd>; },
```
No arquivo de estilos, crie os styled-components (use as cores/variáveis de tema que os outros
styled do arquivo já usam — copie o padrão de cor de borda de outro componente do balão):
```ts
export const TableScroll = styled.div`
  overflow-x: auto;          /* impede a tabela larga de estourar o layout no celular */
  max-width: 100%;
  margin: 8px 0;
`;
export const StyledTable = styled.table`
  border-collapse: collapse;
  width: 100%;
  font-size: 0.9em;
`;
export const StyledTh = styled.th`
  border: 1px solid <cor-de-borda-do-tema>;
  padding: 6px 10px;
  text-align: left;
  background: <cor-de-fundo-sutil-do-tema>;
  font-weight: 600;
`;
export const StyledTd = styled.td`
  border: 1px solid <cor-de-borda-do-tema>;
  padding: 6px 10px;
`;
```
Substitua `<cor-...>` pelas variáveis reais que você achar nos outros styled do arquivo. Importe os
novos styled no `index.tsx`.

### Teste / Aceite
- `npm --prefix front run validate` passa.
- Rode o front (`./scripts/dev-up.sh`), peça no chat "monta uma tabela de 3 linguagens com colunas
  Nome/Ano/Uso" → deve renderizar tabela com borda e, se larga, com scroll horizontal.
- `~~teste~~` aparece riscado; `- [ ] item` aparece com checkbox.

---

## Entrega B — Relatórios visuais reaproveitando `ui-preview` (ZERO dependência)

### Contexto
O Avento **já renderiza** blocos ```` ```ui-preview ````: o `MessageBubble` detecta o bloco (função
por volta da linha 106) e manda pro `UiPreviewCard`, que mostra o HTML num iframe isolado, **sem
rede**. Hoje só é usado pra mockup. Vamos ensinar o modelo a usar o mesmo bloco pra relatório, tabela
estilizada, cards e gráfico SVG. **Nada de código novo de renderização — só instrução para o modelo.**

### Arquivos
- `back/avento/src/main/resources/agent/instructions/tools.md`
- **novo:** `back/avento/src/main/resources/agent/skills/visual-report.md`

### Passo 1 — instrução no `tools.md`
Adicione um parágrafo (em português, o resto do arquivo é PT):
```
- Relatório/tabela/dashboard visual: quando o usuário pedir "monta um relatório", "tabela bonita",
  "dashboard", "resumo visual disso", responda com um bloco ```ui-preview contendo HTML AUTOCONTIDO:
  todo o CSS inline, SEM fetch, SEM <script src> externo, SEM imagem de URL — o iframe não tem rede.
  Para gráfico simples, desenhe SVG inline (barras/linhas com <rect>/<polyline>) ou barras em CSS.
  Para dado pequeno (poucas linhas), prefira uma tabela Markdown simples em vez de ui-preview.
```

### Passo 2 — skill `visual-report.md`
Crie o arquivo com **exatamente** este cabeçalho (a 1ª linha é o título/descrição; a linha
`Gatilhos:` é lida pelo `SkillRegistry`):
```
# Monta um relatório ou tabela visual autocontido no chat
Gatilhos: monta um relatorio, montar relatorio, relatorio visual, dashboard disso, monta um dashboard, tabela visual, resumo visual

Quando o usuário pedir um relatório/tabela/dashboard visual, produza um único bloco ```ui-preview
com HTML autocontido:
- Todo o CSS inline dentro do próprio HTML. Nada de rede: sem fetch, sem CDN, sem <script src>, sem
  <img src="http...">.
- Estruture com seções, uma tabela estilizada e, quando houver números, um gráfico em SVG inline.
- Use uma paleta sóbria e legível. Não invente dados: use só o que o usuário forneceu ou o que você
  pesquisou nesta conversa.
- Se o dado for pequeno (poucas linhas), uma tabela Markdown já basta — não force ui-preview.
```
(**Sem** linha `Ferramenta:` — `ui-preview` é formato de saída, não ferramenta.)

### Teste / Aceite
- No chat: "monta um relatório visual comparando React, Vue e Svelte" → aparece um card ui-preview
  estilizado (não texto solto).
- Confirme que o HTML gerado não tem `http://`/`https://` em `src`/`fetch` (o iframe já bloquearia,
  mas a instrução evita o modelo tentar).
- Adicione um caso na `SkillRegistryTest`: `registry.find("visual-report")` presente e
  `triggers()` contém `"monta um relatorio"`.

---

## Entrega C — Exportar PDF (tool `generate_pdf`)

### Contexto e decisões
- Motor: **biblioteca Java `openhtmltopdf`** (não Playwright). Roda no Spring, não depende de MCP.
- Entrada: **Markdown** (convertido com `commonmark-java` + extensão de tabelas) ou **HTML** puro.
- **Síncrono** (rápido). Não use job/worker.
- O PDF é salvo na **mesma pasta de mídia** já existente e servido pelo endpoint de mídia que já
  existe — assim não precisamos criar endpoint de download novo.

### C.1 — Dependências Maven (`back/avento/pom.xml`)
Adicione dentro de `<dependencies>` (versões conhecidas boas; **se o Maven não resolver, pare e
pergunte — não bumpe às cegas**):
```xml
<dependency>
  <groupId>com.openhtmltopdf</groupId>
  <artifactId>openhtmltopdf-pdfbox</artifactId>
  <version>1.0.10</version>
</dependency>
<dependency>
  <groupId>org.commonmark</groupId>
  <artifactId>commonmark</artifactId>
  <version>0.22.0</version>
</dependency>
<dependency>
  <groupId>org.commonmark</groupId>
  <artifactId>commonmark-ext-gfm-tables</artifactId>
  <version>0.22.0</version>
</dependency>
```

### C.2 — Novo service `PdfGenerationService`
Crie `back/avento/src/main/java/com/avento/service/PdfGenerationService.java`. Responsabilidades, em
ordem:
1. Receber `title` + (`markdown` OU `html`), `chatId`, `userId`.
2. Se veio `markdown`: converter para HTML com commonmark + extensão de tabelas:
   ```java
   List<Extension> extensions = List.of(TablesExtension.create());
   Parser parser = Parser.builder().extensions(extensions).build();
   HtmlRenderer renderer = HtmlRenderer.builder().extensions(extensions).build();
   String bodyHtml = renderer.render(parser.parse(markdown));
   ```
3. Embrulhar num HTML **XHTML válido** (o openhtmltopdf exige XHTML bem-formado) com CSS inline de
   tabela/tipografia e o `title` num `<h1>`. **Sem** recurso externo.
4. Renderizar para PDF:
   ```java
   String filename = "avento-doc-" + timestamp + ".pdf";   // padrão avento-doc-*
   Path out = mediaDirectory.resolve(filename);
   try (OutputStream os = Files.newOutputStream(out)) {
       PdfRendererBuilder builder = new PdfRendererBuilder();
       builder.useFastMode();
       builder.withHtmlContent(xhtml, null);   // baseUri null = sem resolver recurso externo
       builder.toStream(os);
       builder.run();
   }
   ```
   `mediaDirectory` = `~/Pictures/Avento Generated Images` (a MESMA pasta de imagem/vídeo; injete via
   `@Value("${avento.media.directory:}")` seguindo o mesmo padrão de `MediaController` linhas 55-56).
5. Registrar o asset: `generatedMediaAssetService.register(out, chatId, userId, "document")`.
   > `mediaType` é uma coluna `String` livre (`GeneratedMediaAsset` linha 30-31) — **não precisa de
   > migração** para o valor `"document"`.
6. Retornar um `ObjectNode` com `status=success`, `filename`, `path`, `mediaType="document"`.

### C.3 — Servir e apagar o PDF (editar `MediaController`)
O endpoint `GET /api/media/{filename}` já serve arquivos da `mediaDirectory`, mas hoje só reconhece
imagem/vídeo. Dois ajustes:

**⚠️ QUEBRA (silenciosamente) se esquecer:** sem editar `isGeneratedMedia`, o PDF retorna 404.

`isGeneratedMedia` (linha ~117) — adicione o caso `.pdf`:
```java
private boolean isGeneratedMedia(Path path) {
    String filename = path.getFileName().toString();
    return Files.isRegularFile(path)
            && ((filename.startsWith("avento-image-") && filename.endsWith(".png"))
                    || (filename.startsWith("avento-video-") && filename.endsWith(".webp"))
                    || (filename.startsWith("avento-doc-") && filename.endsWith(".pdf")));
}
```
`contentTypeFor` (linha ~154) — devolver `application/pdf` para `.pdf`:
```java
private MediaType contentTypeFor(Path path) {
    String name = path.getFileName().toString();
    if (name.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
    if (name.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
    return MediaType.IMAGE_PNG;
}
```
> A exclusão já funciona sozinha: `GeneratedMediaAssetService.deleteForChat` apaga todos os assets do
> chat pela pasta, então o PDF some junto ao apagar o chat. Não precisa mexer.

### C.4 — Registrar a tool (`ToolCapabilityRegistry`)
**⚠️ QUEBRA O BUILD:** primeiro adicione o valor `DOCUMENT` ao enum, senão o `register(... DOCUMENT
...)` não compila.

`back/avento/src/main/java/com/avento/service/tools/ToolCategory.java` — adicione `DOCUMENT`:
```java
public enum ToolCategory {
    FILESYSTEM, PROJECT_SCAFFOLD, MACOS_APP, BROWSER, URL, FINDER, SHORTCUT,
    SCREEN, IMAGE, DOCUMENT, TERMINAL, MCP_EXTERNAL
}
```
**⚠️ QUEBRA O BUILD:** o `switch` de `IntentRouter.shouldExposeRegisteredTool` é **exaustivo** —
adicionar valor ao enum sem tratar o novo `case` não compila. Edite
`back/avento/src/main/java/com/avento/service/intent/IntentRouter.java`:
```java
case IMAGE -> profile.has(AgentIntent.IMAGE);
case DOCUMENT -> profile.has(AgentIntent.IMAGE);   // reusa a intenção visual/mídia
```
Agora registre a tool em `ToolCapabilityRegistry` (perto do `generate_video`, linha ~210). A
assinatura de `register` é `(defs, name, ToolCategory, ToolRiskLevel, ToolApprovalPolicy,
boolean directAutoExecutable, String summary)`:
```java
register(
        definitions,
        "generate_pdf",
        ToolCategory.DOCUMENT,
        ToolRiskLevel.MEDIUM,
        ToolApprovalPolicy.APPROVAL_REQUIRED,   // escreve arquivo → pede aprovação
        false,
        "Gera um documento PDF local a partir de Markdown ou HTML e o vincula ao chat.");
```
(`ToolRiskLevel`: READ_ONLY/LOW/MEDIUM/HIGH/DESTRUCTIVE. `ToolApprovalPolicy`:
AUTO/APPROVAL_REQUIRED.)

### C.5 — Schema da tool para o modelo (`McpController`)
Em `McpController`, junto dos outros `allTools.add(tool(...))` (o de `generate_video` está na linha
~509). Use os helpers existentes `tool(name, description, Map<String,ObjectNode>, List<required>)`,
`stringProperty(desc)`:
```java
allTools.add(tool(
        "generate_pdf",
        "Gera um PDF local a partir de conteudo Markdown (preferido) ou HTML e o salva vinculado ao"
                + " chat. Use para 'gera um PDF disso', relatorios e exportacoes. Passe o conteudo ja"
                + " pronto; tabelas em Markdown sao suportadas.",
        Map.of(
                "title", stringProperty("Titulo do documento, vira o cabecalho do PDF."),
                "markdown", stringProperty("Conteudo em Markdown (aceita tabelas). Use este OU html."),
                "html", stringProperty("Conteudo em HTML puro, alternativa ao markdown.")),
        List.of("title")));
```

### C.6 — Dispatch (`McpController.executeLocalTool`)
No `switch` de `executeLocalTool` (onde há `case "generate_video" -> executeGenerateVideo(payload);`,
linha ~681), adicione:
```java
case "generate_pdf" -> executeGeneratePdf(payload);
```
E o método (espelhe `executeGenerateImage`, linha ~1186 — lê `_chatId`/`_userId` que o AgentService
injeta, e devolve `toolResult(...)`):
```java
private JsonNode executeGeneratePdf(Map<String, Object> payload) throws IOException {
    if (pdfGenerationService == null) {
        return toolResult(mapper.createObjectNode().put("error", "Serviço de PDF indisponível."));
    }
    Long chatId = requiredLong(payload, "_chatId");
    UUID userId = UUID.fromString(requiredString(payload, "_userId"));
    String title = requiredString(payload, "title");
    String markdown = optionalString(payload, "markdown");
    String html = optionalString(payload, "html");
    return toolResult(pdfGenerationService.generate(title, markdown, html, chatId, userId));
}
```
Injete `PdfGenerationService pdfGenerationService` no construtor do `McpController` seguindo o padrão
dos outros services já injetados ali (ex.: `imageGenerationJobService`).

### C.7 — Mensagem com marcador (`AgentService`)
No `switch` que monta a mensagem da tool (linha ~3298, onde há
`case "generate_video" -> generatedVideoMessage(toolResult);`), adicione:
```java
case "generate_pdf" -> generatedPdfMessage(toolResult);
```
E o método (espelhe `generatedImageMessage`, linha ~3315; como é síncrono, use o `filename`, não um
jobId):
```java
private String generatedPdfMessage(JsonNode toolResult) {
    String filename = toolResult.path("filename").asText("");
    if (filename.isBlank()) {
        return "\nO PDF foi gerado, mas o arquivo não foi identificado.\n";
    }
    return "\nPDF gerado e vinculado a esta conversa."
            + "\n\n[[avento-doc:" + filename + "]]\n";
}
```

### C.8 — Frontend: card de download
Em `front/src/modules/chat/MessageBubble/index.tsx`, a função `extractMediaJobs` (linha ~109) já
extrai marcadores de imagem/vídeo. Adicione a extração do PDF:
```ts
const docFilenames: string[] = [];
markdown = markdown.replace(/\[\[avento-doc:([^\]]+)\]\]/gi, (_, name: string) => {
  docFilenames.push(name);
  return '';
});
// inclua docFilenames no retorno do objeto, ao lado de imageJobIds/videoJobIds
```
Crie `front/src/modules/chat/DocumentCard/index.tsx`: recebe `filename`, mostra o nome e um botão
"Baixar PDF" que baixa de `/api/media/${filename}` com `responseType: 'blob'` (copie o padrão de
`ImageGenerationCard` linha ~65/128, que já baixa blob) e dispara o download no navegador.
No `MessageBubble`, renderize `docFilenames.map(name => <DocumentCard key={name} filename={name} />)`
junto de onde hoje renderiza os `ImageGenerationCard`.

### Testes / Aceite
- Novo `PdfGenerationServiceTest`: chamar `generate("Titulo", "# Oi\n\n| a | b |\n|---|---|\n| 1 | 2 |",
  null, chatId, userId)` → retorna `status=success`, arquivo `.pdf` existe e tem bytes > 0, asset
  registrado com `mediaType="document"`.
- Manual: peça "gera um PDF do relatório acima" → aparece card com botão que baixa um PDF válido.
- Apague o chat → o PDF some do disco.

---

## Entrega D — Pesquisa na internet com resultado visual (`/research`)

### Contexto
No print real, o modelo travou em `> Limite de ferramentas atingido` (`AgentService` linha 1924:
`round > maxToolRounds`, e `max-tool-rounds: 6` no `application.yml`). Os tools de web **já existem**
(`searxng`, `fetch`, `browser_navigate`, `browser_snapshot`, `web_url_read`). Faltam duas coisas:
**(D.1)** garantir que esses tools sejam expostos e que a pesquisa tenha fôlego de rodadas; **(D.2)**
uma skill que sintetize o resultado numa tabela/relatório em vez de texto solto.

Hoje uma skill força **uma** ferramenta (`Ferramenta:` → `AgentRunState.requiredToolName`). Pesquisa
precisa de **várias** e de **mais rodadas**. Vamos generalizar de forma **aditiva** (o singular
continua funcionando).

### D.1 — Skill pode declarar várias ferramentas e um teto de rodadas próprio

**Arquivo 1 — `com.avento.service.dto.Skill`** (record). Hoje:
`Skill(name, description, triggers, tool, body, builtin)`. Adicione dois campos e um helper:
```java
public record Skill(
        String name, String description, List<String> triggers,
        String tool, List<String> tools, int maxRounds,   // <- tools e maxRounds novos
        String body, boolean builtin) {

    public boolean declaresTool() { return tool != null && !tool.isBlank(); }

    /** Todas as ferramentas que a skill força expor (singular + plural). */
    public java.util.Set<String> requiredToolNames() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        if (declaresTool()) all.add(tool.trim());
        if (tools != null) tools.forEach(t -> { if (t != null && !t.isBlank()) all.add(t.trim()); });
        return all;
    }
}
```
**⚠️ QUEBRA O BUILD:** mudar o record muda o construtor. O **único** `new Skill(...)` no código está
em `SkillRegistry.parseSkill` (linha ~240). Atualize essa linha para passar os novos campos (veja
D.1 abaixo). `saveCustomSkill` e os testes chamam `saveCustomSkill(...)`, que passa por `parseSkill`,
então **não** têm `new Skill` direto — não precisam mudar a chamada, só o parse.

**Arquivo 2 — `SkillRegistry.parseSkill`.** O laço que lê os cabeçalhos hoje trata `gatilhos:` e
`ferramenta:`. Adicione `ferramentas:` (plural, lista por vírgula) e `maxrodadas:`. No laço
`while (true)` que consome as linhas de cabeçalho, acrescente ramos:
```java
} else if (lowerBody.startsWith("ferramentas:")) {
    for (String t : headerLine.substring("ferramentas:".length()).split(",")) {
        String v = t.strip();
        if (!v.isBlank() && !toolsList.contains(v)) toolsList.add(v);
    }
} else if (lowerBody.startsWith("maxrodadas:")) {
    try { maxRounds = Integer.parseInt(headerLine.substring("maxrodadas:".length()).strip()); }
    catch (NumberFormatException ignored) { /* mantém 0 */ }
}
```
Declare `List<String> toolsList = new ArrayList<>();` e `int maxRounds = 0;` antes do laço, e no
`return` troque para:
```java
return new Skill(name, description, List.copyOf(triggers), tool,
        List.copyOf(toolsList), maxRounds, body, builtin);
```

**Arquivo 3 — `com.avento.service.dto.SkillResolution`.** Adicione o conjunto e o teto ao lado dos
campos atuais. Onde o `AgentService` cria a resolução da skill explícita (por volta da linha 685,
`new SkillResolution(true, true, skill.get().name(), skill.get().tool(), argument, augmented, null)`)
e a automática (~717), passe também `skill.get().requiredToolNames()` e `skill.get().maxRounds()`.
Ajuste o record `SkillResolution` para carregar `Set<String> toolNames` e `int maxRounds`.

**Arquivo 4 — `AgentService`.** Dois pontos:

(a) **Estado da run.** Em `AgentRunState` (hoje tem `String requiredToolName = ""`, linha ~3509),
**troque** por:
```java
java.util.Set<String> requiredToolNames = java.util.Set.of();
int maxToolRoundsOverride = 0;   // 0 = usa o teto global
```
E onde hoje seta `state.requiredToolName = ...` (linha ~569), passe a setar:
```java
state.requiredToolNames = skillResolution.toolNames();       // do SkillResolution
state.maxToolRoundsOverride = skillResolution.maxRounds();
```
Faça o mesmo no outro caminho de skill (o bloco automático, se existir seteador equivalente).

(b) **Seleção de ferramentas.** O bloco em `selectToolsForCurrentRequest` (linha ~960) que hoje faz:
```java
if (state.requiredToolName != null && !state.requiredToolName.isBlank()) {
    ArrayNode required = filterToolsByName(tools, Set.of(state.requiredToolName));
    if (!required.isEmpty()) return required;
}
```
**troque** por:
```java
if (state.requiredToolNames != null && !state.requiredToolNames.isEmpty()) {
    ArrayNode required = filterToolsByName(tools, state.requiredToolNames);
    if (!required.isEmpty()) return required;
}
```
> `filterToolsByName(tools, Set<String>)` já aceita um conjunto — é a mesma assinatura usada hoje.

(c) **Teto de rodadas.** No ponto do limite (linha ~1924):
```java
if (round > maxToolRounds || state.executedToolCalls >= maxToolCalls) {
```
**troque** `maxToolRounds` por um efetivo:
```java
int effectiveMaxRounds = state.maxToolRoundsOverride > 0 ? state.maxToolRoundsOverride : maxToolRounds;
if (round > effectiveMaxRounds || state.executedToolCalls >= maxToolCalls) {
```

> **Atenção aos testes existentes:** há testes de `requiredToolName` (busque por `requiredToolName`
> em `src/test`). Atualize-os para os novos campos (`requiredToolNames`/`toolNames`). O
> comportamento singular tem que continuar passando: uma skill com `Ferramenta: generate_video`
> ainda força só o `generate_video`.

### D.2 — A skill `research.md`
Crie `back/avento/src/main/resources/agent/skills/research.md`:
```
# Pesquisa na internet e sintetiza o resultado de forma visual
Gatilhos: pesquisa na internet, pesquise sobre, busca online, buscar na internet, procura na web, pesquisa web, pesquisar online
Ferramentas: searxng, fetch, browser_navigate, browser_snapshot, web_url_read
MaxRodadas: 12

Procedimento para pesquisar e apresentar:
1. Faça UMA busca inicial (searxng ou browser_navigate). Não repita a mesma busca em loop.
2. Abra os 2–4 melhores resultados e extraia o conteúdo (fetch / web_url_read / browser_snapshot).
3. Sintetize numa tabela Markdown quando for comparação/lista, ou num bloco ```ui-preview quando for
   um relatório maior (ver a skill visual-report).
4. Cite as fontes (título + URL) ao final.
5. Ofereça exportar em PDF com a ferramenta generate_pdf se o usuário quiser guardar o resultado.
Não invente dados: use só o que foi realmente encontrado nas páginas abertas.
```

### Testes / Aceite
- `SkillRegistryTest`: `research` presente; `requiredToolNames()` contém os 5 tools;
  `maxRounds()` == 12. Uma skill sem `MaxRodadas:` → `maxRounds()` == 0.
- Teste em `AgentService` (siga os de `requiredToolName`): skill com `Ferramentas:` expõe **todas**
  as ferramentas listadas; skill singular continua expondo só a sua.
- Manual: "pesquisa os 3 frameworks JS mais usados em 2026" → o painel de atividade mostra os tools
  de web executando e a resposta vem como tabela com fontes, sem morrer no limite.

---

## Entrega E — Gráficos de dados reais

### Decisão
**Fase inclusa (fazer agora):** gráfico como **SVG inline dentro do `ui-preview`** (Entrega B). Zero
dependência. Barras e linhas simples o modelo desenha bem.

**Fase 2 (NÃO fazer sem o usuário pedir):** tool backend `generate_chart` com lib Java de charting
→ PNG registrado como mídia. Só se pedirem gráfico pixel-perfeito de dados brutos.

### Passo (fase inclusa)
Na instrução da Entrega B (`tools.md` e `visual-report.md`), acrescente uma linha:
```
- Para gráfico de barras/linhas, desenhe SVG inline: um <svg> com eixos, <rect> para barras ou
  <polyline> para linha, rótulos com <text>. Escale os valores para caber na viewBox. Sem libs.
```

### Aceite
- "faz um gráfico de barras desses números: 10, 25, 40" → ui-preview com SVG legível.

---

## Ordem de execução (siga esta sequência)

1. **A** (remark-gfm) — rápida, base pra síntese da D.
2. **B** (ui-preview) — zero dep, maior ganho; base pra E e pro alvo do PDF.
3. **E fase inclusa** (SVG em ui-preview) — sai junto da B.
4. **C** (generate_pdf) — exporta o que A/B produzem.
5. **D** (/research) — junta tudo e conserta o teto de rodadas.

## Definition of Done (marque cada uma por entrega)
- [ ] `spotless:apply` rodado; DTOs nos pacotes `*.dto`; nada de record em controller.
- [ ] Sem `responseType` manual em chamada de envelope; download usa `blob`.
- [ ] Políticas e `~/.avento/` intactos.
- [ ] Pacote npm (se houver) com `--save-exact --ignore-scripts`; diff do lockfile revisado.
- [ ] `mvn test` (≥329) e `npm run validate` verdes; teste novo incluído.
- [ ] Doc atualizada: README.md, README.en.md, docs/*.md, docs.html.
- [ ] Commit coeso, em inglês, **sem push** — aguardar o usuário.
```
