# Avento Agent Roadmap

Este documento define o plano de execucao para transformar o Avento em um agente local-first completo, preparado para rodar hoje no Mac local e futuramente em uma maquina remota mais robusta.

## Objetivo

O Avento deve operar como um copiloto de projetos e automacao com:

- entendimento de projeto, arquivos, terminal, navegador e apps locais;
- voz natural com STT/TTS local;
- memoria por usuario, perfil, projeto e conversa;
- aprovacoes claras para acoes perigosas;
- execucao observavel, com timeline, logs e rollback quando possivel;
- arquitetura preparada para modelos locais maiores ou provedores remotos.

## Fase 1 - Agent Core

Status: implementada como base inicial.

Entregas:

- `ToolCapabilityRegistry` centralizando ferramentas, categoria, risco e politica de aprovacao.
- `ToolRiskLevel` para separar leitura, baixo risco, medio risco, alto risco e destrutivo.
- `ToolApprovalPolicy` para decidir se uma ferramenta pode rodar automaticamente ou precisa aprovacao.
- Integracao inicial do `AgentService` com o registry para aprovacoes e execucao direta.
- Endpoint `GET /api/capabilities` para expor capacidades, riscos e politicas para o frontend/futuro painel.
- Testes garantindo que ferramentas perigosas continuam exigindo aprovacao.

Proximos passos:

- Migrar descricoes do `McpController` para definicoes reutilizaveis no registry.
- Registrar ferramentas externas MCP no mesmo modelo de capacidade.

## Fase 2 - Intent Router

Status: iniciada.

Entregas:

- Classificador de intencao antes do modelo escolher ferramenta.
- Rotas de intencao: conversa casual, projeto/codigo, filesystem, terminal, navegador, app macOS, web search, git, banco, memoria.
- Regras negativas explicitas, como: pedido com "aba/guia/pesquisa" nunca vira `close_app`.
- Testes de roteamento para casos de voz ruidosa.

Entregue nesta fase:

- `IntentRouter` classificando intencoes principais e decidindo exposicao de ferramentas por categoria.
- `AgentService` usando o router em vez de manter toda a matriz de exposicao de ferramentas inline.

## Fase 3 - Permission Engine

Status: iniciada.

Entregas:

- Politicas por usuario, perfil, projeto e ambiente.
- Niveis: leitura, acao segura, escrita, destrutivo, remoto/admin.
- Preferencias persistidas: "sempre aprovar este comando neste projeto", com escopo e expiracao.
- UI para revisar e revogar permissoes.

Entregue nesta fase:

- Aprovacao/rejeicao por voz para a ultima acao pendente.
- Comandos de voz para "so agora", "por 1 hora", "por 24 horas" e "sempre neste projeto".
- `AgentPermissionRule` persistindo permissoes por projeto, ferramenta e recurso/comando.
- Autoaprovacao de acoes futuras quando existe permissao ativa equivalente.

## Fase 4 - MCP Manager

Entregas:

- Cache de schemas MCP conectados.
- Healthcheck por servidor MCP.
- Priorizacao de wrappers internos seguros antes de ferramentas externas amplas.
- Registro de ferramentas de Desktop Commander, macOS Automator, Playwright, Puppeteer e GitHub no `ToolCapabilityRegistry`.
- Diagnostico de ferramentas indisponiveis com sugestao de instalacao/configuracao.

## Fase 5 - Project Intelligence

Entregas:

- Perfil persistente por projeto.
- Sumario arquitetural incremental.
- Decisoes tecnicas, comandos comuns, riscos conhecidos e padroes do projeto.
- RAG por projeto com reindexacao incremental.
- Modo "revisao" e modo "execucao" separados.

## Fase 6 - Execution Timeline

Status: iniciada.

Entregas:

- Timeline persistente por run do agente.
- Eventos de plano, ferramenta, aprovacao, resultado, erro e rollback.
- Logs de processos longos com anexos.
- Reexecucao controlada de passos seguros.

Entregue nesta fase:

- Entidade `AgentTimelineEvent` para persistir eventos de execucao do agente.
- `AgentTimelineService` gravando eventos de aprovacao, inicio, conclusao e falha de ferramentas.
- Endpoint `GET /api/agent/timeline` com filtro opcional `runId` para historico recente ou de uma execucao.

## Fase 7 - Remote-Ready Runtime

Entregas:

- Separacao entre API, worker executor e provider de modelo.
- Fila de tarefas longas.
- Conectores para Ollama local, Ollama remoto, vLLM ou outro provider configurado.
- Politicas mais fortes para acesso remoto: HTTPS, cookie `Secure`, CORS restrito, auditoria e rate limit.
- Inventario de capacidades por maquina executora.

## Principio De Projeto

Ferramentas poderosas devem ser pequenas, nomeadas e com trilho. MCPs amplos entram como camada de expansao, mas a interface do Avento deve preferir capacidades internas seguras, testadas e auditaveis.
