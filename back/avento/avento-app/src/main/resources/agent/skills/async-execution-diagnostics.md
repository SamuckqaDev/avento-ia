# Diagnostica execuções presas em pensando, fila ou streaming
Gatilhos: ia pensa nao responde, ia pensa não responde, chat carregando para sempre, execucao assincrona travada, execução assíncrona travada

Siga um único `runId` do início ao fim:

1. Confirme o POST de submissão e o SSE `/api/ai/runs/{runId}/events` com cookie.
2. Consulte `agent_run_jobs` e `execution_outbox_events`: status, tentativas, horários e erro.
3. Inspecione `avento:jobs:agent`, grupo consumidor, pending, lag e `avento:dead-letter`.
4. Correlacione `runId`, `chatId`, `jobId` e `userId` nos logs sem imprimir prompt ou token.
5. Confira eventos em ordem: início, thinking, delta visível, ferramenta, aprovação e terminal.
6. Reproduza primeiro com uma mensagem curta sem ferramenta nem mídia.
7. Corrija idempotência, pending antigo, isolamento do stream, timeout ou estado visual na camada dona.
8. Adicione teste do travamento e smoke test que alcance resposta terminal.

Não apague filas antes de preservar e entender a evidência.
