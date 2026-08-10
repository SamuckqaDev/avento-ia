package com.avento.controller;

import com.avento.dto.BackupEntry;
import com.avento.dto.BaseResponse;
import com.avento.dto.CommandExecution;
import com.avento.dto.ConnectionResult;
import com.avento.dto.Context;
import com.avento.dto.DirectoryBackupEntry;
import com.avento.dto.DocumentReadResult;
import com.avento.dto.MacApplication;
import com.avento.dto.ManagedProcess;
import com.avento.dto.PinnedToolsRequest;
import com.avento.dto.SystemActionResult;
import com.avento.dto.ToolDefinition;
import com.avento.dto.api.ApiResponses;
import com.avento.model.exception.ApiServiceException;
import com.avento.service.FileBackupService;
import com.avento.service.GeneratedMediaAssetService;
import com.avento.service.ImageGenerationJobService;
import com.avento.service.NotificationService;
import com.avento.service.PdfGenerationService;
import com.avento.service.ProjectVerificationService;
import com.avento.service.SymbolSearchService;
import com.avento.service.SystemAutomationService;
import com.avento.service.VideoGenerationJobService;
import com.avento.service.WorkspaceAccessService;
import com.avento.service.auth.AuthPrincipal;
import com.avento.service.mcp.McpClientManager;
import com.avento.service.mcp.McpServerCatalogService;
import com.avento.service.memory.UserMemoryService;
import com.avento.service.rag.CodeSearchService;
import com.avento.service.rag.DocumentReaderService;
import com.avento.service.rag.WorkspaceIndexingService;
import com.avento.service.tools.LocalToolNames;
import com.avento.service.tools.LocalToolDefinitions;
import com.avento.service.tools.TerminalCommandPolicy;
import com.avento.service.tools.ToolCatalogService;
import com.avento.service.tools.ToolExecutionContext;
import com.avento.service.tools.ToolProvider;
import com.avento.service.tools.ToolSchemaNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/mcp")
public class McpController implements ToolProvider {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaNormalizer toolSchemaNormalizer = new ToolSchemaNormalizer(mapper);
    private final LocalToolDefinitions localToolDefinitions = new LocalToolDefinitions(this);
    private static final Set<String> LOCAL_TOOL_NAMES = LocalToolNames.ALL;
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git",
            "node_modules",
            "target",
            "dist",
            "build",
            ".venv",
            "venv",
            ".idea",
            ".gradle",
            ".dart_tool",
            "tmp");
    private static final int DEFAULT_TREE_DEPTH = 4;
    private static final int MAX_TREE_DEPTH = 8;
    private static final int DEFAULT_SEARCH_LIMIT = 50;
    private static final int MAX_SEARCH_LIMIT = 200;
    private static final Duration SCAFFOLD_TIMEOUT = Duration.ofMinutes(4);
    private static final int MAX_COMMAND_OUTPUT_CHARS = 20000;
    private static final int MAX_PROCESS_LOG_CHARS = 40000;
    private static final Set<String> ALLOWED_VITE_TEMPLATES = Set.of(
            "vanilla",
            "vanilla-ts",
            "react",
            "react-ts",
            "react-swc",
            "react-swc-ts",
            "vue",
            "vue-ts",
            "svelte",
            "svelte-ts",
            "preact",
            "preact-ts",
            "solid",
            "solid-ts",
            "lit",
            "lit-ts");

    private final Map<String, ManagedProcess> managedProcesses = new ConcurrentHashMap<>();

    @Autowired
    private WorkspaceAccessService workspaceAccessService;

    @Autowired
    private FileBackupService fileBackupService;

    @Autowired
    private SystemAutomationService systemAutomationService;

    @Autowired
    private NotificationService notificationService;

    @Autowired(required = false)
    private VideoGenerationJobService videoGenerationJobService;

    @Autowired(required = false)
    private ImageGenerationJobService imageGenerationJobService;

    @Autowired(required = false)
    private com.avento.service.execution.ScheduledTaskService scheduledTaskService;

    @Autowired(required = false)
    private GeneratedMediaAssetService generatedMediaAssetService;

    @Autowired(required = false)
    private ProjectVerificationService projectVerificationService;

    @Autowired(required = false)
    private SymbolSearchService symbolSearchService;

    @Autowired(required = false)
    private UserMemoryService userMemoryService;

    @Autowired(required = false)
    private com.avento.service.support.SkillRegistry skillRegistry;

    @Autowired
    private PdfGenerationService pdfGenerationService;

    @Autowired
    private DocumentReaderService documentReaderService;

    @Autowired(required = false)
    private McpClientManager mcpClientManager;

    @Autowired(required = false)
    private McpServerCatalogService mcpServerCatalogService;

    @Autowired
    private ToolExecutionContext toolExecutionContext;

    @Autowired
    private ToolCatalogService toolCatalogService;

    @Autowired(required = false)
    private com.avento.service.tools.PinnedToolService pinnedToolService;

    @Autowired
    private CodeSearchService codeSearchService;

    @Autowired
    private WorkspaceIndexingService workspaceIndexingService;

    @Value("${avento.mcp.sdk.enabled:true}")
    private boolean mcpSdkEnabled;

    @PostMapping("/connect")
    public synchronized ResponseEntity<BaseResponse<JsonNode>> connect(
            @RequestBody Map<String, Object> payload, @AuthenticationPrincipal AuthPrincipal principal) {
        Long chatId = optionalLong(payload.get("chatId"));
        try {
            return toolExecutionContext.call(
                    new Context(principal == null ? null : principal.userId(), chatId, ""),
                    () -> ApiResponses.ok(connectScoped(payload, principal)));
        } catch (Exception exception) {
            throw new ApiServiceException("Could not connect MCP servers.", exception);
        }
    }

    private JsonNode connectScoped(Map<String, Object> payload, AuthPrincipal principal) {
        List<String> projectPaths = extractProjectPaths(payload);
        ArrayNode connectedServers = mapper.createArrayNode();
        ArrayNode warnings = mapper.createArrayNode();
        ObjectNode environment = detectEnvironment();
        List<String> authorizedPaths = new ArrayList<>();
        for (String projectPath : projectPaths) {
            try {
                authorizedPaths.add(workspaceAccessService
                        .registerWorkspaceRoot(principal == null ? null : principal.userId(), projectPath)
                        .toString());
            } catch (RuntimeException exception) {
                warnings.add("Workspace ignorado: " + projectPath + " (" + exception.getMessage() + ")");
            }
        }

        if (mcpSdkEnabled && mcpClientManager != null && mcpServerCatalogService != null) {
            for (ConnectionResult result : mcpServerCatalogService.connectAuto(authorizedPaths)) {
                if (result.connected()) {
                    connectedServers.add(result.serverName());
                } else {
                    warnings.add("Servidor MCP " + result.serverName() + " não iniciou: " + result.error());
                }
            }
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "ready");
        result.put("localTools", true);
        result.set("workspaceRoots", mapper.valueToTree(authorizedPaths));
        result.set("environment", environment);
        result.set("connectedServers", connectedServers);
        result.set("warnings", warnings);
        if (mcpSdkEnabled && mcpClientManager != null) {
            result.set(
                    "sdk",
                    mcpClientManager.status(toolExecutionContext.current().scopeKey()));
        }
        return result;
    }

    private ObjectNode detectEnvironment() {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");
        String osVersion = System.getProperty("os.version", "");
        boolean macOs = osName.toLowerCase(Locale.ROOT).contains("mac");

        ObjectNode environment = mapper.createObjectNode();
        environment.put("osName", osName);
        environment.put("osArch", osArch);
        environment.put("osVersion", osVersion);
        environment.put("macOs", macOs);
        environment.put("windows", osName.toLowerCase(Locale.ROOT).contains("win"));
        environment.put("linux", osName.toLowerCase(Locale.ROOT).contains("linux"));

        ObjectNode commands = environment.putObject("commands");
        for (String command :
                List.of("node", "npm", "npx", "uvx", "osascript", "open", "shortcuts", "docker", "git", "mvn")) {
            commands.put(command, commandAvailable(command));
        }

        ObjectNode apps = environment.putObject("apps");
        if (macOs) {
            apps.put("Finder", true);
            apps.put("Terminal", macAppAvailable("Terminal"));
            apps.put("Visual Studio Code", macAppAvailable("Visual Studio Code"));
            apps.put("Brave Browser", macAppAvailable("Brave Browser"));
            apps.put("Google Chrome", macAppAvailable("Google Chrome"));
            apps.put("Safari", macAppAvailable("Safari"));
            apps.put("Figma", macAppAvailable("Figma"));
            apps.put("Cursor", macAppAvailable("Cursor"));
        }

        ObjectNode mcp = environment.putObject("mcp");
        mcp.put("sdk", mcpSdkEnabled);
        mcp.put("catalogManaged", mcpServerCatalogService != null);
        return environment;
    }

    private boolean commandAvailable(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-lc", "command -v " + command + " >/dev/null 2>&1").start();
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean macAppAvailable(String appName) {
        return Files.exists(Paths.get("/Applications", appName + ".app"))
                || Files.exists(Paths.get(System.getProperty("user.home"), "Applications", appName + ".app"))
                || Files.exists(Paths.get("/System/Applications", appName + ".app"))
                || Files.exists(Paths.get("/System/Applications/Utilities", appName + ".app"));
    }

    private List<String> extractProjectPaths(Map<String, Object> payload) {
        Object rawProjectPaths = payload.get("projectPaths");
        if (rawProjectPaths instanceof List<?>) {
            List<String> paths = new ArrayList<>();
            for (Object item : (List<?>) rawProjectPaths) {
                if (item instanceof String path && !path.trim().isEmpty()) {
                    paths.add(path);
                }
            }
            return paths;
        }

        Object rawProjectPath = payload.get("projectPath");
        if (rawProjectPath instanceof String path && !path.trim().isEmpty()) {
            return List.of(path);
        }

        return List.of();
    }

    public ArrayNode getAvailableToolsInternal() {
        ArrayNode allTools = mapper.createArrayNode();
        addLocalTools(allTools);
        Set<String> registeredToolNames = new HashSet<>(LOCAL_TOOL_NAMES);

        if (mcpSdkEnabled && mcpClientManager != null) {
            for (ToolDefinition definition :
                    mcpClientManager.listTools(toolExecutionContext.current().scopeKey())) {
                if (registeredToolNames.add(definition.exposedName())) {
                    allTools.add(externalTool(definition));
                }
            }
        }

        return allTools;
    }

    @Override
    public ArrayNode listTools() {
        return getAvailableToolsInternal();
    }

    private void addLocalTools(ArrayNode allTools) {
        Map<String, ObjectNode> annotatedTools = annotatedLocalTools();
        for (String toolName : LocalToolDefinitions.NAMES) {
            ObjectNode tool = annotatedTools.get(toolName);
            if (tool == null) {
                throw new IllegalStateException("Missing annotated local tool definition: " + toolName);
            }
            allTools.add(tool);
        }
    }

    private Map<String, ObjectNode> annotatedLocalTools() {
        Map<String, ObjectNode> tools = new LinkedHashMap<>();
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(localToolDefinitions)
                .build()
                .getToolCallbacks();

        for (ToolCallback callback : callbacks) {
            var definition = callback.getToolDefinition();
            ObjectNode tool = mapper.createObjectNode();
            tool.put("name", definition.name());
            tool.put("description", definition.description());
            tool.set("inputSchema", mapper.readTree(toolSchemaNormalizer.normalise(definition.inputSchema())));
            tools.put(definition.name(), tool);
        }

        return tools;
    }

    @GetMapping("/tools")
    public ResponseEntity<BaseResponse<JsonNode>> getTools() {
        try {
            ArrayNode allTools = getAvailableToolsInternal();
            return ApiResponses.ok(allTools);
        } catch (Exception e) {
            throw new ApiServiceException("Could not list MCP tools.", e);
        }
    }

    /**
     * Ferramentas que este usuário fixou.
     *
     * <p>Fixar é o contrapeso manual da descoberta progressiva: o catálogo continua decidindo o
     * resto, mas o que está aqui entra no toolset de toda rodada, sem depender de o modelo lembrar
     * de chamar {@code activate_tools}.
     */
    @GetMapping("/tools/pinned")
    public ResponseEntity<BaseResponse<List<String>>> getPinnedTools(@AuthenticationPrincipal AuthPrincipal principal) {
        if (pinnedToolService == null) {
            return ApiResponses.ok(List.of());
        }
        return ApiResponses.ok(List.copyOf(pinnedToolService.pinnedFor(principal != null ? principal.userId() : null)));
    }

    @PutMapping("/tools/pinned")
    public ResponseEntity<BaseResponse<List<String>>> updatePinnedTools(
            @RequestBody PinnedToolsRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        if (pinnedToolService == null) {
            return ApiResponses.ok(List.of());
        }
        Set<String> requested =
                request == null || request.toolNames() == null ? Set.of() : new LinkedHashSet<>(request.toolNames());
        return ApiResponses.ok(
                List.copyOf(pinnedToolService.replace(principal != null ? principal.userId() : null, requested)));
    }

    public JsonNode executeToolInternal(String name, Map<String, Object> payload) throws Exception {
        if (LOCAL_TOOL_NAMES.contains(name)) {
            return executeLocalTool(name, payload);
        }

        String scope = toolExecutionContext.current().scopeKey();
        if (mcpSdkEnabled && mcpClientManager != null && mcpClientManager.hasTool(scope, name)) {
            return mcpClientManager.callTool(scope, name, payload);
        }
        return mapper.createObjectNode().put("error", "Tool not found or server disconnected");
    }

    @Override
    public JsonNode execute(String toolName, Map<String, Object> arguments) throws Exception {
        return executeToolInternal(toolName, arguments);
    }

    private ObjectNode externalTool(ToolDefinition definition) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", definition.exposedName());
        tool.put("description", definition.description());
        tool.set("inputSchema", mapper.valueToTree(definition.inputSchema()));
        tool.put("mcpServer", definition.serverName());
        tool.put("originalName", definition.originalName());
        return tool;
    }

    private JsonNode executeLocalTool(String name, Map<String, Object> payload) throws IOException {
        return switch (name) {
            case "directory_tree" -> executeDirectoryTree(payload);
            case "read_file" -> executeReadFile(payload);
            case "read_document" -> executeReadDocument(payload);
            case "list_mcp_servers" -> executeListMcpServers(payload);
            case "connect_mcp_server" -> executeConnectMcpServer(payload);
            case "disconnect_mcp_server" -> executeDisconnectMcpServer(payload);
            case "write_file" -> executeWriteFile(payload);
            case "edit_file" -> executeEditFile(payload);
            case "delete_file" -> executeDeleteFile(payload);
            case "delete_directory" -> executeDeleteDirectory(payload);
            case "create_directory" -> executeCreateDirectory(payload);
            case "search_files" -> executeSearchFiles(payload);
            case "find_symbol" -> executeFindSymbol(payload);
            case "remember" -> executeRemember(payload);
            case "create_skill" -> executeCreateSkill(payload);
            case "list_skills" -> executeListSkills(payload);
            case "delete_skill" -> executeDeleteSkill(payload);
            case "create_vite_project" -> executeCreateViteProject(payload);
            case "list_macos_apps" -> executeListMacosApps(payload);
            case "open_app" -> executeOpenApp(payload);
            case "close_app" -> executeCloseApp(payload);
            case "open_browser_tab" -> executeOpenBrowserTab(payload);
            case "close_browser_tab" -> executeCloseBrowserTab(payload);
            case "open_url" -> executeOpenUrl(payload);
            case "open_path" -> executeOpenPath(payload);
            case "reveal_in_finder" -> executeRevealInFinder(payload);
            case "run_shortcut" -> executeRunShortcut(payload);
            case "capture_screen" -> executeCaptureScreen();
            case "generate_pdf" -> executeGeneratePdf(payload);
            case "generate_image" -> executeGenerateImage(payload);
            case "generate_video" -> executeGenerateVideo(payload);
            case "verify_project" -> executeVerifyProject(payload);
            case "revert_changes" -> executeRevertChanges();
            case "terminal_run" -> executeTerminalRun(payload);
            case "terminal_start" -> executeTerminalStart(payload);
            case "terminal_list" -> executeTerminalList();
            case "terminal_logs" -> executeTerminalLogs(payload);
            case "terminal_stop" -> executeTerminalStop(payload);
            case "search_capabilities" -> executeSearchCapabilities(payload);
            case "activate_tools" -> executeActivateTools(payload);
            case "search_code" -> executeSearchCode(payload);
            case "schedule_task" -> executeScheduleTask(payload);
            default -> mapper.createObjectNode().put("error", "Unknown local tool: " + name);
        };
    }

    private JsonNode executeScheduleTask(Map<String, Object> payload) throws IOException {
        String name = requiredString(payload, "name");
        String cron = payload.get("cronExpression") != null
                ? payload.get("cronExpression").toString()
                : "57 3 * * *";
        String prompt = requiredString(payload, "prompt");
        String description =
                payload.get("description") != null ? payload.get("description").toString() : "";
        String projectPath =
                payload.get("projectPath") != null ? payload.get("projectPath").toString() : "";

        if (scheduledTaskService == null) {
            ObjectNode err = mapper.createObjectNode();
            err.put("error", "Serviço de agendamento indisponível no servidor.");
            return toolResult(err);
        }

        Long chatId = toolExecutionContext.current().chatId();
        UUID userId = toolExecutionContext.current().userId();

        var task = scheduledTaskService.createTask(name, description, cron, prompt, chatId, projectPath, userId);

        ObjectNode response = mapper.createObjectNode();
        response.put("status", "SUCCESS");
        response.put("taskId", task.getId());
        response.put("name", task.getName());
        response.put("cronExpression", task.getCronExpression());
        response.put(
                "nextRunAt", task.getNextRunAt() != null ? task.getNextRunAt().toString() : "");
        response.put(
                "message", "Atividade '" + name + "' agendada com sucesso na sua agenda do Cowork (" + cron + ").");
        return toolResult(response);
    }

    // --- Progressive tool discovery -------------------------------------------------------------

    private JsonNode executeSearchCapabilities(Map<String, Object> payload) throws IOException {
        String query = requiredString(payload, "query");
        List<ToolCatalogService.CapabilitySummary> extras = new ArrayList<>();
        // External tools currently connected for this chat scope.
        if (mcpSdkEnabled && mcpClientManager != null) {
            for (ToolDefinition definition :
                    mcpClientManager.listTools(toolExecutionContext.current().scopeKey())) {
                extras.add(new ToolCatalogService.CapabilitySummary(
                        definition.exposedName(),
                        definition.exposedName(),
                        "MCP_EXTERNAL:" + definition.serverName(),
                        definition.description() == null ? "" : definition.description()));
            }
        }
        // Available-but-disconnected servers: surface them so the model knows a capability exists
        // one activate_tools/connect_mcp_server away instead of concluding it cannot act.
        if (mcpServerCatalogService != null) {
            for (var descriptor : mcpServerCatalogService.catalog(List.of())) {
                if (descriptor.available() && !descriptor.connected()) {
                    extras.add(new ToolCatalogService.CapabilitySummary(
                            "server:" + descriptor.id(),
                            descriptor.name(),
                            "MCP_SERVER_AVAILABLE",
                            descriptor.description()
                                    + " (servidor desconectado — conecte com connect_mcp_server serverId="
                                    + descriptor.id() + ")"));

                    // E, quando sabemos QUAIS ferramentas o servidor tem, anuncia cada uma pelo nome.
                    //
                    // Anunciar so o servidor obriga o modelo a adivinhar: para achar o download de
                    // pagina web ele teria de procurar por "fetch", o nome do servidor, e nao por
                    // "baixar pagina", o que ele quer fazer. Com as ferramentas na busca, a descoberta
                    // passa a casar pela CAPACIDADE.
                    //
                    // Isto nao custa orcamento de schema: sao resultados de busca, nao ferramentas
                    // expostas na rodada. O schema so entra depois do activate_tools.
                    //
                    // A lista vem do cache por digest de imagem, entao responder aqui NAO sobe
                    // container nenhum — que era a razao de o cache existir.
                    for (var tool : mcpServerCatalogService.knownTools(descriptor.id())) {
                        extras.add(new ToolCatalogService.CapabilitySummary(
                                tool.exposedName(),
                                tool.exposedName(),
                                "MCP_TOOL_AVAILABLE",
                                (tool.description() == null ? "" : tool.description())
                                        + " (do servidor " + descriptor.id()
                                        + ", desconectado — ative com activate_tools "
                                        + tool.exposedName() + ")"));
                    }
                }
            }
        }

        List<ToolCatalogService.CapabilitySummary> matches = toolCatalogService.searchCapabilities(query, extras);
        ObjectNode result = mapper.createObjectNode();
        result.put("query", query);
        ArrayNode capabilities = result.putArray("capabilities");
        for (ToolCatalogService.CapabilitySummary match : matches) {
            ObjectNode entry = capabilities.addObject();
            entry.put("tool", match.toolId());
            entry.put("category", match.category());
            entry.put("description", match.shortDescription());
        }
        result.put(
                "hint",
                matches.isEmpty()
                        ? "Nada encontrado. Tente outras palavras-chave ou liste os servidores com list_mcp_servers."
                        : "Ative as ferramentas necessarias com activate_tools e chame-as na proxima rodada.");
        return toolResult(result);
    }

    private JsonNode executeActivateTools(Map<String, Object> payload) throws IOException {
        List<String> requested = new ArrayList<>();
        if (payload.get("tools") instanceof List<?> names) {
            for (Object name : names) {
                if (name instanceof String value && !value.isBlank()) {
                    requested.add(value.trim());
                }
            }
        }

        // A tool of an available-but-disconnected server activates by connecting the server first.
        List<String> connectedServers = new ArrayList<>();
        Set<String> currentNames = collectAvailableToolNames();
        if (mcpServerCatalogService != null) {
            Set<String> namesBeforeConnect = currentNames;
            List<String> missing = requested.stream()
                    .filter(name -> !namesBeforeConnect.contains(name))
                    .toList();
            if (!missing.isEmpty()) {
                for (var descriptor : mcpServerCatalogService.catalog(List.of())) {
                    boolean referencedAsServer =
                            missing.contains("server:" + descriptor.id()) || missing.contains(descriptor.id());
                    if (descriptor.available() && !descriptor.connected() && referencedAsServer) {
                        mcpServerCatalogService.connect(List.of(descriptor.id()), List.of());
                        connectedServers.add(descriptor.id());
                    }
                }
                if (!connectedServers.isEmpty()) {
                    currentNames = collectAvailableToolNames();
                }
            }
        }

        String runId = toolExecutionContext.current().runId();
        Set<String> active = toolCatalogService.activateTools(runId, requested, currentNames);

        ObjectNode result = mapper.createObjectNode();
        ArrayNode activated = result.putArray("activeTools");
        active.forEach(activated::add);
        if (!connectedServers.isEmpty()) {
            ArrayNode servers = result.putArray("connectedServers");
            connectedServers.forEach(servers::add);
        }
        List<String> unknown =
                requested.stream().filter(name -> !active.contains(name)).toList();
        if (!unknown.isEmpty()) {
            ArrayNode rejected = result.putArray("unknownTools");
            unknown.forEach(rejected::add);
        }
        result.put("hint", "As ferramentas ativas ficam disponiveis nas proximas rodadas desta execucao.");
        return toolResult(result);
    }

    private Set<String> collectAvailableToolNames() {
        Set<String> names = new HashSet<>(LOCAL_TOOL_NAMES);
        if (mcpSdkEnabled && mcpClientManager != null) {
            for (ToolDefinition definition :
                    mcpClientManager.listTools(toolExecutionContext.current().scopeKey())) {
                names.add(definition.exposedName());
            }
        }
        return names;
    }

    private JsonNode executeSearchCode(Map<String, Object> payload) throws IOException {
        Path root = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        String query = requiredString(payload, "query");
        int maxResults = boundedInt(payload.get("maxResults"), 5, 1, 20);

        CodeSearchService.Result search = codeSearchService.search(root, query, maxResults);
        ObjectNode result = mapper.createObjectNode();
        result.put("query", query);
        result.put("workspace", root.toString());
        // The model reads the description of this tool once and the result on every call. Saying which
        // path answered is what lets it know whether a miss means "not in the code" or "matched no
        // literal token" — the two need different follow-up queries.
        result.put(
                "matching",
                search.strategy() == CodeSearchService.Strategy.VECTOR ? "semantica (indice vetorial)" : "literal");
        ArrayNode hits = result.putArray("results");
        for (CodeSearchService.Hit match : search.hits()) {
            ObjectNode hit = hits.addObject();
            hit.put("file", match.filePath());
            hit.put("startLine", match.startLine());
            hit.put("endLine", match.endLine());
            hit.put("snippet", match.snippet());
            hit.put("score", match.score());
        }
        if (search.hits().isEmpty()) {
            result.put("hint", "Nenhum trecho relevante. Tente termos mais especificos ou use search_files.");
        }
        return toolResult(result);
    }

    private JsonNode executeDirectoryTree(Map<String, Object> payload) throws IOException {
        Path root = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        if (!Files.isDirectory(root)) {
            return mapper.createObjectNode().put("error", "Path is not a directory: " + root);
        }

        int maxDepth = boundedInt(payload.get("maxDepth"), DEFAULT_TREE_DEPTH, 1, MAX_TREE_DEPTH);
        ObjectNode result = mapper.createObjectNode();
        result.put("path", root.toString());
        result.set("tree", buildTreeNode(root, 0, maxDepth));
        return toolResult(result);
    }

    private JsonNode executeRevertChanges() throws IOException {
        Context context = toolExecutionContext.current();
        if (context.userId() == null || context.chatId() == null) {
            return toolResult(mapper.createObjectNode().put("error", "Chat autenticado é obrigatório para reverter."));
        }
        var result = fileBackupService.revertMostRecent(context.userId(), context.chatId());
        ObjectNode node = mapper.createObjectNode();
        if (result.filesRestored() == 0) {
            node.put("reverted", false);
            node.put("message", "Não havia alterações de arquivo recentes para desfazer.");
        } else {
            node.put("reverted", true);
            node.put("filesRestored", result.filesRestored());
            node.put(
                    "message",
                    result.filesRestored() + " arquivo(s) restaurado(s) ao estado anterior às últimas edições.");
        }
        return toolResult(node);
    }

    private JsonNode executeRemember(Map<String, Object> payload) throws IOException {
        if (userMemoryService == null) {
            return toolResult(mapper.createObjectNode().put("error", "Memória de longo prazo indisponível."));
        }
        String content = requiredString(payload, "content");
        String category = optionalString(payload, "category");
        UUID userId = toolExecutionContext.current().userId();
        if (userId == null) {
            return toolResult(mapper.createObjectNode().put("error", "Usuário autenticado é obrigatório."));
        }
        var outcome = userMemoryService.suggest(
                userId,
                content,
                category == null || category.isBlank() ? UserMemoryService.defaultCategory() : category);
        ObjectNode result = mapper.createObjectNode();
        result.put("saved", outcome.saved());
        result.put("status", "PENDING");
        result.put("content", outcome.content());
        result.put(
                "message",
                outcome.saved()
                        ? "Sugestão de memória registrada; aguarda confirmação do usuário."
                        : "Essa memória já existia; nada foi duplicado.");
        return toolResult(result);
    }

    private JsonNode executeCreateSkill(Map<String, Object> payload) throws IOException {
        if (skillRegistry == null) {
            return toolResult(mapper.createObjectNode().put("error", "Sistema de skills indisponível."));
        }
        String name = requiredString(payload, "name");
        String description = requiredString(payload, "description");
        String instructions = requiredString(payload, "instructions");
        List<String> triggers = toStringList(payload.get("triggers"));
        try {
            var skill = skillRegistry.saveCustomSkill(name, description, triggers, instructions);
            ObjectNode result = mapper.createObjectNode();
            result.put("created", true);
            result.put("name", skill.name());
            result.put("triggers", String.join(", ", skill.triggers()));
            result.put(
                    "message",
                    "Skill '" + skill.name() + "' criada. Ela dispara sozinha quando a mensagem casar com um"
                            + " dos gatilhos"
                            + (skill.triggers().isEmpty()
                                    ? " (nenhum gatilho definido — so via /" + skill.name() + ")."
                                    : ": " + String.join(", ", skill.triggers()) + "."));
            return toolResult(result);
        } catch (IllegalArgumentException exception) {
            return toolResult(mapper.createObjectNode().put("error", exception.getMessage()));
        }
    }

    private JsonNode executeListSkills(Map<String, Object> payload) throws IOException {
        if (skillRegistry == null) {
            return toolResult(mapper.createObjectNode().put("error", "Sistema de skills indisponível."));
        }
        ArrayNode skills = mapper.createArrayNode();
        skillRegistry.all().forEach(skill -> {
            ObjectNode node = skills.addObject();
            node.put("name", skill.name());
            node.put("description", skill.description());
            node.put("builtin", skill.builtin());
            node.put("triggers", String.join(", ", skill.triggers()));
        });
        ObjectNode result = mapper.createObjectNode();
        result.set("skills", skills);
        result.put("count", skills.size());
        return toolResult(result);
    }

    private JsonNode executeDeleteSkill(Map<String, Object> payload) throws IOException {
        if (skillRegistry == null) {
            return toolResult(mapper.createObjectNode().put("error", "Sistema de skills indisponível."));
        }
        String name = requiredString(payload, "name");
        try {
            skillRegistry.deleteCustomSkill(name);
            return toolResult(mapper.createObjectNode()
                    .put("deleted", true)
                    .put("name", name)
                    .put("message", "Skill '" + name + "' apagada."));
        } catch (IllegalArgumentException exception) {
            return toolResult(mapper.createObjectNode().put("error", exception.getMessage()));
        }
    }

    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(text -> !text.isBlank())
                    .toList();
        }
        if (value instanceof String text) {
            return Arrays.stream(text.split(","))
                    .map(String::trim)
                    .filter(part -> !part.isBlank())
                    .toList();
        }
        return List.of();
    }

    private JsonNode executeFindSymbol(Map<String, Object> payload) throws IOException {
        if (symbolSearchService == null) {
            return toolResult(mapper.createObjectNode().put("error", "Busca de símbolo indisponível."));
        }
        var found = symbolSearchService.find(requiredString(payload, "path"), requiredString(payload, "symbol"));
        ObjectNode result = mapper.createObjectNode();
        result.put("symbol", requiredString(payload, "symbol"));
        result.put("count", found.size());
        ArrayNode matches = result.putArray("matches");
        found.forEach(match -> {
            ObjectNode node = matches.addObject();
            node.put("file", match.file());
            node.put("line", match.line());
            node.put("text", match.text());
        });
        return toolResult(result);
    }

    private JsonNode executeVerifyProject(Map<String, Object> payload) throws IOException {
        if (projectVerificationService == null) {
            return toolResult(mapper.createObjectNode().put("error", "Verificação de projeto indisponível."));
        }
        var verification = projectVerificationService.verify(requiredString(payload, "path"));
        ObjectNode result = mapper.createObjectNode();
        result.put("detected", verification.detected());
        result.put("ok", verification.ok());
        result.put("command", verification.command());
        result.put("exitCode", verification.exitCode());
        result.put("timedOut", verification.timedOut());
        result.put("errorSummary", verification.errorSummary());
        return toolResult(result);
    }

    private JsonNode executeTerminalStart(Map<String, Object> payload) throws IOException {
        Path workingDirectory = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        if (!Files.isDirectory(workingDirectory)) {
            return mapper.createObjectNode().put("error", "Command path must be a directory: " + workingDirectory);
        }

        String commandText = requiredString(payload, "command").trim();
        List<String> command = TerminalCommandPolicy.allowedLongRunningCommand(commandText);
        if (command.isEmpty()) {
            return mapper.createObjectNode().put("error", "Long-running command is not allowed: " + commandText);
        }

        String processId = "proc_" + UUID.randomUUID();
        UUID ownerId = optionalUuid(payload.get("_userId"));
        ManagedProcess managedProcess = startManagedProcess(processId, ownerId, workingDirectory, command);
        managedProcesses.put(processId, managedProcess);

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "running");
        result.put("processId", processId);
        result.put("path", workingDirectory.toString());
        result.put("command", String.join(" ", command));
        return toolResult(result);
    }

    private JsonNode executeTerminalList() throws IOException {
        ArrayNode processes = mapper.createArrayNode();
        UUID ownerId = toolExecutionContext.current().userId();
        managedProcesses.forEach((id, managedProcess) -> {
            if (!ownedBy(managedProcess, ownerId)) {
                return;
            }
            ObjectNode process = mapper.createObjectNode();
            process.put("processId", id);
            process.put("command", String.join(" ", managedProcess.command()));
            process.put("path", managedProcess.workingDirectory().toString());
            process.put("running", managedProcess.process().isAlive());
            process.put("startedAt", managedProcess.startedAt());
            processes.add(process);
        });

        ObjectNode result = mapper.createObjectNode();
        result.set("processes", processes);
        return toolResult(result);
    }

    private JsonNode executeTerminalLogs(Map<String, Object> payload) throws IOException {
        String processId = requiredString(payload, "processId");
        ManagedProcess managedProcess =
                ownedProcess(processId, toolExecutionContext.current().userId());
        if (managedProcess == null) {
            return mapper.createObjectNode().put("error", "Process not found: " + processId);
        }

        int maxChars = boundedInt(payload.get("maxChars"), 8000, 1, MAX_PROCESS_LOG_CHARS);
        ObjectNode result = mapper.createObjectNode();
        result.put("processId", processId);
        result.put("running", managedProcess.process().isAlive());
        if (managedProcess.process().isAlive()) {
            result.putNull("exitCode");
        } else {
            result.put("exitCode", managedProcess.process().exitValue());
        }
        result.put("logs", managedProcess.tail(maxChars));
        return toolResult(result);
    }

    private JsonNode executeTerminalStop(Map<String, Object> payload) throws IOException {
        String processId = requiredString(payload, "processId");
        ManagedProcess managedProcess =
                ownedProcess(processId, toolExecutionContext.current().userId());
        if (managedProcess == null) {
            return mapper.createObjectNode().put("error", "Process not found: " + processId);
        }
        managedProcesses.remove(processId, managedProcess);

        if (managedProcess.process().isAlive()) {
            managedProcess.process().destroy();
            try {
                if (!managedProcess.process().waitFor(5, TimeUnit.SECONDS)) {
                    managedProcess.process().destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                managedProcess.process().destroyForcibly();
            }
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "stopped");
        result.put("processId", processId);
        result.put("logs", managedProcess.tail(8000));
        return toolResult(result);
    }

    private JsonNode executeReadFile(Map<String, Object> payload) throws IOException {
        Path file = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        if (!Files.isRegularFile(file)) {
            return mapper.createObjectNode().put("error", "Path is not a file: " + file);
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("path", file.toString());
        result.put("content", Files.readString(file, StandardCharsets.UTF_8));
        return toolResult(result);
    }

    private JsonNode executeReadDocument(Map<String, Object> payload) throws IOException {
        DocumentReadResult document = documentReaderService.read(requiredString(payload, "path"));
        ObjectNode result = mapper.valueToTree(document);
        return toolResult(result);
    }

    private JsonNode executeListMcpServers(Map<String, Object> payload) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        result.set("servers", mapper.valueToTree(mcpServerCatalogService.catalog(authorizedProjectPaths(payload))));
        return toolResult(result);
    }

    private JsonNode executeConnectMcpServer(Map<String, Object> payload) throws IOException {
        String serverId = requiredString(payload, "serverId");
        ObjectNode result = mapper.createObjectNode();
        result.set(
                "results",
                mapper.valueToTree(
                        mcpServerCatalogService.connect(List.of(serverId), authorizedProjectPaths(payload))));
        return toolResult(result);
    }

    private JsonNode executeDisconnectMcpServer(Map<String, Object> payload) throws IOException {
        String serverId = requiredString(payload, "serverId");
        mcpServerCatalogService.disconnect(List.of(serverId));
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "disconnected");
        result.put("serverId", serverId);
        return toolResult(result);
    }

    private List<String> authorizedProjectPaths(Map<String, Object> payload) {
        List<String> authorized = new ArrayList<>();
        Object raw = payload.get("projectPaths");
        if (raw instanceof List<?> paths) {
            for (Object item : paths) {
                if (item instanceof String path && !path.isBlank()) {
                    authorized.add(
                            workspaceAccessService.requireAuthorized(path).toString());
                }
            }
        }
        return List.copyOf(authorized);
    }

    private JsonNode executeWriteFile(Map<String, Object> payload) throws IOException {
        Path file = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        String content = requiredString(payload, "content");

        Path parent = file.getParent();
        if (parent != null) {
            workspaceAccessService.requireAuthorized(parent.toString());
            Files.createDirectories(parent);
        }

        BackupEntry backup = fileBackupService.backupBeforeWrite(file, optionalString(payload, "_runId"));
        Files.writeString(file, content, StandardCharsets.UTF_8);
        noteIndexableChange(file);

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "success");
        result.put("path", file.toString());
        result.put("backupId", backup.id());
        result.put("bytesWritten", Files.size(file));
        return toolResult(result);
    }

    /**
     * Tells the indexer a file moved under it.
     *
     * <p>Saving is the right trigger, not sending a message: the pass is incremental by file hash, so
     * it re-embeds only what changed, while a pass per message would walk the whole tree to find out
     * nothing did.
     */
    private void noteIndexableChange(Path file) {
        if (workspaceIndexingService != null) {
            workspaceIndexingService.noteFileChanged(file);
        }
    }

    private JsonNode executeEditFile(Map<String, Object> payload) throws IOException {
        Path file = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        if (!Files.isRegularFile(file)) {
            return mapper.createObjectNode().put("error", "Path is not a file: " + file);
        }

        String oldString = requiredString(payload, "old_string");
        Object rawNewString = payload.get("new_string");
        if (!(rawNewString instanceof String newString)) {
            return mapper.createObjectNode().put("error", "new_string is required");
        }
        if (oldString.equals(newString)) {
            return mapper.createObjectNode().put("error", "old_string and new_string are identical, nothing to change");
        }

        boolean replaceAll = Boolean.TRUE.equals(payload.get("replace_all"));

        String content = Files.readString(file, StandardCharsets.UTF_8);
        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            return mapper.createObjectNode().put("error", "old_string not found in file: " + file);
        }
        if (!replaceAll && occurrences > 1) {
            return mapper.createObjectNode()
                    .put(
                            "error",
                            "old_string matches " + occurrences
                                    + " locations in the file. Add more surrounding context to old_string to make it"
                                    + " unique, or set replace_all to true.");
        }

        String updatedContent = replaceAll
                ? content.replace(oldString, newString)
                : replaceFirstOccurrence(content, oldString, newString);

        BackupEntry backup = fileBackupService.backupBeforeWrite(file, optionalString(payload, "_runId"));
        Files.writeString(file, updatedContent, StandardCharsets.UTF_8);
        noteIndexableChange(file);

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "success");
        result.put("path", file.toString());
        result.put("backupId", backup.id());
        result.put("replacements", replaceAll ? occurrences : 1);
        result.put("bytesWritten", Files.size(file));
        return toolResult(result);
    }

    private int countOccurrences(String content, String needle) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String replaceFirstOccurrence(String content, String oldString, String newString) {
        int index = content.indexOf(oldString);
        return content.substring(0, index) + newString + content.substring(index + oldString.length());
    }

    private JsonNode executeDeleteFile(Map<String, Object> payload) throws IOException {
        Path file = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        if (!Files.isRegularFile(file)) {
            return mapper.createObjectNode().put("error", "Path is not a regular file: " + file);
        }

        BackupEntry backup = fileBackupService.backupBeforeWrite(file, optionalString(payload, "_runId"));
        Files.delete(file);

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "deleted");
        result.put("path", file.toString());
        result.put("backupId", backup.id());
        return toolResult(result);
    }

    private JsonNode executeDeleteDirectory(Map<String, Object> payload) throws IOException {
        Path directory = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        if (!Files.isDirectory(directory)) {
            return mapper.createObjectNode().put("error", "Path is not a directory: " + directory);
        }
        if (workspaceAccessService.isRegisteredRoot(directory)) {
            return mapper.createObjectNode()
                    .put("error", "Refusing to delete an entire authorized workspace root: " + directory);
        }

        DirectoryBackupEntry backup =
                fileBackupService.backupDirectoryBeforeDelete(directory, optionalString(payload, "_runId"));
        deleteRecursively(directory);

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "deleted");
        result.put("path", directory.toString());
        result.put("backupId", backup.id());
        result.put("backedUp", backup.backedUp());
        result.put("fileCount", backup.fileCount());
        if (!backup.backedUp()) {
            result.put(
                    "warning",
                    "Pasta tinha " + backup.fileCount()
                            + " arquivos, acima do limite de backup automatico; exclusao rodou sem backup.");
        }
        return toolResult(result);
    }

    private void deleteRecursively(Path directory) throws IOException {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(directory)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    private JsonNode executeCreateDirectory(Map<String, Object> payload) throws IOException {
        Path directory = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        boolean existed = Files.exists(directory);
        Files.createDirectories(directory);
        if (!existed) {
            fileBackupService.recordCreatedDirectory(directory, optionalString(payload, "_runId"));
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "success");
        result.put("path", directory.toString());
        return toolResult(result);
    }

    private JsonNode executeSearchFiles(Map<String, Object> payload) throws IOException {
        Path root = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        String pattern = requiredString(payload, "pattern").toLowerCase(Locale.ROOT);
        int maxResults = boundedInt(payload.get("maxResults"), DEFAULT_SEARCH_LIMIT, 1, MAX_SEARCH_LIMIT);

        ArrayNode matches = mapper.createArrayNode();
        collectMatches(root, pattern, maxResults, matches, new HashSet<>());

        ObjectNode result = mapper.createObjectNode();
        result.put("path", root.toString());
        result.put("pattern", pattern);
        result.put("count", matches.size());
        result.set("matches", matches);
        return toolResult(result);
    }

    private JsonNode executeCreateViteProject(Map<String, Object> payload) throws IOException {
        Path parentDirectory = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        if (!Files.isDirectory(parentDirectory)) {
            return mapper.createObjectNode().put("error", "Path is not a directory: " + parentDirectory);
        }

        String projectName = requiredString(payload, "projectName").trim();
        if (!projectName.equals(".") && !projectName.matches("[A-Za-z0-9._-]+")) {
            return mapper.createObjectNode()
                    .put("error", "Invalid projectName. Use letters, numbers, dot, underscore or hyphen.");
        }

        String template = requiredString(payload, "template").trim();
        if (!ALLOWED_VITE_TEMPLATES.contains(template)) {
            return mapper.createObjectNode().put("error", "Vite template is not allowed: " + template);
        }

        Path targetDirectory = projectName.equals(".")
                ? parentDirectory
                : workspaceAccessService.requireAuthorized(
                        parentDirectory.resolve(projectName).toString());
        if (!targetDirectory.startsWith(parentDirectory)) {
            return mapper.createObjectNode()
                    .put("error", "Target project path must stay inside the authorized workspace.");
        }
        if (Files.exists(targetDirectory) && !isDirectoryEmpty(targetDirectory)) {
            return mapper.createObjectNode()
                    .put("error", "Target directory already exists and is not empty: " + targetDirectory);
        }

        boolean installDependencies = optionalBoolean(payload.get("installDependencies"), false);
        List<String> scaffoldCommand =
                List.of("npm", "create", "vite@latest", projectName, "--", "--template", template);
        CommandExecution scaffold = runCommand(parentDirectory, scaffoldCommand, SCAFFOLD_TIMEOUT);

        CommandExecution install = null;
        if (scaffold.exitCode() == 0 && installDependencies) {
            install = runCommand(targetDirectory, List.of("npm", "install"), SCAFFOLD_TIMEOUT);
        }

        ObjectNode result = mapper.createObjectNode();
        result.put(
                "status",
                scaffold.exitCode() == 0 && (install == null || install.exitCode() == 0) ? "success" : "failed");
        result.put("path", targetDirectory.toString());
        result.put("template", template);
        result.set("scaffold", commandResult(scaffold));
        if (install != null) {
            result.set("install", commandResult(install));
        }
        return toolResult(result);
    }

    private JsonNode executeOpenApp(Map<String, Object> payload) throws IOException {
        return systemActionResult(systemAutomationService.openApp(requiredString(payload, "appName")));
    }

    private JsonNode executeListMacosApps(Map<String, Object> payload) throws IOException {
        String query = optionalString(payload, "query");
        String normalizedQuery = query == null
                ? ""
                : query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        List<MacApplication> applications = systemAutomationService.listMacApplications().stream()
                .filter(app -> normalizedQuery.isBlank()
                        || app.name()
                                .toLowerCase(Locale.ROOT)
                                .replaceAll("[^a-z0-9]+", " ")
                                .contains(normalizedQuery))
                .toList();

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "success");
        result.put("count", applications.size());
        if (query != null && !query.isBlank()) {
            result.put("query", query);
        }
        ArrayNode apps = result.putArray("apps");
        for (MacApplication application : applications) {
            ObjectNode app = apps.addObject();
            app.put("name", application.name());
            app.put("path", application.path());
        }
        return toolResult(result);
    }

    private JsonNode executeCloseApp(Map<String, Object> payload) throws IOException {
        return systemActionResult(systemAutomationService.closeApp(requiredString(payload, "appName")));
    }

    private JsonNode executeOpenBrowserTab(Map<String, Object> payload) throws IOException {
        return systemActionResult(systemAutomationService.openBrowserTab(
                requiredString(payload, "browserName"), optionalString(payload, "url")));
    }

    private JsonNode executeCloseBrowserTab(Map<String, Object> payload) throws IOException {
        return systemActionResult(systemAutomationService.closeBrowserTab(requiredString(payload, "browserName")));
    }

    private JsonNode executeOpenUrl(Map<String, Object> payload) throws IOException {
        return systemActionResult(systemAutomationService.openUrl(requiredString(payload, "url")));
    }

    private JsonNode executeOpenPath(Map<String, Object> payload) throws IOException {
        Path target = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        return systemActionResult(systemAutomationService.openPath(target));
    }

    private JsonNode executeRevealInFinder(Map<String, Object> payload) throws IOException {
        Path target = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        return systemActionResult(systemAutomationService.revealInFinder(target));
    }

    private JsonNode executeRunShortcut(Map<String, Object> payload) throws IOException {
        return systemActionResult(systemAutomationService.runShortcut(requiredString(payload, "shortcutName")));
    }

    private JsonNode executeCaptureScreen() throws IOException {
        Path outputPath = defaultScreenshotPath();
        SystemActionResult actionResult = systemAutomationService.captureScreen(outputPath);

        ObjectNode result = mapper.createObjectNode();
        result.put("status", actionResult.status());
        result.put("path", outputPath.toString());
        result.put("command", String.join(" ", actionResult.command()));
        result.put("exitCode", actionResult.exitCode());
        result.put("timedOut", actionResult.timedOut());
        result.put("durationSeconds", actionResult.durationSeconds());
        result.put("output", actionResult.output());
        if ("success".equals(actionResult.status()) && Files.exists(outputPath)) {
            result.put("sizeBytes", Files.size(outputPath));
        }
        if (!"success".equals(actionResult.status())) {
            result.put(
                    "error",
                    actionResult.output() == null || actionResult.output().isBlank()
                            ? "Screen capture failed."
                            : actionResult.output());
        }
        return toolResult(result);
    }

    private JsonNode executeGenerateVideo(Map<String, Object> payload) throws IOException {
        String prompt = requiredString(payload, "prompt").trim();
        String size = fallbackString(optionalString(payload, "size"), "auto");
        int seconds = boundedInt(payload.get("seconds"), 2, 1, 5);
        if (videoGenerationJobService == null) {
            return toolResult(mapper.createObjectNode().put("error", "Serviço assíncrono de vídeo indisponível."));
        }
        Long chatId = requiredLong(payload, "_chatId");
        UUID userId = UUID.fromString(requiredString(payload, "_userId"));
        String mode =
                fallbackString(optionalString(payload, "mode"), "auto").trim().toLowerCase(Locale.ROOT);
        if (!List.of("auto", "image", "text").contains(mode)) {
            throw new IllegalArgumentException("Modo de vídeo inválido. Use auto, image ou text.");
        }
        Path sourceImage = null;
        if (!"text".equals(mode) && generatedMediaAssetService != null) {
            sourceImage = generatedMediaAssetService
                    .latestImageForChat(chatId, userId)
                    .orElse(null);
        }
        if ("image".equals(mode) && sourceImage == null) {
            throw new IllegalArgumentException("O chat não possui uma imagem gerada para animar.");
        }
        return toolResult(videoGenerationJobService.enqueue(prompt, size, seconds, chatId, userId, sourceImage));
    }

    private JsonNode executeGeneratePdf(Map<String, Object> payload) throws IOException {
        String title = requiredString(payload, "title");
        String markdown = payload.get("markdown") != null ? String.valueOf(payload.get("markdown")) : null;
        String html = payload.get("html") != null ? String.valueOf(payload.get("html")) : null;
        Long chatId = requiredLong(payload, "_chatId");
        UUID userId = UUID.fromString(requiredString(payload, "_userId"));

        ObjectNode result = pdfGenerationService.generate(title, markdown, html, chatId, userId);
        result.put(
                "message",
                "Documento gerado: [[avento-doc:" + result.get("filename").asText() + "]]");
        return toolResult(result);
    }

    private JsonNode executeGenerateImage(Map<String, Object> payload) throws IOException {
        if (imageGenerationJobService == null) {
            return toolResult(mapper.createObjectNode().put("error", "Serviço assíncrono de imagem indisponível."));
        }
        Long chatId = requiredLong(payload, "_chatId");
        UUID userId = UUID.fromString(requiredString(payload, "_userId"));
        return toolResult(imageGenerationJobService.enqueue(payload, chatId, userId));
    }

    private Path defaultScreenshotPath() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return Paths.get(
                System.getProperty("user.home"),
                "Pictures",
                "Avento Screenshots",
                "avento-screenshot-" + timestamp + ".png");
    }

    private JsonNode systemActionResult(SystemActionResult actionResult) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", actionResult.status());
        result.put("command", String.join(" ", actionResult.command()));
        result.put("exitCode", actionResult.exitCode());
        result.put("timedOut", actionResult.timedOut());
        result.put("durationSeconds", actionResult.durationSeconds());
        result.put("output", actionResult.output());
        if (!"success".equals(actionResult.status())) {
            result.put(
                    "error",
                    actionResult.output() == null || actionResult.output().isBlank()
                            ? "System automation command failed."
                            : actionResult.output());
        }
        return toolResult(result);
    }

    private JsonNode executeTerminalRun(Map<String, Object> payload) throws IOException {
        String pathStr = optionalString(payload, "path");
        if (pathStr == null || pathStr.isBlank()) {
            pathStr = optionalString(payload, "cwd");
        }
        if (pathStr == null || pathStr.isBlank()) {
            Set<Path> roots = workspaceAccessService.authorizedRoots();
            pathStr = !roots.isEmpty() ? roots.iterator().next().toString() : System.getProperty("user.dir");
        }
        Path workingDirectory = workspaceAccessService.requireAuthorized(pathStr);
        if (!Files.isDirectory(workingDirectory)) {
            return mapper.createObjectNode().put("error", "Command path must be a directory: " + workingDirectory);
        }

        String commandText = requiredString(payload, "command").trim();
        List<String> command = TerminalCommandPolicy.allowedTerminalCommand(commandText);
        if (command.isEmpty()) {
            return mapper.createObjectNode().put("error", "Command is not allowed: " + commandText);
        }

        int timeoutSeconds = boundedInt(
                payload.get("timeoutSeconds"),
                TerminalCommandPolicy.defaultTerminalTimeoutSeconds(commandText),
                1,
                300);
        CommandExecution execution = runCommand(workingDirectory, command, Duration.ofSeconds(timeoutSeconds));

        ObjectNode result = mapper.createObjectNode();
        result.put("status", execution.exitCode() == 0 ? "success" : "failed");
        result.put("path", workingDirectory.toString());
        result.set("execution", commandResult(execution));
        return toolResult(result);
    }

    private ObjectNode buildTreeNode(Path path, int depth, int maxDepth) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put(
                "name",
                path.getFileName() == null
                        ? path.toString()
                        : path.getFileName().toString());
        node.put("path", path.toString());
        node.put("type", Files.isDirectory(path) ? "directory" : "file");

        if (!Files.isDirectory(path) || depth >= maxDepth) {
            return node;
        }

        ArrayNode children = mapper.createArrayNode();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            List<Path> entries = new ArrayList<>();
            for (Path entry : stream) {
                if (shouldSkip(entry)) {
                    continue;
                }
                entries.add(entry);
            }
            entries.sort(Comparator.comparing((Path entry) -> !Files.isDirectory(entry))
                    .thenComparing(entry -> entry.getFileName().toString().toLowerCase(Locale.ROOT)));
            for (Path entry : entries) {
                children.add(buildTreeNode(entry, depth + 1, maxDepth));
            }
        }
        node.set("children", children);
        return node;
    }

    private void collectMatches(Path root, String pattern, int maxResults, ArrayNode matches, Set<Path> visited)
            throws IOException {
        if (matches.size() >= maxResults || shouldSkip(root)) {
            return;
        }

        Path normalized = root.toAbsolutePath().normalize();
        if (!visited.add(normalized)) {
            return;
        }

        String name = root.getFileName() == null
                ? root.toString()
                : root.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).contains(pattern)) {
            ObjectNode match = mapper.createObjectNode();
            match.put("name", name);
            match.put("path", root.toString());
            match.put("type", Files.isDirectory(root) ? "directory" : "file");
            matches.add(match);
        }

        if (!Files.isDirectory(root)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                collectMatches(entry, pattern, maxResults, matches, visited);
                if (matches.size() >= maxResults) {
                    return;
                }
            }
        }
    }

    private boolean shouldSkip(Path path) throws IOException {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return Files.isHidden(path) || IGNORED_DIRECTORY_NAMES.contains(name);
    }

    private CommandExecution runCommand(Path workingDirectory, List<String> command, Duration timeout)
            throws IOException {
        long startedAt = System.currentTimeMillis();
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workingDirectory.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.environment().put("npm_config_yes", "true");
            processBuilder.environment().put("NO_COLOR", "1");

            process = processBuilder.start();
            Process runningProcess = process;
            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            Thread outputReader = new Thread(() -> {
                try {
                    runningProcess.getInputStream().transferTo(outputBuffer);
                } catch (IOException ignored) {
                    // Command output is best-effort; exit code still carries the result.
                }
            });
            outputReader.start();

            boolean completed = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                outputReader.join(1000);
                return new CommandExecution(
                        command,
                        -1,
                        true,
                        elapsedSeconds(startedAt),
                        truncateOutput(outputBuffer.toString(StandardCharsets.UTF_8)));
            }

            outputReader.join(1000);
            return new CommandExecution(
                    command,
                    process.exitValue(),
                    false,
                    elapsedSeconds(startedAt),
                    truncateOutput(outputBuffer.toString(StandardCharsets.UTF_8)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandExecution(
                    command, -1, true, elapsedSeconds(startedAt), "Command execution was interrupted.");
        }
    }

    private ManagedProcess startManagedProcess(
            String processId, UUID ownerId, Path workingDirectory, List<String> command) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("NO_COLOR", "1");

        Process process = processBuilder.start();
        ManagedProcess managedProcess = new ManagedProcess(
                processId,
                ownerId,
                process,
                workingDirectory,
                command,
                LocalDateTime.now().toString(),
                new StringBuilder());

        Thread outputReader = new Thread(() -> {
            try {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = process.getInputStream().read(buffer)) != -1) {
                    managedProcess.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // Process logs are best-effort.
            } finally {
                notifyIfProcessCrashedUnexpectedly(processId, managedProcess);
            }
        });
        outputReader.setDaemon(true);
        outputReader.start();
        return managedProcess;
    }

    // Fires a native notification when a terminal_start process dies on its own with a non-zero
    // exit code, so the user finds out even if they aren't looking at the Avento tab. If
    // executeTerminalStop() already removed this processId from managedProcesses, the process
    // was stopped on purpose and there's nothing to alert about.
    private void notifyIfProcessCrashedUnexpectedly(String processId, ManagedProcess managedProcess) {
        if (!managedProcesses.containsKey(processId)) {
            return;
        }
        int exitCode;
        try {
            exitCode = managedProcess.process().waitFor();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return;
        }
        if (exitCode == 0) {
            return;
        }
        String command = String.join(" ", managedProcess.command());
        String title = "Avento — processo encerrou com erro";
        String message = command + " parou sozinho (exit " + exitCode + ").";
        systemAutomationService.displayNotification(title, message);
        notificationService.record("process.crashed", title, message);
    }

    private ObjectNode commandResult(CommandExecution execution) {
        ObjectNode node = mapper.createObjectNode();
        node.put("command", String.join(" ", execution.command()));
        node.put("exitCode", execution.exitCode());
        node.put("timedOut", execution.timedOut());
        node.put("durationSeconds", execution.durationSeconds());
        node.put("output", execution.output());
        return node;
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return true;
        }
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return !stream.iterator().hasNext();
        }
    }

    private JsonNode toolResult(ObjectNode result) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.setAll(result);
        ArrayNode content = root.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", result.toString());
        return root;
    }

    private String requiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return text;
    }

    private Long requiredLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // Falls through to the same validation error used for missing values.
            }
        }
        throw new IllegalArgumentException(key + " is required");
    }

    private Long optionalLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private UUID optionalUuid(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ManagedProcess ownedProcess(String processId, UUID ownerId) {
        ManagedProcess process = managedProcesses.get(processId);
        return ownedBy(process, ownerId) ? process : null;
    }

    private boolean ownedBy(ManagedProcess process, UUID ownerId) {
        if (process == null) {
            return false;
        }
        return ownerId == null ? process.ownerId() == null : ownerId.equals(process.ownerId());
    }

    private String optionalString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private String fallbackString(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback;
    }

    private int boundedInt(Object value, int fallback, int min, int max) {
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                parsed = Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private boolean optionalBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private double elapsedSeconds(long startedAt) {
        return Math.round(((System.currentTimeMillis() - startedAt) / 1000.0) * 10.0) / 10.0;
    }

    private String truncateOutput(String output) {
        if (output == null || output.length() <= MAX_COMMAND_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(output.length() - MAX_COMMAND_OUTPUT_CHARS);
    }

    private String truncateText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    /**
     * Processos que este usuário mantém rodando.
     *
     * <p>Existe para a extensão no telefone poder listar antes de pedir log: sem isto ela teria de
     * adivinhar o id de um processo que nem sabe existir.
     *
     * <p>Filtra por dono. Sem o filtro, o telefone de um usuário enxergaria o build de outro — e o
     * id vazado bastaria para ler a saída inteira pelo endpoint de log.
     */
    @GetMapping("/processes")
    public ResponseEntity<BaseResponse<JsonNode>> listProcesses(@AuthenticationPrincipal AuthPrincipal principal) {
        UUID owner = principal != null ? principal.userId() : null;
        ArrayNode processes = mapper.createArrayNode();
        for (ManagedProcess managed : managedProcesses.values()) {
            if (owner != null && !owner.equals(managed.ownerId())) {
                continue;
            }
            ObjectNode entry = processes.addObject();
            entry.put("id", managed.processId());
            entry.put("command", String.join(" ", managed.command()));
            entry.put("startedAt", managed.startedAt());
            entry.put("running", managed.process().isAlive());
        }
        return ApiResponses.ok(mapper.createObjectNode().set("processes", processes));
    }

    @GetMapping("/processes/{processId}/logs")
    public ResponseEntity<BaseResponse<JsonNode>> processLogs(
            @PathVariable String processId,
            @RequestParam(defaultValue = "8000") int maxChars,
            @AuthenticationPrincipal AuthPrincipal principal) {
        try {
            JsonNode result = toolExecutionContext.call(
                    new Context(principal == null ? null : principal.userId(), null, ""),
                    () -> executeTerminalLogs(Map.of("processId", processId, "maxChars", maxChars)));
            if (result.has("error")) {
                throw new IllegalArgumentException(result.path("error").asText("Could not read process logs."));
            }
            return ApiResponses.ok(result);
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new ApiServiceException("Could not read process logs.", exception);
        }
    }

    @PostMapping("/processes/{processId}/stop")
    public ResponseEntity<BaseResponse<JsonNode>> stopProcess(
            @PathVariable String processId, @AuthenticationPrincipal AuthPrincipal principal) {
        try {
            JsonNode result = toolExecutionContext.call(
                    new Context(principal == null ? null : principal.userId(), null, ""),
                    () -> executeTerminalStop(Map.of("processId", processId)));
            if (result.has("error")) {
                throw new IllegalArgumentException(result.path("error").asText("Could not stop process."));
            }
            return ApiResponses.ok(result);
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new ApiServiceException("Could not stop process.", exception);
        }
    }
}
