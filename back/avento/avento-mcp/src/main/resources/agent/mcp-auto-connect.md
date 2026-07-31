# Servidores MCP — conexão sob demanda por intenção

Quando o pedido do usuário casa com um gatilho abaixo, o Avento conecta aquele servidor MCP sob
demanda (se estiver disponível), para que as ferramentas dele já entrem na rodada — sem depender de
o modelo lembrar de chamar `connect_mcp_server`.

Formato: um cabeçalho `### <id-do-servidor>` seguido de uma linha `Gatilhos: a, b, c`. O casamento é
por palavra inteira, sem acento e sem diferenciar maiúsculas. Servidores gerais (filesystem, time,
memory...) já sobem no boot e não precisam de gatilho aqui.

## Developer

<!-- git saiu daqui: agora vem do docker-gateway, que conecta no boot. Manter o gatilho subiria uma
     segunda copia via npx e duas ferramentas com o mesmo nome disputando a rodada. -->

## Data

### dbhub
Gatilhos: banco de dados, database, consulta sql, sql, query no banco, dbhub, postgres, postgresql, mysql, sqlite

## Web

<!-- fetch idem: vem do gateway desde o boot, nao precisa de gatilho. -->

## Advanced

<!-- O docker-gateway NAO tem gatilho de propósito. Ele nao e uma ferramenta sobre Docker: e o
     transporte que serve OUTROS servidores MCP, em containers isolados. Acionar por "dockerfile"
     subia o gateway a toa, e precisar de um servidor agregado nele nao disparava nada. Conecte-o
     explicitamente com connect_mcp_server. -->
