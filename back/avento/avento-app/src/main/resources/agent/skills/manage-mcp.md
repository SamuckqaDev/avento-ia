# Descobre, conecta e diagnostica servidores MCP locais do Avento
Gatilhos: listar mcp, listar mcps, conectar mcp, conecte mcp, status mcp, diagnosticar mcp

Procedimento:
1. Use list_mcp_servers com os workspaces atuais.
2. Identifique o ID exato, disponibilidade, dependências e configuração ausente.
3. Quando solicitado, use connect_mcp_server com o serverId exato e projectPaths quando necessários.
4. Depois da conexão, confira as ferramentas realmente descobertas antes de usá-las.
5. Para desconectar, use disconnect_mcp_server com o ID confirmado.

Não mande o usuário instalar manualmente algo que o catálogo consiga iniciar. Se o servidor não estiver disponível, mostre o requisito real retornado pelo catálogo.
