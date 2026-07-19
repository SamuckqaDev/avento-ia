# Catalogo local de MCPs

O Avento usa o SDK Java oficial do MCP como cliente e mantem um catalogo de servidores locais. O objetivo e carregar automaticamente apenas o nucleo barato e conectar ferramentas especializadas sob demanda, evitando excesso de schemas no contexto do modelo.

## Leitor universal

A ferramenta local `read_document` valida o caminho contra os workspaces autorizados antes de abrir o arquivo. Textos sao lidos diretamente; PDF, Word, Excel, PowerPoint, EPUB, ZIP, imagens, audio e outros formatos suportados sao convertidos para Markdown pelo Microsoft MarkItDown.

Instalacao manual:

```bash
./scripts/setup-local-mcps.sh
```

O `scripts/dev-up.sh` executa essa instalacao automaticamente na primeira subida. O ambiente Python fica em `.avento-tools/mcp`, fora do Git. Para desativar apenas essa preparacao automatica:

```bash
AVENTO_LOCAL_MCP_AUTO_SETUP=0 ./scripts/dev-up.sh
```

Limites configuraveis:

```bash
AVENTO_DOCUMENT_READ_TIMEOUT=120s
AVENTO_DOCUMENT_MAX_OUTPUT_CHARS=200000
AVENTO_DOCUMENT_MAX_FILE_SIZE=100MB
```

O leitor nao usa API paga. Recursos opcionais de nuvem do MarkItDown nao recebem endpoint nem credencial e, portanto, nao sao usados.

## Servidores

| Perfil | Servidores | Comportamento |
| --- | --- | --- |
| `core` | Filesystem, MarkItDown, Memory, Sequential Thinking, Time | O conjunto configurado conecta no escopo do chat |
| `automation` | Desktop Commander, macOS Automator, Apple MCP | Conectados automaticamente quando habilitados e disponiveis |
| `web` | Playwright, Chrome DevTools, Puppeteer, Fetch, SearXNG | SearXNG exige uma instancia configurada |
| `developer` | Git oficial | Usa `uvx mcp-server-git` e limita ao workspace selecionado |
| `data` | DBHub | Exige DSN e suporta PostgreSQL, MySQL, MariaDB, SQL Server e SQLite |
| `advanced` | Docker MCP Gateway | Exige um perfil do Docker MCP Toolkit |

## Banco do projeto ativo

Quando um chat possui um workspace autorizado, o Avento procura a configuracao de banco desse projeto e conecta o DBHub automaticamente. Cada chat mantem clientes e rotas MCP separados; trocar de conversa nao reutiliza o banco, o filesystem ou as ferramentas da anterior.

A ordem de descoberta e:

1. `dbhub.toml` ou `.avento/dbhub.toml` no projeto;
2. PostgreSQL, MySQL, MariaDB ou SQL Server com porta publicada em `compose.yml`, `compose.yaml`, `docker-compose.yml` ou `docker-compose.yaml`;
3. `AVENTO_DBHUB_DSN`, `DATABASE_URL`, `DB_URL` ou `DATABASE_DSN` nos arquivos `.env` locais;
4. `spring.datasource.*` em `application.properties`, `application.yml` ou `application.yaml`;
5. variaveis `POSTGRES_*`, `MYSQL_*`, `MARIADB_*`, `MSSQL_*` ou `DB_*`;
6. arquivos SQLite comuns em `data`, `db`, `prisma`, `storage` ou na raiz.

Para bancos em Docker, publique a porta no host. Por exemplo, `5544:5432` faz o DBHub local usar `127.0.0.1:5544`; o nome interno do servico Compose nao e enviado ao processo local.

O Avento gera um TOML em `~/.avento/dbhub` com permissao do usuario e apenas uma referencia a variavel de ambiente. A DSN, incluindo a senha, nao e retornada pela API nem escrita nesse arquivo. Um `dbhub.toml` mantido pelo proprio projeto sempre tem prioridade e pode definir multiplas fontes conforme a documentacao oficial do DBHub.

O conjunto automatico adicional pode ser alterado sem recompilar:

```bash
AVENTO_MCP_AUTO_CONNECT=filesystem,memory,sequential-thinking,time,desktop-commander,macos-automator,apple,playwright,chrome-devtools,puppeteer,git
```

## Interface web

O botao de plugue no header abre o gerenciador de ferramentas locais. A tela consulta o catalogo com Axios, preserva os workspaces do chat atual e permite buscar, filtrar, conectar, desconectar e atualizar servidores. Estados indisponiveis mostram a configuracao que falta; conexoes bem-sucedidas usam o snackbar temporario da aplicacao.

Em telas menores que 640px, o gerenciador ocupa a viewport inteira e mantem a lista rolavel sem deslocar o chat.

## API do catalogo

Listar disponibilidade e estado:

```http
GET /api/mcp/catalog?workspace=/caminho/do/projeto&chatId=42
```

Conectar servidores selecionados:

```http
POST /api/mcp/catalog/connect
Content-Type: application/json

{
  "serverIds": ["fetch", "git"],
  "projectPaths": ["/caminho/do/projeto"],
  "chatId": 42
}
```

Desconectar:

```http
POST /api/mcp/catalog/disconnect
Content-Type: application/json

{
  "serverIds": ["fetch"],
  "projectPaths": [],
  "chatId": 42
}
```

## Configuracao opcional

```bash
# Busca local/gratuita: aponte para sua propria instancia SearXNG.
AVENTO_MCP_SEARXNG_URL=http://127.0.0.1:8080

# Fallback global usado apenas quando o chat nao possui workspace.
AVENTO_MCP_DBHUB_DSN=postgres://avento:senha@127.0.0.1:5432/avento?sslmode=disable

# Perfil criado no Docker MCP Toolkit.
AVENTO_MCP_DOCKER_GATEWAY_PROFILE=avento-local
```

`Fetch` acessa URLs fornecidas pelo modelo e pode alcancar enderecos internos. Ele nao conecta automaticamente. DBHub conecta automaticamente quando o workspace ativo fornece uma configuracao reconhecida; sem workspace, usa somente o fallback global. Docker Gateway fica desligado enquanto sua configuracao estiver vazia.

O catalogo usa exclusivamente o SDK Java oficial. Os pacotes npm possuem versoes fixas em
`application.yml`, sem `@latest`; isso deixa a instalacao reproduzivel e permite atualizar cada
servidor de forma deliberada.

## Validacao

```bash
./scripts/setup-local-mcps.sh
./scripts/check-local-deps.sh
mvn -f back/avento/pom.xml test
```
