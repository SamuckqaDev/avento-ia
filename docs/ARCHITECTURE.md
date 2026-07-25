# Arquitetura atual do Avento

Este documento descreve o que existe no repositorio hoje. Ele nao representa a arquitetura futura
nem uma lista de funcionalidades desejadas. A intencao e permitir que uma pessoa nova entenda onde
cada responsabilidade esta, como os processos se comunicam e em quais pontos investigar um erro.

## Visao geral

O Avento e uma aplicacao local-first formada por um frontend React e um backend Spring Boot. O
backend e o centro da arquitetura: autentica o usuario, persiste as conversas, conversa com os
modelos locais, executa ferramentas, controla permissoes e integra os servicos de voz e midia.

```mermaid
flowchart TB
    USER["Usuario no navegador"]

    subgraph FRONT["Frontend - React, TypeScript e Vite"]
        HOME["Home e componentes do chat"]
        REST["Axios - APIs REST e cookie HttpOnly"]
        STREAM["Fetch - stream de eventos do agente"]
        AUDIO["Web Audio, MediaRecorder e WebSocket"]
        UIPREVIEW["Visualizador HTML isolado"]
    end

    subgraph BACK["Backend - Spring Boot"]
        SECURITY["Spring Security e autenticacao por cookie"]
        API["Controllers REST, stream e WebSocket"]
        ORCH["AgentOrchestrator e AgentService"]
        JOBS["AgentRunJob, Outbox e Agent Worker"]
        EVENTS["RunEventPublisher e SSE"]
        INTENT["IntentRouter, skills e instrucoes"]
        PERMISSION["Permission Engine"]
        TOOLS["ToolExecutionGateway"]
        MEDIA["Jobs assincronos de imagem e video"]
        VOICE["VoiceController e transcricao"]
        DOCS["DocumentReaderService"]
    end

    subgraph LOCAL_AI["Modelos e runtimes locais"]
        OLLAMA["Ollama - chat, visao e embeddings"]
        COMFY["ComfyUI - imagem e video"]
        WHISPER["FFmpeg e Whisper.cpp - fala para texto"]
        PIPER["Piper - texto para voz"]
        MARKITDOWN["MarkItDown - documentos"]
    end

    subgraph DATA["Dados locais"]
        POSTGRES["PostgreSQL - dados duraveis"]
        REDIS["Redis Stack - filas, eventos, contexto, vetores e cache"]
        FILES["Arquivos gerados e backups"]
    end

    subgraph MCP["Servidores MCP separados"]
        MCPCLIENT["McpClientManager - cliente MCP Java"]
        MCPSERVERS["Filesystem, Git, DBHub, navegador, macOS e outros"]
    end

    USER --> HOME
    HOME --> UIPREVIEW
    HOME --> REST
    HOME --> STREAM
    HOME --> AUDIO
    REST --> SECURITY
    STREAM --> SECURITY
    AUDIO --> SECURITY
    SECURITY --> API
    API --> JOBS
    JOBS --> REDIS
    REDIS --> JOBS
    JOBS --> ORCH
    ORCH --> EVENTS
    EVENTS --> REDIS
    REDIS --> EVENTS
    EVENTS --> STREAM
    ORCH --> INTENT
    ORCH --> OLLAMA
    ORCH --> PERMISSION
    PERMISSION --> TOOLS
    TOOLS --> MCPCLIENT
    MCPCLIENT -->|"MCP via stdio"| MCPSERVERS
    TOOLS --> MEDIA
    API --> VOICE
    API --> DOCS
    MEDIA --> COMFY
    VOICE --> WHISPER
    VOICE --> PIPER
    DOCS --> MARKITDOWN
    ORCH --> POSTGRES
    API --> POSTGRES
    API --> REDIS
    MEDIA --> FILES
    TOOLS --> FILES
```

## Por que o backend fica no centro

O navegador nao recebe o JWT em JavaScript. O token fica em um cookie `HttpOnly`, e o frontend usa
`Axios` com credenciais para as chamadas REST. O backend valida a sessao antes de acessar chats,
workspaces, midias, ferramentas ou dados persistidos.

Essa fronteira tambem evita que o frontend chame diretamente Ollama, ComfyUI ou um servidor MCP.
Assim, autenticacao, permissoes, auditoria e isolamento por usuario continuam no mesmo lugar.

### Contrato HTTP JSON

Controllers REST retornam `ResponseEntity<BaseResponse<T>>`. O envelope possui `status`, `code` e
`data`: o primeiro acompanha o status HTTP real, o segundo identifica o resultado de forma estavel e
o terceiro carrega o DTO produzido. Uma busca sem resultados continua sendo sucesso e retorna uma
colecao vazia em `data`, nunca `null`.

