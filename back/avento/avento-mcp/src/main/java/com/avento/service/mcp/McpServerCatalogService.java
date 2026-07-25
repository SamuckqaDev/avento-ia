package com.avento.service.mcp;

import com.avento.service.dto.*;
import com.avento.service.dto.ConnectionResult;
import com.avento.service.dto.DatabaseConfiguration;
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

    McpServerCatalogService(
            McpClientManager clientManager,
            Environment environment,
            ProjectDatabaseDiscoveryService databaseDiscoveryService) {
        this(clientManager, environment, databaseDiscoveryService, new ToolExecutionContext());
    }

    @Autowired
    public McpServerCatalogService(
            McpClientManager clientManager,
            Environment environment,
            ProjectDatabaseDiscoveryService databaseDiscoveryService,
            ToolExecutionContext executionContext) {
        this.clientManager = clientManager;
        this.environment = environment;
        this.databaseDiscoveryService = databaseDiscoveryService;
        this.executionContext = executionContext;
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
            results.add(result.connected() ? result : ConnectionResult.failedFor(id, result.error()));
        }
        return List.copyOf(results);
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
                yield executable(
                        "npx",
                        List.of("npx", "-y", packageName("memory")),
                        Map.of("MEMORY_FILE_PATH", memoryFile.toString()));
            }
            case "sequential-thinking" ->
                executable("npx", List.of("npx", "-y", packageName("sequential-thinking")), Map.of());
            case "time" -> executable("uvx", List.of("uvx", "mcp-server-time"), Map.of());
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
            case "fetch" -> executable("uvx", List.of("uvx", "mcp-server-fetch"), Map.of("PYTHONIOENCODING", "utf-8"));
            case "searxng" ->
                configuredNpx(
                        "avento.mcp.searxng.url", "npx", List.of("npx", "-y", packageName("searxng")), "SEARXNG_URL");
            case "git" ->
                roots.isEmpty()
                        ? ServerLaunch.unavailable("Selecione um workspace Git.")
                        : executable(
                                "uvx", List.of("uvx", "mcp-server-git", "--repository", roots.getFirst()), Map.of());
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
                String profile = environment
                        .getProperty("avento.mcp.docker-gateway.profile", "")
                        .trim();
                yield profile.isBlank()
                        ? ServerLaunch.unavailable("Configure AVENTO_MCP_DOCKER_GATEWAY_PROFILE.")
                        : executable(
                                "docker", List.of("docker", "mcp", "gateway", "run", "--profile", profile), Map.of());
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
