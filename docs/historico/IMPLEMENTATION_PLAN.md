# Plano didatico de evolucao

Este documento guarda as melhorias discutidas para execucao futura. Ele e um plano, nao uma
afirmacao de que os itens ja foram implementados. O estado real do projeto esta descrito em
[Arquitetura atual](ARCHITECTURE.md).

## Marco implementado - Redis, Outbox, worker e SSE

O primeiro corte vertical da infraestrutura assincrona foi concluido:

- `POST /api/ai/runs` persiste o job e uma Outbox na mesma transacao;
- Redis Streams transporta a referencia do job e os eventos do run;
- um consumer group executa o agente fora do request do navegador;
- o frontend acompanha a resposta por SSE usando `chatId` e `runId`;
- cancelamento interrompe a subscription ativa e atualiza o estado duravel;
- contexto recente usa Redis como cache e PostgreSQL como fallback;
- apagar o chat remove jobs, Outboxes e contexto relacionados;
- o frontend estatico antigo, assets de template e proxies sem consumidor foram removidos.

O guia completo fica em [Execucao assincrona com Redis](REDIS_EXECUTION.md). Ainda faltam
`XAUTOCLAIM`, agrupamento de deltas, resumo incremental e a generalizacao dos jobs de midia.

## Forma de trabalho

Cada fase deve ser executada separadamente e seguir a mesma sequencia:

1. registrar o comportamento atual;
2. explicar o problema observado;
3. apresentar o conceito tecnico envolvido;
4. comparar alternativas e justificar a decisao;
5. implementar uma alteracao pequena e rastreavel;
6. validar manualmente e com testes automaticos;
7. atualizar a documentacao no mesmo conjunto de mudancas;
8. apresentar os arquivos alterados e o fluxo antes/depois.

O objetivo e tornar a evolucao compreensivel para quem esta conhecendo MCP e para quem encontrar o
repositorio publico pela primeira vez.

## Fase 0 - Fundamentos e arquitetura

### Objetivo

Explicar como frontend, backend, modelo, orquestrador, Permission Engine, ferramentas e servidores
MCP se relacionam.

### Entregas

- diagrama do estado atual;
- glossario de host, cliente, servidor, tool, resource e prompt no MCP;
- caminho completo de uma mensagem e de uma chamada de ferramenta;
- mapa de codigo para investigacao de erros.

### Criterio de conclusao

Uma pessoa nova deve conseguir identificar quem toma decisoes, quem executa acoes e onde cada dado
e armazenado sem precisar ler o codigo inteiro.

## Fase 1 - Voz natural e interrupcao confiavel

### Problemas observados

- a voz atual soa robotizada;
- desligar a voz pode nao impedir que chunks antigos voltem a enfileirar audio;
- a sintese e entregue em arquivos WAV por trechos, sem um provider abstrato de TTS.

### Conceitos a explicar

- diferenca entre STT e TTS;
- modelo de voz, idioma, ritmo, ruido e pausas;
- fila de reproducao, cancelamento e `AbortController` no navegador;
- streaming de texto para audio e fallback local.

### Implementacao planejada

- [x] impedir que callbacks de um stream antigo religuem a voz;
- [x] cancelar audio atual, URLs temporarias, fila e requisicoes TTS pendentes;
- [x] persistir o mute global e limitar a reproducao ao chat atualmente aberto;
- comparar TTSs locais compativeis com o hardware antes de escolher um provider principal;
- criar uma interface de provider e manter Piper como fallback;
- sintetizar frases progressivamente, preservando pontuacao e idioma;
- mostrar estados claros de preparando, falando, pausado e interrompido.

### Criterios de aceite

- [x] um clique interrompe a fala e ela nao retorna sozinha;
- [x] trocar de chat nao move a voz para a conversa aberta;
- portugues e ingles usam modelos apropriados;
- falha do provider principal aciona o fallback sem quebrar o chat.

## Fase 2 - Imagens assincronas

### Problema observado

Imagem e video possuem jobs persistidos próprios, executores locais separados, progresso estimado,
cancelamento, retomada e atualização automática da galeria.

### Conceitos a explicar