```json
{
  "status": 200,
  "code": "SUCCESS",
  "data": []
}
```

Erros de validacao, dominio, seguranca e falhas inesperadas passam pelo `ApiExceptionHandler`. Nesse
caso, o erro e o dado retornado e por isso fica dentro de `data`:

```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "data": {
    "message": "Request validation failed.",
    "path": "/api/projects/analyze",
    "timestamp": "2026-07-17T12:00:00Z",
    "traceId": "...",
    "errors": [{ "field": "path", "message": "must not be blank" }]
  }
}
```

`RequestTraceFilter` devolve o mesmo identificador no header `X-Trace-Id`. O Spring Security usa a
mesma fabrica de erros, inclusive para `401` e `403`. Entidades JPA de chat, mensagem e notificacao
nao atravessam a borda HTTP; controllers convertem entradas e saidas por DTOs.

O interceptor em `front/src/services/apiClient.ts` desembrulha apenas envelopes JSON. SSE, WebSocket,
audio sintetizado e download de midia ficam fora do envelope porque seus conversores e protocolos
exigem o corpo bruto.

## Caminho de uma mensagem

```mermaid
sequenceDiagram
    actor U as Usuario
    participant F as Frontend
    participant C as LocalAiOrchestratorController
    participant D as PostgreSQL
    participant R as Redis Streams
    participant W as AgentRunWorker
    participant O as AgentOrchestrator
    participant A as AgentService
    participant L as Ollama
    participant P as Permission Engine
    participant T as ToolExecutionGateway
    participant M as Ferramenta local ou MCP

    U->>F: Envia uma mensagem
    F->>C: POST /api/ai/runs com cookie e chatId
    C->>D: Salva job QUEUED e Outbox
    C-->>F: 202 Accepted com runId
    D->>R: Dispatcher publica referencia do job
    R->>W: Worker consome pelo consumer group
    W->>O: Inicia uma execucao com o mesmo runId
    O->>A: Conduz a execucao com o runId persistido
    A->>L: Envia mensagens, skills e ferramentas disponiveis
    L-->>A: Texto ou chamada de ferramenta

    alt resposta em texto
        A->>R: Publica chunks e eventos
        R-->>F: SSE autenticado do run
    else chamada de ferramenta
        A->>P: Avalia risco e permissao
        P->>R: Solicita aprovacao quando necessaria
        R-->>F: Evento de aprovacao
        A->>T: Executa a chamada aprovada
        T->>M: Chama provider local ou servidor MCP
        M-->>T: Resultado verificavel
        T-->>A: Resultado normalizado
        A->>L: Continua a conversa com o resultado real
        L-->>A: Resposta final em chunks
        A->>R: Publica resposta final
        R-->>F: SSE do run
    end

    W->>D: Persiste estado terminal do job
    A->>D: Persiste timeline
    F-->>U: Atualiza o chat progressivamente
```

Cada execucao carrega `userId`, `chatId` e `runId`. Essa combinacao impede que o thinking, uma
aprovacao ou o resultado de uma ferramenta seja transferido para outra conversa quando o usuario
troca de chat.

### Isolamento de dados e estado recuperavel

O identificador do usuario autenticado nao vem do JSON do navegador: ele vem exclusivamente do
cookie validado pelo Spring Security. Controllers e ferramentas propagam esse dono pelo
`AuthPrincipal` e pelo `ToolExecutionContext`.

| Estado | Chave de isolamento | Fonte duravel |
|---|---|---|
| Chats, mensagens e midias | `userId + chatId` | PostgreSQL |
| Memoria de longo prazo | `userId + status` | PostgreSQL |
| Preferencias de voz/thinking | `avento:user:{userId}:settings` | Redis |
| Jobs e planos idempotentes | `userId + chatId + idempotencyKey` | PostgreSQL |
| Aprovacoes pendentes | `approvalId + userId + chatId + runId` | PostgreSQL |
| Rollback de arquivos | `userId + chatId + runId` | PostgreSQL + disco local |

Memorias antigas sem dono podem continuar fisicamente na tabela durante a evolucao do banco local,
mas nunca sao consultadas nem injetadas no prompt. Uma aprovacao pendente serializa a continuacao da
ferramenta; depois de reiniciar o backend, o mesmo card pode ser aprovado uma unica vez e retomar a
run original. Estados em `ConcurrentHashMap` permanecem somente como aceleracao de processo, nao
como fonte de verdade.

