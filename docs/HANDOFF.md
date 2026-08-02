# Handoff — sessão de 02/08/2026

Contexto para retomar em outra conversa. **Tudo aqui foi verificado rodando comando**, e onde não
foi está marcado como não verificado. O handoff anterior (31/07–01/08) foi absorvido: o que ficou de
pendência dele está na seção "Pendências", o resto virou doc.

---

## Estado do repositório

- Branch `master`
- **672 testes, 0 falhas, 8 pulados** (`cd back/avento && mvn clean test`) — eram 653, mais 19 novos
- Frontend não foi tocado nesta sessão

---

## O que mudou nesta sessão

### O RAG foi ligado ao agente (a tarefa que estava marcada como "próxima")

O problema era ausência de ligação, não código errado: `RagService` tinha busca vetorial completa e
só o `RagController` (REST) a chamava, então o índice do projeto aberto ficava vazio para sempre,
enquanto a ferramenta do agente usava `CodebaseRagService` (`conteudo.contains(token)`).

O que foi feito:

- `WorkspaceAccessService` publica `WorkspaceRootRegisteredEvent` na **primeira** vez que uma raiz é
  registrada num escopo (a mesma pasta é re-registrada a cada mensagem; anunciar sempre viraria uma
  varredura por mensagem).
- `WorkspaceIndexingService` (novo, avento-rag) escuta e aquece o índice numa thread só, prioridade
  mínima, fora da requisição. Estados: `UNKNOWN`/`INDEXING`/`READY`/`FAILED` — `FAILED` é retentado,
  porque a causa comum é o modelo de embedding fora do ar.
- `CodeSearchService` (novo) é a junção: vetorial quando o índice está pronto, **literal** enquanto
  não está, quando o vetorial vem vazio, ou quando ele estoura. A resposta carrega `matching` dizendo
  qual dos dois respondeu — sem isso o modelo não sabe se vazio é "não existe" ou "não bate
  literalmente".
- Índice é por raiz de projeto; busca em subpasta consulta a raiz e recorta os resultados de volta.
- Reindexação disparada por **salvar arquivo** (`write_file`/`edit_file`), debounce de 15s. Não por
  mensagem.
- `vectorStore.add` passou a ir em lotes sequenciais de 32.
- Descrição de `search_code` atualizada para descrever os dois modos honestamente.

### O limiar de similaridade estava herdado de prosa

Medido com `nomic-embed-text` sobre 15 chunks reais deste repo e 4 perguntas:

| Busca | Score do chunk certo | Passava em 0.62? |
|---|---:|---|
| "onde verifico se um caminho está dentro do workspace" | 0,615 (1º lugar) | Não |
| "onde fica o backup antes de sobrescrever" | 0,734 (1º lugar) | Sim |
| `requireAuthorized` — nome exato do método | 0,489 (1º lugar) | Não |

Faixa observada: 0,317–0,734, mediana 0,484. **0.62 descartava a resposta certa em metade das
buscas**, inclusive com o nome exato de um método escrito no arquivo. Novo padrão: **0.45**.

**Medido e descartado:** `nomic-embed-text` é treinado com prefixo de tarefa (`search_query:` /
`search_document:`) e o Spring AI não aplica nenhum. Com prefixo todos os scores sobem alguns
centésimos mas a separação **piora** — margem média entre o chunk certo e o melhor errado cai de
+0,039 para +0,012. Não vale implementar.

### Onde as chaves novas moram (cuidado)

As chaves `avento.rag.*` foram para `application.yml` (base). O app roda com
`--spring.profiles.active=local`, então `application-local.yml` **sobrescreveria** o base — mas hoje
ele não tem bloco `rag:` nenhum (verificado), então os valores do base valem. Se um dia aparecer um
`rag:` no local, é lá que `similarity-threshold`, `embedding-batch-size` e `auto-index` passam a ser
decididos. Já aconteceu antes com `num-ctx` e `default-model`: editar só o base não mudou nada.

