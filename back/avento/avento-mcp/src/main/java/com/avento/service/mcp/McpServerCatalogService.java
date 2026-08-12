package com.avento.service.mcp;

import com.avento.dto.*;
import com.avento.dto.ConnectionResult;
import com.avento.dto.DatabaseConfiguration;
import com.avento.service.support.ProjectPaths;
import com.avento.service.tools.LocalToolNames;
import com.avento.service.tools.ToolExecutionContext;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class McpServerCatalogService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(McpServerCatalogService.class);

    private static final List<String> DEFAULT_AUTO_CONNECT = List.of(
            "filesystem",
            "markitdown",
            "memory",
            "sequential-thinking",
            "time",
            "macos-automator",
            "apple",
            "playwright",
            "chrome-devtools",
            "puppeteer",
            "git");

    private final McpClientManager clientManager;
    private final Environment environment;
    private final ProjectDatabaseDiscoveryService databaseDiscoveryService;
    private final ToolExecutionContext executionContext;

    /** Opcional: os construtores de teste não o fornecem, e o catálogo funciona sem ele. */
    private final McpToolSchemaCache toolSchemaCache;

    McpServerCatalogService(
            McpClientManager clientManager,
            Environment environment,
            ProjectDatabaseDiscoveryService databaseDiscoveryService) {
        this(clientManager, environment, databaseDiscoveryService, new ToolExecutionContext(), (McpToolSchemaCache)
                null);
    }

    McpServerCatalogService(
            McpClientManager clientManager,
            Environment environment,
            ProjectDatabaseDiscoveryService databaseDiscoveryService,
            ToolExecutionContext executionContext) {
        this(clientManager, environment, databaseDiscoveryService, executionContext, (McpToolSchemaCache) null);
    }

    @Autowired
    public McpServerCatalogService(
            McpClientManager clientManager,
            Environment environment,
            ProjectDatabaseDiscoveryService databaseDiscoveryService,
            ToolExecutionContext executionContext,
            org.springframework.beans.factory.ObjectProvider<McpToolSchemaCache> toolSchemaCacheProvider) {
        this(
                clientManager,
                environment,
                databaseDiscoveryService,
                executionContext,
                toolSchemaCacheProvider == null ? null : toolSchemaCacheProvider.getIfAvailable());
    }

    private McpServerCatalogService(
            McpClientManager clientManager,
            Environment environment,
            ProjectDatabaseDiscoveryService databaseDiscoveryService,
            ToolExecutionContext executionContext,
            McpToolSchemaCache toolSchemaCache) {
        this.clientManager = clientManager;
        this.environment = environment;
        this.databaseDiscoveryService = databaseDiscoveryService;
        this.executionContext = executionContext;
        this.toolSchemaCache = toolSchemaCache;
    }

    /**
     * Ferramentas conhecidas de um servidor SEM conectar nele.
     *
     * <p>É o que permite a tela de criação de agente listar ferramentas e o {@code allowed_tools} de
     * um perfil ser resolvido sem subir container nenhum. Só responde para servidor em container e
     * já visto uma vez; para o resto devolve vazio, e o chamador conecta para descobrir.
     */
    public List<ToolDefinition> knownTools(String serverId) {
        String image = CONTAINER_IMAGES.get(serverId);
        if (image == null || toolSchemaCache == null) {
            return List.of();
        }
        return toolSchemaCache.tools(image);
    }

    public List<ServerDescriptor> catalog(List<String> workspaceRoots) {
        return definitions().stream()
                .map(definition -> describe(definition, workspaceRoots))
                .toList();
    }

    public List<ConnectionResult> connectAuto(List<String> workspaceRoots) {
        List<String> ids = new ArrayList<>(autoConnectServerIds());
        if (!ids.contains("dbhub")
                && databaseDiscoveryService.discover(workspaceRoots).isPresent()) {
            ids.add("dbhub");
        }
        return connect(ids, workspaceRoots);
    }

    List<String> autoConnectServerIds() {
        String configured = environment.getProperty("avento.mcp.catalog.auto-connect", String.class, "");
        return configured.isBlank()
                ? DEFAULT_AUTO_CONNECT
                : Arrays.stream(configured.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .toList();
    }

    public List<ConnectionResult> connect(List<String> serverIds, List<String> workspaceRoots) {
        List<ConnectionResult> results = new ArrayList<>();
        Set<String> uniqueIds = new LinkedHashSet<>(serverIds == null ? List.of() : serverIds);
        Map<String, ServerDefinition> byId = new LinkedHashMap<>();
        definitions().forEach(definition -> byId.put(definition.id(), definition));
        String scope = executionContext.current().scopeKey();

        for (String id : uniqueIds) {
            ServerDefinition definition = byId.get(id);
            if (definition == null) {
                results.add(ConnectionResult.failedFor(id, "Servidor MCP desconhecido."));
                continue;
            }
            if (clientManager.isConnected(scope, id)) {
                results.add(new ConnectionResult(true, id, List.of(), ""));
                continue;
            }
            ServerLaunch launch = launch(definition, workspaceRoots);
            if (!launch.ready()) {
                results.add(ConnectionResult.failedFor(id, launch.reason()));
                continue;
            }
            ConnectionResult result =
                    clientManager.connect(scope, id, launch.command(), launch.environment(), LocalToolNames.ALL);
            // Conexao bem-sucedida de um servidor em container e a UNICA hora barata de aprender o
            // schema dele: o container ja esta de pe e o tools/list ja foi pago. Gravar aqui e o que
            // permite listar as ferramentas depois sem subir nada.
            if (result.connected() && toolSchemaCache != null) {
                String image = CONTAINER_IMAGES.get(id);
                if (image != null) {
                    toolSchemaCache.record(image, result.tools());
                }
            }
            results.add(result.connected() ? result : ConnectionResult.failedFor(id, result.error()));
        }
        return List.copyOf(results);
    }

    /** Escopo próprio do aquecimento: nunca compartilha cliente com sessão de usuário. */
    private static final String WARMUP_SCOPE = "schema-warmup";

    /**
     * Aprende o {@code tools/list} das imagens que o cache ainda não conhece.
     *
     * <p><b>Por que existe:</b> o cache só aprendia na primeira conexão bem-sucedida de cada
     * servidor. Numa máquina nova isso significa que a tela de criação de agente nasce mostrando
     * apenas as ferramentas locais, e as de container só aparecem depois que alguém, por acaso,
     * usar aquele servidor numa conversa. Quem está montando um agente não deveria depender de sorte.
     *
     * <p><b>Escopo dedicado.</b> Conectar pelo caminho normal usaria o escopo {@code local}, o mesmo
     * de sessão anônima — e o {@code disconnect} do fim do aquecimento derrubaria um servidor que
     * alguém está usando. Aqui o escopo é próprio e é fechado no fim.
     *
     * <p><b>Só o que falta.</b> Imagem já conhecida é pulada, então a partir do segundo boot isto não
     * custa nada. O digest é a chave: imagem atualizada volta a ser desconhecida e reaquece sozinha.
     *
     * @return quantas imagens foram aprendidas nesta passagem
     */
    public int warmSchemaCache() {
        if (toolSchemaCache == null || !containersEnabled()) {
            return 0;
        }
        int learned = 0;
        for (Map.Entry<String, String> entry : CONTAINER_IMAGES.entrySet()) {
            String serverId = entry.getKey();
            String image = entry.getValue();
            if (!toolSchemaCache.tools(image).isEmpty()) {
                continue;
            }
            ServerDefinition definition = definitions().stream()
                    .filter(candidate -> candidate.id().equals(serverId))
                    .findFirst()
                    .orElse(null);
            if (definition == null) {
                continue;
            }
            ServerLaunch launch = launch(definition, List.of());
            if (!launch.ready()) {
                continue;
            }
            try {
                ConnectionResult result = clientManager.connect(
                        WARMUP_SCOPE, serverId, launch.command(), launch.environment(), LocalToolNames.ALL);
                if (result.connected() && !result.tools().isEmpty()) {
                    toolSchemaCache.record(image, result.tools());
                    learned++;
                }
            } catch (Exception exception) {
                // Aquecimento e otimizacao: uma imagem que nao sobe agora sera aprendida na primeira
                // conexao real. Derrubar a subida da aplicacao por causa disso seria trocar um
                // inconveniente de tela por indisponibilidade.
                logger.debug("Aquecimento falhou para {}: {}", serverId, exception.getMessage());
            } finally {
                clientManager.disconnect(WARMUP_SCOPE, serverId);
            }
        }
        return learned;
    }

    public void disconnect(List<String> serverIds) {
        if (serverIds == null) {
            return;
        }
        String scope = executionContext.current().scopeKey();
        serverIds.stream().filter(id -> id != null && !id.isBlank()).forEach(id -> clientManager.disconnect(scope, id));
    }

    private ServerDescriptor describe(ServerDefinition definition, List<String> workspaceRoots) {
        ServerLaunch launch = launch(definition, workspaceRoots);
        return new ServerDescriptor(
                definition.id(),
                definition.name(),
                definition.description(),
                definition.profile(),
                definition.local(),
                definition.requiresNetwork(),
                definition.requiresConfiguration(),
                launch.ready(),
                clientManager.isConnected(executionContext.current().scopeKey(), definition.id()),
                launch.ready() ? "" : launch.reason());
    }

    private List<ServerDefinition> definitions() {
        return List.of(
                new ServerDefinition(
                        "filesystem",
                        "Filesystem",
                        "Le e altera apenas os workspaces informados.",
                        "core",
                        true,
                        false,
                        true),
                new ServerDefinition(
                        "markitdown",
                        "MarkItDown",
                        "Converte PDF, Office, EPUB, ZIP, imagem e audio em Markdown.",
                        "core",
                        true,
                        false,
                        false),
                new ServerDefinition(
                        "memory",
                        "Memory",
                        "Memoria persistente local em grafo para fatos e contexto duradouro.",
                        "core",
                        true,
                        false,
                        false),
                new ServerDefinition(
                        "sequential-thinking",
                        "Sequential Thinking",
                        "Raciocinio estruturado para tarefas longas e revisaveis.",
                        "core",
                        true,
                        false,
                        false),
                new ServerDefinition(
                        "time",
                        "Time",
                        "Consulta horario e converte fusos sem API externa.",
                        "core",
                        true,
                        false,
                        false),
                new ServerDefinition(
                        "desktop-commander",
                        "Desktop Commander",
                        "Arquivos, processos e documentos locais no desktop.",
                        "automation",
                        true,
                        false,
                        false),
                new ServerDefinition(
                        "macos-automator",
                        "macOS Automator",
                        "Automacao nativa de aplicativos e AppleScript no macOS.",
                        "automation",
                        true,
                        false,
                        false),
                new ServerDefinition(
                        "apple",
                        "Apple MCP",
                        "Integracao complementar com aplicativos Apple.",
                        "automation",
                        true,
                        false,
                        false),
                new ServerDefinition(
                        "playwright",
                        "Playwright",
                        "Automacao e teste de navegadores por arvore de acessibilidade.",
                        "web",
                        true,
                        true,
                        false),
                new ServerDefinition(
                        "chrome-devtools",
                        "Chrome DevTools",
                        "Inspecao, rede, console e performance do Chrome.",
                        "web",
                        true,
                        true,
                        false),
                new ServerDefinition(
                        "puppeteer", "Puppeteer", "Automacao alternativa de Chromium.", "web", true, true, false),
                new ServerDefinition(
                        "fetch", "Fetch", "Le paginas web e converte HTML em Markdown.", "web", true, true, false),
                new ServerDefinition(
                        "searxng",
                        "SearXNG",
                        "Pesquisa web por uma instancia SearXNG configurada pelo usuario.",
                        "web",
                        true,
                        true,
                        true),
                new ServerDefinition(
                        "git", "Git", "Analisa e opera um repositorio Git autorizado.", "developer", true, false, true),
                new ServerDefinition(
                        "dbhub",
                        "DBHub",
                        "Explora PostgreSQL, MySQL, SQL Server, MariaDB ou SQLite.",
                        "data",
                        true,
                        false,
                        true),
                new ServerDefinition(
                        "docker-gateway",
                        "Docker MCP Gateway",
                        "Agrega servidores MCP isolados pelo perfil do Docker.",
                        "advanced",
                        true,
                        false,
                        true));
    }

    private ServerLaunch launch(ServerDefinition definition, List<String> workspaceRoots) {
        List<String> roots = workspaceRoots == null ? List.of() : workspaceRoots;
        String id = definition.id();
        if (!enabled(id)) {
            return ServerLaunch.unavailable("Desativado por configuracao.");
        }
        return switch (id) {
            case "filesystem" ->
                roots.isEmpty()
                        ? ServerLaunch.unavailable("Selecione ao menos um workspace.")
                        : executable("npx", append(List.of("npx", "-y", packageName("filesystem")), roots), Map.of());
            case "markitdown" ->
                executablePath(
                        environment.getProperty(
                                "avento.documents.markitdown-mcp-command", defaultToolPath("markitdown-mcp")),
                        Map.of());
            case "memory" -> {
                Path memoryFile = Path.of(environment.getProperty(
                                "avento.mcp.memory.file",
                                Path.of(System.getProperty("user.home"), ".avento", "memory.json")
                                        .toString()))
                        .toAbsolutePath()
                        .normalize();
                try {
                    Files.createDirectories(memoryFile.getParent());
                } catch (IOException exception) {
                    yield ServerLaunch.unavailable(
                            "Nao foi possivel preparar a memoria local: " + exception.getMessage());
                }
                // A memoria e um arquivo do host. Montamos a PASTA, nao o arquivo: com bind de
                // arquivo inexistente o Docker cria um DIRETORIO com esse nome, e o servidor passa
                // a falhar para sempre num caminho que parece certo.
                yield containerOrElse(
                        "mcp/memory",
                        List.of(memoryFile.getParent() + ":/data"),
                        Map.of("MEMORY_FILE_PATH", "/data/" + memoryFile.getFileName()),
                        List.of(),
                        executable(
                                "npx",
                                List.of("npx", "-y", packageName("memory")),
                                Map.of("MEMORY_FILE_PATH", memoryFile.toString())));
            }
            case "sequential-thinking" ->
                containerOrElse(
                        "mcp/sequentialthinking",
                        List.of(),
                        Map.of(),
                        List.of(),
                        executable("npx", List.of("npx", "-y", packageName("sequential-thinking")), Map.of()));
            case "time" ->
                containerOrElse(
                        "mcp/time",
                        List.of(),
                        Map.of(),
                        List.of(),
                        executable("uvx", List.of("uvx", "mcp-server-time"), Map.of()));
            case "desktop-commander" ->
                executable(
                        "npx",
                        List.of("npx", "-y", "--package", packageName("desktop-commander"), "desktop-commander"),
                        Map.of());
            case "macos-automator" ->
                isMacOs()
                        ? executable(
                                "npx",
                                List.of(
                                        "npx",
                                        "-y",
                                        "--package",
                                        packageName("macos-automator"),
                                        "macos-automator-mcp"),
                                Map.of())
                        : ServerLaunch.unavailable("Disponivel somente no macOS.");
            case "apple" ->
                isMacOs()
                        ? executable(
                                "npx", List.of("npx", "-y", "--package", packageName("apple"), "apple-mcp"), Map.of())
                        : ServerLaunch.unavailable("Disponivel somente no macOS.");
            case "playwright" -> executable("npx", List.of("npx", "-y", packageName("playwright")), Map.of());
            case "chrome-devtools" ->
                executable(
                        "npx",
                        List.of("npx", "-y", "--package", packageName("chrome-devtools"), "chrome-devtools-mcp"),
                        Map.of());
            case "puppeteer" -> executable("npx", List.of("npx", "-y", packageName("puppeteer")), Map.of());
            case "fetch" ->
                containerOrElse(
                        "mcp/fetch",
                        List.of(),
                        Map.of("PYTHONIOENCODING", "utf-8"),
                        List.of(),
                        executable("uvx", List.of("uvx", "mcp-server-fetch"), Map.of("PYTHONIOENCODING", "utf-8")));
            case "searxng" ->
                configuredNpx(
                        "avento.mcp.searxng.url", "npx", List.of("npx", "-y", packageName("searxng")), "SEARXNG_URL");
            case "git" -> {
                if (roots.isEmpty()) {
                    yield ServerLaunch.unavailable("Selecione um workspace Git.");
                }
                String repository = roots.getFirst();
                // Git fica NO HOST, de proposito — e o unico dos cinco que nao foi para container.
                //
                // Medido em 08/08/2026 neste repo (37.725 arquivos, com target/ e node_modules):
                //
                //   host      `git status --porcelain`            0,056s
                //   container `git status -uno --porcelain`       0,573s   (so arquivos rastreados)
                //   container `git status --porcelain`            > 3 min  (nao retornou)
                //
                // O protocolo funciona e a montagem monta: o handshake do mcp/git responde em 0,6s
                // e um `rev-parse` volta em 0,28s. O que mata e a varredura de nao-rastreados sobre
                // o bind mount do Docker Desktop no macOS — cada stat custa, e sao dezenas de
                // milhares. Ferramenta que anda na arvore do host pertence ao host.
                yield executable("uvx", List.of("uvx", "mcp-server-git", "--repository", repository), Map.of());
            }
            case "dbhub" -> {
                Optional<DatabaseConfiguration> configuration = roots.isEmpty()
                        ? databaseDiscoveryService.fromGlobalDsn(environment
                                .getProperty("avento.mcp.dbhub.dsn", "")
                                .trim())
                        : databaseDiscoveryService.discover(roots);
                yield configuration
                        .map(database -> executable(
                                "npx",
                                List.of(
                                        "npx",
                                        "-y",
                                        packageName("dbhub"),
                                        "--transport",
                                        "stdio",
                                        "--config=" + database.configFile()),
                                database.environment()))
                        .orElseGet(() -> ServerLaunch.unavailable(
                                roots.isEmpty()
                                        ? "Configure AVENTO_MCP_DBHUB_DSN."
                                        : "Nenhum banco foi detectado no workspace deste chat."));
            }
            case "docker-gateway" -> {
                // Docker MCP Toolkit 4.62+ organiza servidores em perfis. Usar explicitamente o
                // perfil evita depender do registry legado e torna o catálogo reproduzível. A lista
                // de servidores, quando configurada, continua sendo uma alternativa deliberada e é
                // mutuamente exclusiva com --profile na CLI do Docker.
                if (!dockerDesktopRunning()) {
                    yield ServerLaunch.unavailable(
                            "O gateway e um plugin do Docker Desktop e exige que ele esteja em execucao."
                                    + " Um daemon alternativo (Colima, OrbStack, Rancher) roda containers"
                                    + " normalmente, mas nao atende o `docker mcp`. Os demais servidores MCP"
                                    + " do catalogo nao dependem disto.");
                }
                List<String> command = new ArrayList<>(List.of("docker", "mcp", "gateway", "run"));
                String servers = environment
                        .getProperty("avento.mcp.docker-gateway.servers", "")
                        .trim();
                if (!servers.isBlank()) {
                    command.add("--servers");
                    command.add(servers);
                } else {
                    String profile = environment
                            .getProperty("avento.mcp.docker-gateway.profile", "default")
                            .trim();
                    if (!profile.isBlank()) {
                        command.add("--profile");
                        command.add(profile);
                    }
                }
                yield executable("docker", List.copyOf(command), Map.of());
            }
            default -> ServerLaunch.unavailable("Servidor nao implementado.");
        };
    }

    private boolean enabled(String id) {
        String property =
                switch (id) {
                    case "filesystem" -> "avento.mcp.filesystem.enabled";
                    case "desktop-commander" -> "avento.mcp.desktop-commander.enabled";
                    case "macos-automator" -> "avento.mcp.macos-automator.enabled";
                    case "playwright" -> "avento.mcp.playwright.enabled";
                    case "puppeteer" -> "avento.mcp.puppeteer.enabled";
                    case "git" -> "avento.mcp.git.enabled";
                    case "dbhub" -> "avento.mcp.dbhub.enabled";
                    case "docker-gateway" -> "avento.mcp.docker.enabled";
                    case "apple" -> "avento.mcp.apple.enabled";
                    case "chrome-devtools" -> "avento.mcp.chrome-devtools.enabled";
                    default -> "";
                };
        return property.isBlank() || environment.getProperty(property, Boolean.class, true);
    }

    private ServerLaunch configuredNpx(
            String property, String commandName, List<String> command, String environmentName) {
        String value = environment.getProperty(property, "").trim();
        return value.isBlank()
                ? ServerLaunch.unavailable(
                        "Configure " + property.toUpperCase(Locale.ROOT).replace('.', '_') + ".")
                : executable(commandName, command, Map.of(environmentName, value));
    }

    private String packageName(String id) {
        return environment.getRequiredProperty("avento.mcp.packages." + id);
    }

    private ServerLaunch executablePath(String command, Map<String, String> variables) {
        Path path = ProjectPaths.resolve(command);
        return Files.isExecutable(path)
                ? ServerLaunch.ready(List.of(path.toString()), variables)
                : ServerLaunch.unavailable(
                        "Execute scripts/setup-local-mcps.sh para instalar " + path.getFileName() + ".");
    }

    private ServerLaunch executable(String name, List<String> command, Map<String, String> variables) {
        return commandAvailable(name)
                ? ServerLaunch.ready(command, variables)
                : ServerLaunch.unavailable("Comando nao encontrado: " + name);
    }

    /**
     * Lanca o servidor pela imagem oficial {@code mcp/<nome>} em vez de {@code npx}/{@code uvx}.
     *
     * <p>O MCP Toolkit do Docker esta quebrado nesta maquina — a interface diz "No MCP servers
     * added", o catalogo de 101 servidores nao contem nenhum {@code mcp/*} e o {@code registry.yaml}
     * tem os oito com {@code ref: ""}. Nada disso importa: o protocolo MCP fala por stdio, entao
     * {@code docker run --rm -i mcp/<nome>} conversa direto com a imagem, sem gateway e sem
     * catalogo. Verificado a mao com {@code initialize} -> {@code tools/list} -> chamada real.
     *
     * <p>{@code --pull=never} e deliberado: as imagens ja estao no disco e uma tentativa de pull
     * dentro do lancamento penduraria a listagem do catalogo esperando a rede. Sem tag local a
     * imagem simplesmente nao sobe, e o fallback assume.
     *
     * <p>As variaveis de ambiente entram como {@code -e} no proprio comando, e nao no mapa do
     * {@link ServerLaunch}: o processo que sobe e o {@code docker}, e o ambiente dele nao atravessa
     * para dentro do container.
     */
    /**
     * Servidores que rodam em container, e a imagem de cada um. Fonte ÚNICA — o lançamento e o
     * cache de schemas leem daqui, para não divergirem quando um servidor entrar ou sair.
     *
     * <p>Ausentes de propósito: {@code git} e {@code filesystem} andam na árvore do host e o bind
     * mount do Docker Desktop torna a varredura inviável (medido: {@code git status} não retornou em
     * 3 min contra 0,056s no host); {@code playwright} e {@code puppeteer} precisam de tela.
     */
    static final Map<String, String> CONTAINER_IMAGES = Map.of(
            "fetch", "mcp/fetch",
            "time", "mcp/time",
            "memory", "mcp/memory",
            "sequential-thinking", "mcp/sequentialthinking");

    private ServerLaunch containerOrElse(
            String image,
            List<String> mounts,
            Map<String, String> variables,
            List<String> serverArguments,
            ServerLaunch fallback) {
        if (!containersEnabled()) {
            return fallback;
        }
        List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "-i", "--pull=never"));
        for (String mount : mounts) {
            command.add("-v");
            command.add(mount);
        }
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            command.add("-e");
            command.add(variable.getKey() + "=" + variable.getValue());
        }
        command.add(image);
        command.addAll(serverArguments);
        return ServerLaunch.ready(List.copyOf(command), Map.of());
    }

    /**
     * Barato de proposito: nenhuma chamada ao {@code docker} para decidir. Esta pergunta e feita a
     * cada listagem do catalogo, e um processo por listagem custaria mais que o beneficio.
     */
    private boolean containersEnabled() {
        return environment.getProperty("avento.mcp.containers.enabled", Boolean.class, true)
                && commandAvailable("docker")
                && dockerDesktopRunning();
    }

    private boolean commandAvailable(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(directory, command))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Se o Docker Desktop esta no ar — nao apenas se ha um daemon Docker.
     *
     * <p>O {@code docker mcp} e um plugin que vive dentro do Docker.app e conversa com o backend do
     * Desktop, nao com o daemon. Com Colima, OrbStack ou Rancher os containers sobem normalmente e o
     * plugin ainda responde "Docker Desktop is not running" — ate no {@code --dry-run}. O socket
     * proprio do Desktop e o sinal barato e confiavel; rodar {@code docker mcp} so para perguntar
     * custaria um processo a cada listagem do catalogo.
     */
    private boolean dockerDesktopRunning() {
        return Files.exists(Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock"));
    }

    private boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private String defaultToolPath(String executable) {
        return ProjectPaths.resolve(null, ".avento-tools", "mcp", "bin", executable)
                .toString();
    }

    private List<String> append(List<String> base, List<String> suffix) {
        List<String> result = new ArrayList<>(base);
        result.addAll(suffix);
        return List.copyOf(result);
    }
}