### Estruturas de dados escolhidas

As colecoes seguem o tipo de acesso, sem trocar `List` mecanicamente:

- `List`: mensagens, timeline, resultados ordenados e contratos de API, onde ordem e repeticao sao
  parte do comportamento;
- `Set`/`HashSet`: nomes de ferramentas, frases e membership frequente em O(1);
- `LinkedHashSet`: deduplicacao de `runId` preservando a ordem mais recente durante a retencao;
- `Map`/`ConcurrentHashMap`: lookup por identificador e aceleracao concorrente dentro do processo;
- `Deque`/`ConcurrentLinkedDeque`: pilha de backups legados, sem o custo e a ambiguidade de
  `LinkedList`;
- indices PostgreSQL: consultas por ownership/status/run. Para dados duraveis, o indice e a
  paginacao trazem mais ganho que substituir uma lista depois de ela ja ter sido carregada.

O estado que precisa sobreviver a restart nunca depende somente dessas colecoes Java: PostgreSQL e a
fonte de verdade; Redis transporta jobs/eventos e mantem caches reconstruiveis.

### Composicao das instrucoes do agente

O frontend envia a mensagem original e as opcoes da interface, mas nao decide nem filtra o conteudo
da resposta. Antes de chamar o modelo, o backend monta o contexto de sistema com fontes versionadas
e separadas por responsabilidade:

```mermaid
flowchart LR
    BASE["agent/instructions/*.md"] --> BUILD["AgentService monta o contexto"]
    POLICY["agent/policies/{modo}.md"] --> BUILD
    LOCAL["~/.avento/policies/{modo}.md opcional"] --> BUILD
    SKILL["Skill explicita ou ativada por gatilho"] --> BUILD
    USER["Mensagem original do usuario"] --> BUILD
    ROOTS["Workspaces e continuidade"] --> BUILD
    BUILD --> MODEL["Modelo local selecionado"]
```

`maximum.md`, `professional.md` e `protected.md` definem a politica publica sem misturar esse texto
no codigo Java. Antes de carregar o recurso embutido, `AgentService` procura um override pessoal com
o mesmo nome em `~/.avento/policies/` (ou em `AVENTO_AGENT_POLICY_OVERRIDE_DIR`). O arquivo local
substitui a politica publica, permanece fora do Git e permite configuracao por maquina. As skills
acrescentam procedimentos especificos e nao substituem a politica. Quando a ativacao e automatica,
`AgentService` preserva integralmente a mensagem original e anexa a skill ao mesmo turno.

O cabecalho de uma skill (logo apos o titulo) aceita, em qualquer ordem, `Gatilhos:` (frases que
ativam a skill sem barra) e `Ferramenta:` (a ferramenta que a skill comanda). A skill sempre passa
pelo modelo — ele raciocina sobre o pedido e decide a chamada. O determinismo NAO vem de pular o
modelo, e sim de garantir que a ferramenta declarada esteja exposta com prioridade na selecao
(`AgentRunState.requiredToolName`), imune as heuristicas de keyword que antes desviavam pedidos de
video para `generate_image`. `generate-video` declara `generate_video` e ganhou gatilhos;
`generate-image` declara `generate_image` mas fica sem gatilhos de proposito, porque "gera uma
imagem" ja e capturado pelo detector direto de imagem.

A skill `translate-content` separa transformacao de criacao: pedidos diretos de traducao mantem o
texto fornecido, o registro, os palavroes e a linguagem adulta ou explicita. Isso evita que uma
traducao fiel seja tratada como endosso ou como um novo pedido de geracao de conteudo.

As politicas e os procedimentos internos diretamente ligados a imagem e traducao usam ingles para
melhor aderencia de instruction-following em modelos locais. Essa escolha e interna: a identidade do
agente ainda determina que a resposta acompanhe o idioma do usuario.

## Agente, ferramentas e MCP

O modelo nao executa uma ferramenta diretamente. O fluxo atual passa por estas camadas:

1. `IntentRouter` reduz o conjunto de ferramentas para o tipo de pedido atual.
2. `SkillRegistry` adiciona um procedimento especializado quando uma skill e ativada.
3. `AgentService` conduz os turnos com o modelo e interpreta as chamadas de ferramenta.
4. `AgentPermissionService` decide se a chamada pode seguir ou precisa de aprovacao.
5. `ToolExecutionGateway` cria uma fronteira unica para ferramentas internas e externas.
6. `McpClientManager` inicia servidores MCP, descobre schemas e faz chamadas pelo SDK Java oficial.

