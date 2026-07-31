# Diagnostica ferramenta registrada que o agente diz nao encontrar
Gatilhos: tool not found, ferramenta nao encontrada, ferramenta não encontrada, ferramenta existe mas nao executa, ferramenta existe mas não executa, agente nao acha a ferramenta, agente não acha a ferramenta

A ferramenta aparece no codigo e na documentacao, mas o despachante responde que nao existe:

1. Leia a linha `Agent round toolset:` no log e confirme se o nome esta em `tools(N)=[...]`. Ausente ali, o problema e exposicao; presente e falhando, e execucao.
2. Se faltou na exposicao, confira o teto `avento.agent.max-tools-per-request` e a intencao da rodada: a triagem pode ter cortado a ferramenta antes do modelo ver.
3. Se o erro e `Tool not found or server disconnected`, o nome nao esta em `LocalToolNames` NEM veio de servidor MCP conectado.
4. Procure a classe duplicada: `find . -name LocalToolNames.java -not -path '*/target/*'`. Duas copias no mesmo pacote compilam sem aviso e quem vence e a ordem do classpath.
5. Rode `NoDuplicateClassNamesTest`; ele quebra quando duas copias de qualquer classe divergem entre modulos.
6. Sendo ferramenta MCP, confirme o servidor em `list_mcp_servers` e o nome exposto: colisao e renomeada com prefixo `servidor__ferramenta`, nao rejeitada.
7. Cubra com teste que execute a ferramenta de verdade, nao apenas que ela conste do registro.

Constar do registro nao prova que o despachante a alcanca.
