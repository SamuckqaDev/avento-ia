# Inventário de decisões em `docs/`

Data da conferência: 10/08/2026. A spec menciona 23 documentos, mas há 24 arquivos Markdown no
nível `docs/`: `FEATURES.md` e `FEATURES.pt-BR.md` são arquivos físicos distintos e ambos foram
classificados. `docs/agent-tasks/` e `docs/aprendizados/` não fazem parte deste inventário.

| Documento | Destino | Motivo | Citado por |
| --- | --- | --- | --- |
| `ACCOUNT_CONFIG_PLAN.md` | `docs/historico/` | Plano de implementação do hub de conta e métricas, concluído pelo commit que o criou; não é autoridade atual. | — |
| `AGENT_ROADMAP.md` | `docs/historico/` | Roadmap inicial substituído pela documentação do estado atual e pelos planos posteriores; não é autoridade atual. | `README.md`, `README.pt-BR.md` |
| `ARCHITECTURE.md` | fica | Arquitetura atual, atualizada em agosto. | `CONTRIBUTING.md`, `README.md`, `README.pt-BR.md`, `AVENTO_IDENTITY.md`, `IMPLEMENTATION_PLAN.md`, `PLANO_AGENTE_CONFIGURAVEL.md`, esta spec |
| `AVENTO_IDENTITY.md` | fica | Referência pública de identidade e capacidades atuais. | `README.md`, `README.pt-BR.md`, `docs-en.html`, `docs.html` |
| `BRAND.md` | fica | Referência da identidade visual, não um plano executado. | `README.md`, `README.pt-BR.md`, `AVENTO_IDENTITY.md` |
| `FEATURES.md` | fica | Inventário vivo em inglês, atualizado em agosto. | `CONTRIBUTING.md`, `README.md`, `FEATURES.pt-BR.md`, esta spec |
| `FEATURES.pt-BR.md` | fica | Inventário vivo em português, atualizado em agosto. | `CONTRIBUTING.md`, `README.pt-BR.md`, `FEATURES.md` |
| `HANDOFF.md` | fica | Registro vivo de observações medidas e do trabalho em curso, atualizado em agosto. | `PLANO_AGENTE_CONFIGURAVEL.md`, tarefas de caracterização e deduplicação, esta spec |
| `IMPLEMENTATION_PLAN.md` | `docs/historico/` | Plano didático inicial; o estado atual já delega a `ARCHITECTURE.md` e os itens foram substituídos por documentação específica. | `README.md`, `README.pt-BR.md`, `ARCHITECTURE.md`, esta spec |
| `INTERFACE_PROTOTYPING.md` | fica | Explica o fluxo e os limites atuais de prototipação local, não uma implementação proposta. | `README.md`, `README.pt-BR.md`, `ARCHITECTURE.md`, esta spec |
| `LOCAL_AGENT_ROADMAP.md` | `docs/historico/` | Roadmap de evolução do agente local, substituído pelos planos e pelo estado atual posteriores; não é citado como autoridade. | — |
| `LOCAL_MCP_CATALOG.md` | fica | Catálogo operacional de MCPs, atualizado em 31/07. | `README.md`, `README.pt-BR.md` |
| `ORCHESTRATION.md` | fica | Descreve a orquestração MCP atual e suas configurações, não uma proposta futura. | `README.md`, `README.pt-BR.md` |
| `PLANO_AGENTE_CONFIGURAVEL.md` | fica | Plano vivo, atualizado em agosto, com fases em curso e decisões que ainda orientam o trabalho. | `HANDOFF.md`, tarefa de caracterização, esta spec |
| `REDIS_EXECUTION.md` | fica | Guia do fluxo assíncrono implementado; declara e descreve o código atual. | `README.md`, `README.pt-BR.md`, `docs.html`, `ARCHITECTURE.md`, `IMPLEMENTATION_PLAN.md`, `ORCHESTRATION.md`, `SETUP.md`, esta spec |
| `SETUP.md` | fica | Guia operacional atual, atualizado em julho/agosto. | `CONTRIBUTING.md`, `README.md`, `README.pt-BR.md`, `AVENTO_IDENTITY.md`, esta spec |
| `VISUAL_OUTPUT_PLAN.md` | `docs/historico/` | Plano prescritivo de saída visual já executado/substituído pela documentação do comportamento atual; não é citado como autoridade. | — |
| `agent-corrections-plan.md` | emendado | É a autoridade citada pelo código para a regra de não fazer fallback; apenas a parte da interseção estrita foi revogada. | `AgentService.java`, `AgentProfileToolPolicyTest.java`, `PLANO_AGENTE_CONFIGURAVEL.md`, planos autônomo/implementação/revisão, esta spec |
| `anti-loop-plan.md` | `docs/historico/` | Plano da guarda anti-repetição, executado pelo trabalho posterior; não é citado como autoridade. | — |
| `autonomous-agent-plan.md` | `docs/historico/` | Especificação-base de uma implementação já concluída e substituída pelo estado atual e pelo plano configurável. | `agent-corrections-plan.md`, planos de implementação e revisão, esta spec |
| `codex-agent-implementation-plan.md` | `docs/historico/` | Plano cirúrgico de implementação especializada, concluído/substituído; não é citado pelo código. | `agent-corrections-plan.md`, planos autônomo e de revisão, esta spec |
| `codex-review-plan.md` | `docs/historico/` | Plano de auditoria da implementação anterior, concluído/substituído; não é autoridade do código. | `PLANO_AGENTE_CONFIGURAVEL.md`, `agent-corrections-plan.md`, planos autônomo e de implementação, esta spec |
| `modo-agente.md` | fica | Referência compacta do fluxo efetivamente construído, incluindo o único item ainda pendente. | — |

## Pares contraditórios encontrados

1. `agent-corrections-plan.md` estabelece que uma lista não vazia de `allowedTools` é uma
   interseção estrita com o conjunto elegível; a Fase 1 de
   `PLANO_AGENTE_CONFIGURAVEL.md` revogou essa parte e passou a fazer o perfil definir o universo
   de ferramentas antes da seleção. Esta é uma contradição explícita e conhecida. A regra restante
   — interseção vazia permanece vazia, sem fallback para todas as ferramentas — continua vigente e
   é citada por `AgentService.java`; por isso o primeiro documento será emendado, não arquivado.

Nenhum outro par de documentos classificáveis decide o mesmo ponto em direções opostas. Os demais
documentos históricos são planos concluídos ou substituídos, não autoridades concorrentes.