MCP significa Model Context Protocol. No Avento, ele e o protocolo usado para conectar ferramentas;
nao e o modelo, a memoria nem o orquestrador. Os servidores MCP normalmente rodam como processos
separados e conversam com o backend por entrada e saida padrao (`stdio`).

## Imagem e video

Os dois recursos usam jobs persistidos e não ocupam o worker do agente enquanto o modelo visual processa:

```mermaid
flowchart LR
    REQUEST["Pedido visual"] --> ROUTE{"Tipo"}
    ROUTE -->|"Imagem"| IMAGE["generate_image"]
    IMAGE --> IMAGEJOB["Cria image_generation_job"]
    IMAGEJOB --> IMAGEWORKER["Worker de imagem executa o provider local"]
    IMAGEWORKER --> SAVEIMAGE["Salva PNG e registra a midia"]

    ROUTE -->|"Video"| VIDEO["generate_video"]
    VIDEO --> JOB["Cria job no PostgreSQL"]
    JOB --> BACKGROUND["Worker acompanha o ComfyUI em background"]
    BACKGROUND --> SAVEVIDEO["Salva WebP e conclui o job"]
```

Imagem e video devolvem o identificador do job imediatamente. O frontend consulta o endpoint do
tipo de midia a cada dois segundos e mostra etapa, progresso estimado, tempo decorrido, previsao e
cancelamento. Ao concluir, o card dispara a atualizacao da galeria somente depois que o arquivo foi
registrado no PostgreSQL.

Arquivos concluidos sao registrados com o proprietario e o chat em PostgreSQL e armazenados, por
padrao, em `~/Pictures/Avento Generated Images`.

### Traducao do prompt de imagem

O encoder CLIP dos checkpoints SDXL entende apenas ingles e trunca o prompt em 77 tokens, entao um
pedido em portugues chegava ao modelo visual como ruido. Antes do `ImagePromptPlanner`, o
`ImagePromptTranslator` envia o pedido ao modelo de conversa (Ollama, `think:false`, temperatura
0.1) com uma instrucao de traducao literal: preservar cada detalhe pedido e nunca inventar
elementos. Qualquer falha — timeout, HTTP, resposta vazia ou verborragica — mantem o prompt
original. O planner tambem passou a montar o prompt positivo com o conteudo do usuario primeiro:
se o CLIP truncar algo, corta as instrucoes de reforco do planner, nunca o pedido em si.

Configuracao: `avento.image.translation-enabled` (padrao `true`),
`avento.image.translation-model` (padrao: o modelo do agente) e
`avento.image.translation-timeout-seconds` (padrao `45`).

### Presets por modelo de imagem

Cada familia de checkpoint tem seu ponto ideal de sampler, passos, CFG e resolucao nativa. O
`ImageModelPresetCatalog` resolve o preset do checkpoint selecionado a partir de
`comfyui/model-presets.json` (bundled): `sdxl-photoreal` (RealVisXL, JuggernautXL e demais SDXL),
`flux2-klein` (4 passos, CFG 1.0) e `stable-diffusion-1.5` como catch-all (`"match": ["*"]`).
Dentro do preset, o seletor de qualidade do usuario (draft/balanced/quality) escolhe a faixa, e
ajustes manuais sempre vencem: um CFG explicito sobrepoe o do preset e o aspect ratio continua
moldando a resolucao.

O usuario pode sobrepor ou adicionar presets sem recompilar em `~/.avento/image-presets.json`
(caminho configuravel por `avento.image.presets-file`). O arquivo local e relido a cada geracao —
edicoes valem sem reiniciar o backend — e entradas locais tem prioridade sobre as bundled; um JSON
invalido e ignorado com aviso no log, caindo de volta nos presets bundled.

Cada preset tambem declara o `promptStyle` do encoder do modelo. `tags` (SDXL/SD 1.5) recebe o
prompt do planner com pesos e reforcos; `natural` (FLUX.2 Klein, encoder LLM qwen) recebe o pedido
traduzido do usuario como frase — o encoder LLM segue linguagem natural e trata sopa de keywords
como ruido, o que fazia o FLUX.2 ignorar o pedido. O destilado (`flux-2-klein-4b`) roda em 4
passos com CFG 1.0 (recomendacao oficial; mais passos piora); a variante base
(`flux-2-klein-base-*`) tem preset proprio com 16-24 passos e CFG 3.5-4.5. A ordem das entradas
importa: `flux2-klein-base` vem antes de `flux2-klein` porque o match e por substring.

## Saida visual: tabelas, relatorios e PDF

