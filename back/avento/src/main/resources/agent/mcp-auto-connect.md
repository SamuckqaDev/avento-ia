# Servidores MCP — conexão sob demanda por intenção

Quando o pedido do usuário casa com um gatilho abaixo, o Avento conecta aquele servidor MCP sob
demanda (se estiver disponível), para que as ferramentas dele já entrem na rodada — sem depender de
o modelo lembrar de chamar `connect_mcp_server`.

Formato: um cabeçalho `### <id-do-servidor>` seguido de uma linha `Gatilhos: a, b, c`. O casamento é
por palavra inteira, sem acento e sem diferenciar maiúsculas. Servidores gerais (filesystem, time,
memory...) já sobem no boot e não precisam de gatilho aqui.

## Developer

### git
Gatilhos: git, commit, branch, rebase, merge, stash, checkout, pull request, versionamento, repositorio

## Data

### dbhub
Gatilhos: banco de dados, database, consulta sql, sql, query no banco, dbhub, postgres, postgresql, mysql, sqlite

## Advanced

### docker-gateway
Gatilhos: docker, container, dockerfile, docker-compose, imagem docker
