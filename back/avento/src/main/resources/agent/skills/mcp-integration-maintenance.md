# Adiciona e mantém integrações MCP com escopo, permissão e verificação reais
Gatilhos: integrar servidor mcp, corrigir integracao mcp, corrigir integração mcp, revisar ferramentas mcp

1. Identifique servidor, transporte, versão do protocolo, comando, ambiente e dependências locais.
2. Registre a integração no catálogo MCP; não adicione roteamento específico ao prompt central.
3. Inicialize e valide o novo cliente antes de desconectar uma conexão funcional.
4. Preserve schemas das ferramentas e adapte contratos na fronteira MCP.
5. Restrinja filesystem, Git, banco e desktop aos workspaces autorizados e valide o escopo no backend.
6. Passe ações de risco pelo Permission Engine e mantenha descoberta somente leitura.
7. Use timeouts e logs úteis sem segredos. Teste `list_tools`, chamada real, reconexão e erro.

Nunca aceite texto do modelo como prova de que uma ferramenta foi executada.