O chat renderiza tabelas Markdown (GFM via `remark-gfm`), `~~riscado~~` e listas de tarefa. Para
relatorios, dashboards e graficos, o modelo responde com um bloco `ui-preview` autocontido (HTML/CSS
inline, sem rede), renderizado no mesmo iframe isolado dos prototipos; a skill `visual-report`
orienta esse formato e o SVG inline dos graficos.

A ferramenta `generate_pdf` (`PdfGenerationService`) converte Markdown com tabelas
(`commonmark-java` + extensao de tabelas) ou HTML em PDF via `openhtmltopdf`, de forma sincrona.
O arquivo `avento-doc-*.pdf` e salvo na pasta de midia, registrado com `mediaType=document`, servido
pelo mesmo `GET /api/media/{filename}` (que passou a reconhecer `.pdf`) e apagado junto com o chat.
O balao mostra um card de download a partir do marcador `[[avento-doc:...]]`.

A skill `/research` fecha o ciclo "pesquisa -> visual": alem de `Gatilhos:`, o cabecalho aceita
`Ferramentas:` (varias, forcadas na selecao com prioridade) e `MaxRodadas:`, que eleva o teto de
rodadas apenas para aquela run via `AgentRunState.maxToolRoundsOverride` — o teto global de 6
continua protegendo as demais conversas de loop. O procedimento busca, extrai e sintetiza o
resultado numa tabela ou num `ui-preview`, com fontes citadas, e oferece exportar em PDF.

### Midias e artefatos do chat

O mockup `ui-preview` continua renderizando inline no chat (iframe isolado com CSP), mas agora
tambem e persistido como artefato baixavel: ao salvar a mensagem do assistente
(`POST /api/chats/{id}/messages`), o `MockupArtifactService` extrai cada bloco `ui-preview` e grava
um `avento-mockup-*.html` registrado com `mediaType=artifact`. A secao lateral "Midias e Artefatos"
lista imagem, video, PDF (`document`) e mockup (`artifact`) — o `MediaItem` carrega o campo `type`
para o icone/rota certos. Documento abre em nova aba. O HTML do artefato e buscado como texto e
aberto pelo frontend em uma URL `blob:` de origem opaca; ele nunca executa na origem autenticada do
Avento. `GET /api/media/chat/{chatId}/artifacts.zip` empacota todos os artefatos e PDFs da conversa.
O `MediaController` resolve cada arquivo pelo registro pertencente ao usuario, serve `.html` com
`nosniff`, CSP sandbox e disposition de download, e escreve o ZIP em streaming para nao duplicar
todos os arquivos na memoria da JVM.

## Verificar e corrigir (loop de qualidade)

A ferramenta `verify_project` (`ProjectVerificationService`) fecha o ciclo editar -> verificar ->
corrigir. Ela detecta sozinha o comando canonico de verificacao do workspace — `package.json` com
`validate`/`build`/`typecheck`/`test`/`lint` (nessa ordem de preferencia) ou `pom.xml` (`mvn test`) —
roda via `ProjectCommandService` (allowlist de scripts/goals) e devolve `{ ok, command, exitCode,
errorSummary }`, com os erros resumidos as linhas relevantes para caber no contexto do modelo local.
A skill `verify-and-fix` e a instrucao base orientam o agente a chamar `verify_project` apos editar
codigo e iterar ate `ok:true`, sem nunca declarar a mudanca pronta com a verificacao vermelha. A
ferramenta entra no kit fixo de chats de projeto e pede aprovacao como as demais que executam
comandos.

## Desfazer alteracoes (rollback por run)

Toda edicao de arquivo do agente passa pelo `FileBackupService` (backup antes de escrever/apagar).
Os manifestos ficam no PostgreSQL, agrupados por `userId + chatId + runId`, e a ferramenta
`revert_changes` restaura somente a ultima resposta daquele usuario e chat ao estado anterior — do
backup mais novo para o mais antigo, de modo que o estado ORIGINAL
prevaleca mesmo que o mesmo arquivo tenha sido editado varias vezes na run. Chamar de novo desfaz a
resposta anterior. A skill `revert-changes` (com `Ferramenta: revert_changes`) roteia "desfaz",
"reverte as alteracoes", "volta o que voce mudou" direto para a ferramenta, que pede aprovacao antes
de sobrescrever. Cobre criacao, edicao e exclusao de arquivos e diretorios. Diretorios acima de
5.000 arquivos continuam exigindo aprovacao, mas nao sao copiados. As ultimas 30 runs por conversa
sao mantidas e backups orfaos antigos passam por rotacao automatica.
O cleanup roda uma vez apos o backend ficar pronto e depois em intervalo configuravel. As execucoes
sao serializadas e a remocao tolera arquivos que desaparecam durante a varredura; como se trata de
manutencao best-effort, uma falha de limpeza e registrada no log sem encerrar a aplicacao.

