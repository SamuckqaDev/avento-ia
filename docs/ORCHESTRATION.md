# Orquestracao do agente e MCP

O Avento usa um unico caminho assincrono para executar solicitacoes com ferramentas:

1. `LocalAiOrchestratorController` valida chat e workspaces e cria o job.
2. `AgentRunSubmissionService` grava job e Outbox na mesma transacao.
3. `RedisOutboxDispatcher` publica a referencia em `avento:jobs:agent`.
4. `AgentRunWorker` consome a fila e chama `AgentOrchestrator` com o `runId` persistido.
5. `AgentService` conduz os turnos do modelo e as aprovacoes.
6. `ToolExecutionGateway` chama o provider local/MCP e valida o resultado.
7. `RedisRunEventPublisher` publica chunks e eventos no Stream isolado `avento:events:{runId}`.
8. `RunEventStreamService` entrega o run por SSE somente ao proprietario.
9. `RunReplyPersistenceService` salva uma resposta visível por `runId` antes do evento terminal.

Cada execucao carrega `userId`, `chatId` e `runId` em `ToolExecutionContext`. Clientes MCP,
workspaces, permissoes lembradas, aprovacoes, processos, runs e timeline usam esse escopo; dados de
uma conversa nao ficam disponiveis para outra. O reconnect inicializa e descobre as ferramentas do
novo cliente antes de substituir o anterior, portanto uma falha de handshake nao derruba uma
conexao funcional.

Quando a conversa nao possui workspace, o backend nao injeta um bloco de ausencia no prompt. Isso
evita que modelos locais pequenos desviem uma conversa comum para um aviso irrelevante; ferramentas
de arquivos continuam bloqueadas pela autorizacao validada no backend.

## Recuperacao de erros de ferramenta

O agente aceita ate tres falhas consecutivas da mesma ferramenta. Entre a primeira e a segunda
falha de caminho, ele recebe uma correcao no contexto: o caminho informado nao existe com o tipo
esperado, a ferramenta continua disponivel e o proximo caminho deve vir somente de um resultado
anterior de `directory_tree`, dentro das raizes autorizadas. Assim, um erro como `Path is not a
directory` nao e comunicado ao modelo como indisponibilidade do servidor e ele pode se recuperar
pela estrutura real do projeto.

Na terceira falha consecutiva, a execucao termina de forma segura. O evento e a resposta final
dizem se foram caminhos inexistentes ou uma falha tecnica da ferramenta, preservando o motivo para
o chat e evitando continuar por caminhos inventados.

## Relatorio terminal do Cowork

Uma tarefa do Cowork fica como `RUNNING` quando entra na fila; o agendamento nao a marca como
sucesso antes de o worker concluir a run. Ao terminar, o Cowork grava o status real e atualiza o
historico com o `runId`, a etapa em que falhou, o motivo retornado e a trilha das ferramentas. Isso
tambem vale para tarefas de automacao do macOS sem projeto associado: sem workspace, as ferramentas
de arquivo seguem bloqueadas pelo sandbox, mas abrir uma aba ou aplicativo pode ser executado.

Uma tarefa criada como **Data Específica** é pontual (`runOnce`). Depois de receber sucesso ou
falha, ela sai da agenda ativa e fica arquivada, sem perder o último retorno nem o histórico.
Se o Avento estiver desligado e o horário pontual vencer por mais de um minuto, ela também é
arquivada sem ser executada, com o motivo registrado no histórico. Agendamentos diários, por
intervalo e Cron manual continuam recorrentes. Para automações diretas do navegador, o Cowork marca
o pedido original no envelope; assim “abra uma aba no YouTube no Chrome” chama a ferramenta de aba
com `Google Chrome` e `https://www.youtube.com`, em vez de confundir a instrução interna com um
comando de Terminal.

### Histórico global de execuções

A aba **Histórico** do Cowork mostra as 100 execuções mais recentes do usuário, não apenas as
atividades ainda visíveis na agenda. Cada entrada reúne a tarefa, data e hora, status, pedido
enviado e o retorno ou erro bruto da execução. O backend atende essa tela por
`GET /api/scheduled-tasks/history?limit=100`, aplica a propriedade do usuário na consulta e lê o
PostgreSQL, que é a fonte durável dos registros `scheduled_task_runs`. Assim, quando uma tarefa
pontual termina e é arquivada, sua evidência continua disponível para auditoria.

## Indicador visual de voz

Durante a reproducao de TTS, o chat mostra o mascote flutuante do Avento. Ele combina movimento
suave, brilho, ondas de audio e boca animada para deixar claro que a fala esta em andamento. O
indicador desaparece quando a fila de audio termina e respeita a preferencia do sistema por reduzir
movimentos.

## Skills embutidas

