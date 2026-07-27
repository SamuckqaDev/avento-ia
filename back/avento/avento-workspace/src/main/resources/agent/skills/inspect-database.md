# Inspeciona o banco descoberto no projeto ativo e executa consultas controladas
Gatilhos: acessar banco projeto, consultar banco projeto, inspecionar banco, listar tabelas banco, ver tabelas banco, executar query banco

Procedimento:
1. Use list_mcp_servers e confirme se dbhub está conectado ou disponível para o workspace atual.
2. Se necessário, use connect_mcp_server com serverId dbhub e os projectPaths autorizados.
3. Na rodada seguinte, use as ferramentas descobertas pelo DBHub, como search_objects para esquema e execute_sql para consultas.
4. Comece por metadados e consultas SELECT limitadas. Mostre origem, SQL executado e quantidade de linhas.
5. Só faça escrita quando o usuário pedir explicitamente e a ferramenta permitir; deixe o Permission Engine solicitar aprovação.

Nunca invente tabelas, colunas, resultados ou credenciais. Não exponha DSN nem senha na resposta.