## Navegacao de codigo por simbolo

A ferramenta `find_symbol` (`SymbolSearchService`) acha ONDE um simbolo e DEFINIDO — classe,
interface, record, enum, funcao, metodo, type, const — em vez de listar toda mencao (o `search_files`
so busca por nome de arquivo). Varre os arquivos-fonte do workspace (pulando node_modules, target,
build, .git etc.), casa padroes de definicao por linguagem (Java, TS/JS, Python, Go, Rust, etc.) e
evita casar chamadas (`new Foo()` nao entra como definicao de `Foo`). Retorna arquivo, linha e o
texto da definicao — o agente usa para entender e navegar o projeto antes de editar, sem reler tudo.
E read-only (auto-aprovada) e faz parte do kit fixo de chats de projeto. Zero dependencia externa.

## Modo Plano de Implementacao (planejar antes de codar)

Para uma tarefa de codigo, a skill `implementation-plan` coloca o agente em MODO PLANO: ela declara
`Ferramentas: directory_tree, read_file, read_document, search_files` — um kit **so-leitura** forcado
na selecao (via `AgentRunState.requiredToolNames`), entao o modelo explora e cita arquivos reais mas
**nao consegue editar, criar, apagar nem rodar nada**. A resposta e um bloco `impl-plan` estruturado
(Objetivo, Arquivos afetados, Passos, Riscos, Como verificar). O frontend renderiza esse bloco como
um card (`ImplPlanCard`) com **Aprovar e executar** / **Ajustar**. "Aprovar" dispara um evento que o
`Home` escuta e envia o conteudo exato do plano, junto com chat, indice da mensagem e chave
idempotente. O botao trava no primeiro clique e o PostgreSQL devolve a mesma run em uma repeticao,
evitando duas implementacoes concorrentes. A execucao recebe o kit completo e fecha no
`verify_project` (loop de verificar-e-corrigir).
Assim o ciclo fica: planeja (so-leitura) -> aprova -> executa -> verifica.

## Planos autonomos tarefa a tarefa

O painel **Plano de execucao** complementa o card de plano textual acima. O usuario informa um
objetivo e os workspaces ja autorizados; `PlanBuilderService` pede ao modelo somente uma definicao
JSON limitada a 20 tarefas e persiste `AgentPlan` e `AgentTask`, sempre com `userId` e `chatId`.
O painel mostra apenas os planos da conversa atual e permite editar, reordenar, pular, aprovar,
pausar, retomar e cancelar.

`PlanExecutionService` executa uma tarefa por vez em um executor local limitado. Cada tarefa gera
um `AgentRunJob` idempotente no backbone existente (PostgreSQL + Outbox + Redis Streams + worker),
recebe apenas titulo, detalhes, arquivos-alvo e ate dez resumos curtos das etapas anteriores. As
edicoes continuam passando pelo `FileBackupService`, que registra os backups com o run da tarefa;
depois da execucao, cada workspace passa pelo `ProjectVerificationService`. Uma verificacao
vermelha desfaz a tentativa e repete no maximo uma vez; nova falha pausa o plano.

```mermaid
flowchart LR
    GOAL["Objetivo no chat"] --> BUILD["PlanBuilderService"]
    BUILD --> DB["AgentPlan e AgentTask no PostgreSQL"]
    DB --> RUN["PlanExecutionService"]
    RUN --> JOB["AgentRunJob + Outbox"]
    JOB --> REDIS["Redis Streams"]
    REDIS --> WORKER["AgentRunWorker"]
    WORKER --> TOOLS["Ferramentas com backup por run"]
    TOOLS --> VERIFY["ProjectVerificationService"]
    VERIFY -->|"ok"| NEXT["Proxima tarefa"]
    VERIFY -->|"falha"| ROLLBACK["Rollback e tentativa limitada"]
    NEXT --> SSE["Eventos SSE do plano"]
```

O plano e as tentativas ficam persistidos. Se o backend reiniciar com um plano `RUNNING`, a tarefa
interrompida volta a `PENDING`, reutiliza a mesma chave idempotente e o executor retoma sem criar
uma segunda execucao logica. As raizes persistidas pelo plano sao registradas novamente para o
mesmo dono antes da retomada. O SSE e apenas transporte de atualizacao; polling curto no painel e o
estado no PostgreSQL permitem reconstruir a tela mesmo quando a conexao de eventos cai.

