# Diagnostica Docker MCP Gateway que nao sobe
Gatilhos: gateway mcp nao sobe, gateway mcp não sobe, docker desktop is not running, mcp gateway indisponivel, mcp gateway indisponível, gateway nao conecta, gateway não conecta

O gateway e plugin do Docker Desktop, nao do daemon:

1. Confirme o runtime: `docker context show` e `docker info`. Um daemon respondendo nao basta.
2. Verifique o socket do Desktop: `ls -l ~/.docker/run/docker.sock`. Ausente, o plugin recusa mesmo com Colima, OrbStack ou Rancher rodando containers normalmente.
3. Cheque `DOCKER_HOST` no ambiente. A variavel tem precedencia sobre `docker context use`, entao trocar de contexto nao adianta enquanto ela apontar para um runtime desligado.
4. Teste o comando isolado: `docker mcp gateway run --dry-run`. Ele lista servidores e ferramentas sem servir nada.
5. Confirme quais servidores estao habilitados: `docker mcp server ls`. Nenhum habilitado significa gateway conectado sem ferramentas, o que e inofensivo e nao e falha.
6. Rode `./scripts/check-local-deps.sh`: a secao de runtime nomeia o contexto ativo, o Desktop e os servidores do gateway.
7. Para usar o gateway: `AVENTO_DOCKER_CONTEXT=desktop-linux ./scripts/dev-up.sh`.

Volumes nao sao compartilhados entre contextos: apontar para outro contexto sobe um banco vazio.