O `SkillRegistry` carrega procedimentos em `agent/skills/*.md` e skills pessoais em
`data/skills/*.md`. Use `/skills` para listar, `/nome argumento` para ativar explicitamente ou uma
frase declarada em `Gatilhos:` para ativação automática. Gatilhos são normalizados sem acentos; se
mais de uma skill combinar, vence o gatilho com mais palavras.

O pacote padrão inclui análise, correção, diagnóstico e execução de projetos; scaffolds Vite e
NestJS; leitura de documentos; banco via DBHub; gerenciamento MCP; pesquisa web; geração de imagem
e vídeo; manutenção Java/Spring; memória persistente; Git; Docker; e automação do macOS. Também há
procedimentos especializados para Spring Security, Flyway, lifecycle MCP, jobs Redis/SSE, frontend,
prototipação HTML interativa, pipeline de mídia, voz, RAG, dependências, finalização de mudanças e
readiness de release. `prototype-interface` produz uma prévia local persistida na mensagem e só
orienta a edição do workspace depois da aprovação explícita do usuário.
`generate-image`, `generate-video`, `media-pipeline-maintenance` e `mac-workflow` não têm gatilho
automático: continuam disponíveis por slash command sem interceptar os roteadores diretos de mídia
e automação.

As skills embutidas orientam o Avento pelo chat. Para agentes de desenvolvimento atuando neste
próprio repositório, `AGENTS.md` direciona às skills equivalentes em `.agents/skills/`; a skill Java
possui referência detalhada de DTO, Lombok, injeção por construtor, decomposição de services,
persistência, testes e remoção segura de legado.

O registro ao vivo usa `RUNNING`, `AWAITING_APPROVAL`, `COMPLETED`, `FAILED` e `CANCELLED`. O job
duravel acrescenta `QUEUED`, `WAITING_APPROVAL` e `CANCEL_REQUESTED`. PostgreSQL guarda o estado
definitivo, enquanto o registro em memoria mantem a visao detalhada da execucao ativa.

## Configuracao

```sh
AVENTO_MCP_SDK_ENABLED=true
AVENTO_MCP_SDK_REQUEST_TIMEOUT=60s
```

Ferramentas externas que colidem com nomes locais recebem namespace interno no formato
`servidor__ferramenta`. Para uma equivalência estrita anunciada pelo `docker-gateway` no
`tools/list`, esse namespace fica escondido: o modelo recebe uma única ferramenta canônica e a
execução é roteada ao Docker. Falhas dessa rota continuam visíveis no resultado da ferramenta; o
orquestrador não tenta a nativa como fallback.

O Avento nao possui mais transporte JSON-RPC manual. O SDK negocia o protocolo com o servidor; o
alvo de conformidade documentado pelo backend e `2025-11-25`. Pacotes npm do catalogo sao
versionados em `application.yml` e podem ser substituidos por variaveis `AVENTO_MCP_*_PACKAGE`.

## Inspecao

- `GET /api/ai/runs`: lista ate 50 execucoes recentes do usuario autenticado.
- `GET /api/ai/runs/{runId}`: retorna uma execucao pertencente ao usuario ou `404`.
- `GET /api/ai/runs/{runId}/result`: recupera a resposta durável se a conexão SSE caiu.
- `POST /api/ai/runs`: cria um job assincrono e retorna `202` com o `runId`.
- `GET /api/ai/runs/{runId}/events`: acompanha o job por SSE autenticado.
- `POST /api/ai/runs/{runId}/cancel`: solicita cancelamento e interrompe o worker ativo.
- `POST /api/mcp/connect`: conecta os MCPs habilitados e inclui o status do SDK na resposta.
- `GET /api/mcp/tools`: lista ferramentas locais e externas descobertas.
- `GET /api/mcp/processes/{processId}/logs`: acompanha somente processos do proprietario.
- `POST /api/mcp/processes/{processId}/stop`: interrompe somente processos do proprietario.

A timeline persiste apenas eventos do run com `userId` e `chatId`. Tokens, cookies, senhas, DSNs,
base64 e conteudo extenso sao removidos ou resumidos antes da gravacao.

O frontend usa `POST /api/ai/runs` e depois `GET /api/ai/runs/{runId}/events`. O antigo
`POST /api/ai/stream` permanece temporariamente como compatibilidade interna e nao e mais usado
pelo React. Aprovar ou rejeitar uma acao continua a mesma execucao, preservando o `runId`.

Detalhes operacionais e troubleshooting ficam em
[Execucao assincrona com Redis](REDIS_EXECUTION.md).

## Validacao

```sh
mvn -f back/avento/pom.xml test
cd front && npm run validate
```

`McpClientManagerTest` inicia um servidor MCP stdio falso em Node.js e valida inicializacao,
descoberta, chamada, colisao de nomes, isolamento por escopo e reconnect atomico usando o protocolo
real.