## Prototipos de interface

Uma proposta de tela nao precisa passar pelo pipeline de imagem. A skill `prototype-interface`
orienta o modelo de conversa a produzir um documento HTML autocontido em um bloco `ui-preview`.
Como o bloco faz parte da mensagem, a persistencia existente do chat tambem preserva o prototipo.

```mermaid
flowchart LR
    REQUEST["Descricao da tela"] --> SKILL["Skill prototype-interface"]
    SKILL --> HTML["HTML, CSS e JS autocontidos"]
    HTML --> CHAT["Mensagem persistida"]
    CHAT --> IFRAME["Iframe local com sandbox e CSP"]
    IFRAME --> REVIEW["Revisao desktop, tablet e celular"]
    REVIEW -->|"ajustar"| REQUEST
    REVIEW -->|"aprovado"| CODE["Implementacao no workspace"]
    CODE --> PLAYWRIGHT["Validacao da pagina real"]
```

O iframe inicia sem scripts; interacoes podem ser habilitadas explicitamente dentro de um sandbox
sem mesma origem. A CSP bloqueia chamadas de rede, formularios e frames, enquanto o sandbox impede
acesso a cookies, DOM do Avento e navegacao da janela principal. O recurso reduz o custo de memoria
porque nao carrega checkpoint, VAE ou ComfyUI. Um modelo visual pode revisar screenshots posteriormente, mas
nao faz parte do caminho obrigatorio. O fluxo completo esta em
[Prototipacao local de interfaces](INTERFACE_PROTOTYPING.md).

## Voz

Existem dois caminhos independentes:

```mermaid
flowchart LR
    MIC["Microfone"] --> WEBM["MediaRecorder - WebM"]
    WEBM --> FFMPEG["FFmpeg"]
    FFMPEG --> WHISPER["Whisper.cpp"]
    WHISPER --> TEXT["Texto no chat"]

    ANSWER["Texto da resposta"] --> NORMALIZE["Remove Markdown, codigo e emojis"]
    NORMALIZE --> PIPER["Piper"]
    PIPER --> WAV["WAV"]
    WAV --> BROWSER["Fila de audio no navegador"]
```

O Whisper.cpp executa STT, isto e, transforma fala em texto. O Piper executa TTS, transformando
texto em fala. O modo de voz em tempo quase real usa WebSocket para enviar as falas capturadas; o
audio sintetizado volta por uma rota HTTP e e reproduzido em fila pelo frontend.

## Persistencia

| Componente | Responsabilidade atual |
|---|---|
| PostgreSQL | Usuarios, sessoes, chats, mensagens, permissoes, auditoria, timeline, midias e jobs |
| Redis Stack | Fila do agente, eventos do run, contexto recente, vetores do RAG e caches opcionais |
| Sistema de arquivos | Midias geradas, backups, modelos, runtimes e logs locais |

PostgreSQL e a fonte duravel dos dados da aplicacao. Redis acelera consultas e recursos derivados;
ele nao deve ser a unica copia de uma conversa ou de um job importante.

O envio do agente usa o padrao Outbox: job e evento de publicacao entram na mesma transacao do
PostgreSQL. O dispatcher envia a referencia para `avento:jobs:agent`, o worker executa e publica em
`avento:events:{runId}`, e o frontend recebe pelo endpoint SSE isolado daquele run. O cache
`avento:context:{userId}:{chatId}` guarda somente a janela recente e e reconstruido do banco quando
expira ou quando Redis fica indisponivel. Veja [Execucao assincrona com Redis](REDIS_EXECUTION.md).

## Processos iniciados no desenvolvimento

`scripts/dev-up.sh` coordena o ambiente local:

```mermaid
flowchart LR
    SCRIPT["dev-up.sh"] --> DOCKER["PostgreSQL e Redis Stack"]
    SCRIPT --> OLLAMA["Ollama"]
    SCRIPT --> COMFY["ComfyUI"]
    SCRIPT --> BACK["Spring Boot :8000"]
    SCRIPT --> FRONT["Vite :5173"]
    SCRIPT --> LOGS["Logs em tmp/dev e terminal"]
    SCRIPT --> SMOKE["Smoke test autenticado"]
```

Os runtimes pesados e modelos nao fazem parte do Git. O repositorio guarda scripts, configuracoes e
workflows necessarios para encontra-los ou prepara-los na maquina local.

## Mapa do codigo

