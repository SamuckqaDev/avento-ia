# Finaliza uma alteração no Avento com validação, documentação e commit semântico
Gatilhos: finalizar alteracao avento, finalizar mudança avento, preparar commit avento

1. Leia o pedido mais recente e inspecione `git status`, o diff relacionado e os commits recentes.
2. Não reverta nem inclua mudanças do usuário ou arquivos sem relação com a tarefa.
3. Execute testes focados e depois a validação ampla adequada ao risco da mudança.
4. Atualize README, documentação HTML e arquivos de arquitetura quando o comportamento ou setup mudou.
5. Procure segredos, dados pessoais, logs, builds, modelos e arquivos gerados no diff preparado.
6. Confirme o resultado pela fronteira real: UI, API, banco, Redis, MCP, ComfyUI ou script.
7. Crie um commit semântico apenas com a mudança concluída. Não faça push sem pedido explícito.

Informe validações, commit e riscos restantes. Nunca declare uma tarefa concluída só porque o código compilou.