- processamento sincrono e assincrono;
- fila, worker, estado de job e idempotencia;
- persistencia, retomada e cancelamento;
- progresso real versus progresso estimado.

### Implementacao concluida

- `image_generation_jobs` e `video_generation_jobs` preservam contratos próprios sem quebrar o
  histórico existente;
- os dois fluxos devolvem o identificador imediatamente e executam em workers locais únicos;
- o chat consulta o estado com Axios, atualiza a galeria na conclusão e permite cancelamento;
- refresh, reinício do backend e exclusão do chat preservam o ciclo de vida do job.

### Criterios de aceite

- o chat continua utilizavel durante a geracao;
- atualizar a pagina nao perde o job;
- o progresso aparece apenas no chat de origem;
- cancelar ou excluir o chat encerra o trabalho e remove os artefatos relacionados.

## Fase 3 - Streaming unificado

### Estado atual

O agente cria job por REST e transmite texto/ferramentas por Redis Streams + SSE. A voz em tempo
quase real usa WebSocket, enquanto imagem e video usam jobs próprios com consulta de estado.

### Conceitos a explicar

- REST, Server-Sent Events, `ReadableStream`, WebSocket e polling;
- eventos, reconexao, ordenacao e deduplicacao;
- isolamento por `userId`, `chatId` e `runId`.

### Implementacao planejada

- evoluir o envelope Redis ja implementado para incluir progresso fino de midia e voz;
- manter cada evento vinculado a conversa e execucao originais;
- transmitir progresso, nao arquivos binarios incompletos;
- adicionar consulta REST como fallback de recuperacao;
- impedir baloes duplicados, respostas vazias e eventos movidos ao trocar de chat.

### Criterios de aceite

- multiplos chats podem executar tarefas sem misturar estado;
- reconectar recupera o estado confirmado no backend;
- cada evento possui identificacao suficiente para deduplicacao e diagnostico.

## Fase 4 - Catalogo MCP gratuito

### Objetivo

Pesquisar, testar e documentar ferramentas locais ou gratuitas por funcao, sem conectar tudo em
todas as mensagens.

### Categorias da auditoria

- arquivos, documentos, PDF, Office e OCR;
- Git, codigo, terminal e ambientes de desenvolvimento;
- navegador, pesquisa e extracao de paginas;
- PostgreSQL, MySQL, SQLite, Redis e outros bancos relevantes;
- Docker, containers, logs e infraestrutura;
- macOS, Finder, Shortcuts e automacao de aplicativos;
- memoria, RAG, planejamento e bases de conhecimento;
- observabilidade e diagnostico.

### Informacoes obrigatorias por servidor

- projeto e mantenedor;
- licenca e custo;
- funcao e ferramentas expostas;
- requisitos de instalacao;
- transporte usado;
- permissoes e riscos;
- comandos de instalacao e teste;
- configuracao no Avento;
- estrategia de conexao automatica ou sob demanda;
- manutencao, compatibilidade e alternativa de fallback.

### Criterios de aceite

- o nucleo automatico permanece pequeno;
- ferramentas especializadas conectam apenas quando necessarias;
- falhas de dependencia aparecem com instrucao pratica;
- nenhum MCP recebe acesso mais amplo que o necessario.

## Fase 5 - Frontend, validacao e documentacao final

### Entregas

- feedback visual consistente para voz, streaming e jobs de midia;
- estados de carregamento, erro, cancelamento e retomada;
- revisao responsiva das areas alteradas;
- exemplos de uso e troubleshooting;
- validacao completa do backend, frontend, scripts e smoke test.

### Validacoes previstas

```bash
mvn -f back/avento/pom.xml test
npm --prefix front run validate
bash -n scripts/*.sh
docker compose config --quiet
```

## Decisoes ainda nao tomadas

Os itens abaixo exigem benchmark ou pesquisa antes da implementacao e nao devem ser escolhidos por
suposicao:

- provider TTS principal e modelo de voz por idioma;
- eventual unificacao de eventos de imagem, video e voz sobre os Redis Streams existentes;
- lista de novos servidores MCP e versoes compativeis;
- politica de auto-conexao por perfil.

Cada decisao deve ser registrada com alternativas consideradas, custo local, impacto de manutencao
e motivo da escolha.