| Caminho | O que procurar ali |
|---|---|
| `front/src/pages/Home` | Estado principal da tela, conversas, streaming e integracao dos modulos |
| `front/src/hooks` | Chat em streaming, gravacao e reproducao de audio |
| `front/src/modules` | Componentes de chat, layout, aprovacoes, MCP e midias |
| `back/avento/pom.xml` | Parent POM Multi-Módulo Maven (avento-parent) |
| `back/avento/avento-core` | DTOs de API base, exceções e tratamento global de erros |
| `back/avento/avento-auth` | Autenticação Spring Security, filtros JWT e Usuários |
| `back/avento/avento-workspace` | Autorização de pastas, leitor/escritor de arquivos e backups |
| `back/avento/avento-mcp` | Cliente síncrono SDK MCP Java Stdio e catálogo MCP |
| `back/avento/avento-execution` | Outbox transacional, Redis Streams Worker e SSE |
| `back/avento/avento-agent` | Orquestração do agente, modelos Ollama, heurísticas e prompts |
| `back/avento/avento-media` | Integração ComfyUI (SDXL, vídeo WAN) e gerador de PDF |
| `back/avento/avento-voice` | Integrador Whisper.cpp, Piper TTS e WebSockets de voz |
| `back/avento/avento-rag` | MarkItDown, Embeddings Nomic e Redis VectorStore |
| `back/avento/avento-app` | Módulo executável Spring Boot (AventoApplication) |
| `scripts` | Setup, inicializacao, verificacao e smoke test |

## Limites atuais importantes

- O backend ainda concentra muitas responsabilidades, especialmente em `AgentService` e
  `McpController`; a modularizacao interna existe, mas nao sao microservicos separados.
- O progresso de imagem e video e estimado pelo custo configurado e pelo tempo decorrido; o ComfyUI
  ainda nao publica porcentagem fina por no para esses workflows.
- Aprovacoes preservam o mesmo `runId`, mas a continuacao pendente ainda vive em memoria e expira
  quando o backend reinicia.
- O TTS atual usa Piper, que prioriza execucao local e leveza, mas tem naturalidade limitada.
- Os servidores MCP aumentam as capacidades, mas muitos schemas conectados ao mesmo tempo podem
  ocupar contexto e piorar a escolha de ferramentas pelo modelo.
- O orcamento de contexto (`avento.agent.num-ctx`) e compartilhado entre o prompt de sistema, os
  schemas das ferramentas selecionadas e o historico compactado da conversa. Uma execucao com muitas
  rodadas de ferramenta (por exemplo, varias chamadas de `read_file` em sequencia) pode aproximar-se
  do limite mesmo com a compactacao de mensagens ativa; `max-total-message-content-chars` existe
  justamente para manter essa janela previsivel independente de quantas rodadas a tarefa tiver.
  Medido ao vivo: uma rodada com 23 ferramentas selecionadas (mensagem que aciona leitura de
  arquivo, terminal e MCP externo ao mesmo tempo) excedeu 6 minutos sem produzir nenhum sinal,
  enquanto o mesmo pedido com poucas ferramentas fecha em menos de 90s — a contagem de ferramentas
  pesa no custo de prompt_eval tanto quanto o tamanho do historico. Chats com projeto conectado
  usam um kit fixo de ferramentas de desenvolvimento (`project-toolkit`): as ferramentas de
  arquivo e terminal estao sempre presentes, e a lista estavel mantem o prefixo do prompt
  identico entre mensagens, permitindo que o cache de prompt do llama.cpp reaproveite o
  processamento do sistema + schemas. Quando a mensagem pede explicitamente algo fora do kit
  (conectar MCP, gerar imagem, criar scaffold), ate 6 ferramentas extras casadas com essa
  intencao entram junto naquela rodada, sem quebrar a estabilidade das mensagens puras de
  codigo. Chats sem projeto continuam com selecao por intencao,
  limitada por `max-tools-per-request` (ferramentas casadas com a intencao primeiro; as sempre
  expostas preenchem as vagas restantes). Se uma rodada terminar sem texto e sem chamada de
  ferramenta, o agente repete uma unica vez com instrucao explicita antes de avisar o usuario,
  em vez de completar a execucao em silencio.
  O padrao local usa uma janela de 16.384 tokens e `num-predict=4096` por rodada. Esse limite de
  geracao evita uma resposta sem teto, mas nao e uma reserva separada: prompt e resposta ainda
  compartilham `num-ctx`, portanto schemas e historico continuam sendo compactados.
- O ambiente foi desenhado primeiro para macOS e loopback; acesso remoto exige outra camada de
  seguranca e operacao.

As mudancas planejadas para esses pontos ficam em [Plano de evolucao](IMPLEMENTATION_PLAN.md).