### Colima saiu do caminho

- `~/.zshrc`: removido o `export DOCKER_HOST` apontando para o socket do Colima (pendência do
  handoff anterior). O contexto ativo é `desktop-linux`.
- `dev-up.sh`: removido o fallback que subia Colima quando o Docker Desktop não está instalado. Ele
  não atende o plugin `docker mcp`, então subir Colima ali deixava a stack de pé com sete servidores
  MCP faltando em silêncio. Agora falha dizendo o que está errado.
- A VM do Colima continua parada e **os volumes seguem no disco** (`~/.colima/default`) — contêm 16
  chats antigos. Não apagar sem decidir o que fazer com eles.

### Medições de embedding (nesta máquina, M2 Pro 16 GB)

| Lote | Tempo | Por chunk |
|---:|---:|---:|
| 1 | 0,10s | 100,3 ms |
| 8 | 0,41s | 50,6 ms |
| 32 | 1,55s | 48,4 ms |

Platô a partir de ~8. Daí `embedding-batch-size: 32` e lote sequencial: paralelizar faria o modelo
de embedding disputar RAM com o de chat, e o chat é quem o usuário está esperando.

---

## NÃO medido / não validado

- **Primeira indexação de um projeto real de ponta a ponta.** O custo por chunk está medido, mas o
  total de chunks de um projeto grande não — a estimativa (~48 ms × N) não foi confirmada com o
  backend de pé.
- **Nenhuma busca vetorial rodou de verdade pelo agente.** Os testes cobrem a lógica de decisão com
  o `RagService` mockado; o caminho Redis + Ollama real não foi exercitado nesta sessão.
- O limiar 0.45 vem de uma amostra pequena (15 chunks, 4 perguntas). É melhor que 0.62 com margem
  larga, mas não é um número calibrado com rigor.

---

## Alavanca de performance ainda não usada

Continua valendo do handoff anterior: rodando `qwen3.5:9b` com `num-ctx: 32768`, enquanto a config
define `granite4.1:8b` como padrão e a anotação do usuário registra `granite4.1:8b @ 8192 ≈ 2,8s em
regime` contra `qwen3.5:9b @ 16384 = 34-46s`. O seletor de modelo **funciona** desde que o bug 6 foi
corrigido, mas exige restart do backend; o `num-ctx` precisa de edição no yml.

---

## Pendências

- `capture_screen`: precisa de permissão de Gravação de Tela nos Ajustes do Sistema
- FIXME em `image-prompt-signals.txt`: quatro literais com "pitbull" — fixture de teste que vazou
  para produção. O certo é um padrão `<verbo> <artigo> <assunto> que (eu) pedi`.
- Gatilho duplicado pré-existente: `research.md` e `web-research.md` compartilham
  "buscar na internet" e "pesquise na web"
- 22 classes duplicadas entre módulos (idênticas hoje, o teste impede divergirem) — entre elas
  `Manifest`, `ScannedFile` e `DocumentReadResult`, duplicadas entre avento-workspace e avento-rag
- Ferramentas nunca exercitadas: `generate_video`, `open/close_browser_tab`, `create_vite_project`,
  `run_shortcut`

---

## Convenções do projeto (importantes)

- **Inglês** em nome de branch, mensagem de commit e código novo. Texto que o usuário lê no chat
  fica em PT-BR informal.
- **Texto fora do código**: listas de palavras vão para `agent/heuristics/*.txt`, prompts para
  `agent/prompts/*.md`.
- **`policies/maximum.md` nunca entra num `git add -A`** — a versão experimental do usuário vive em
  `~/.avento/policies/`.
- Aprendizado que custou caro vira **um arquivo HTML por caso** em `docs/aprendizados/`, contando
  sintoma → causa raiz → conserto.
- O objetivo do projeto é ser **peça de portfólio de engenharia de IA**.
