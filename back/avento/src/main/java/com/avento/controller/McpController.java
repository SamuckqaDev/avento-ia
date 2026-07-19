package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.api.exception.ApiServiceException;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.DocumentReaderService;
import com.avento.service.FileBackupService;
import com.avento.service.GeneratedMediaAssetService;
import com.avento.service.ImageGenerationJobService;
import com.avento.service.NotificationService;
import com.avento.service.PdfGenerationService;
import com.avento.service.SystemAutomationService;
import com.avento.service.VideoGenerationJobService;
import com.avento.service.WorkspaceAccessService;
import com.avento.service.dto.BackupEntry;
import com.avento.service.dto.ConnectionResult;
import com.avento.service.dto.Context;
import com.avento.service.dto.DirectoryBackupEntry;
import com.avento.service.dto.DocumentReadResult;
import com.avento.service.dto.MacApplication;
import com.avento.service.dto.SystemActionResult;
import com.avento.service.dto.ToolDefinition;
import com.avento.service.mcp.McpClientManager;
import com.avento.service.mcp.McpServerCatalogService;
import com.avento.service.support.CommandAllowlists;
import com.avento.service.tools.LocalToolNames;
import com.avento.service.tools.ToolExecutionContext;
import com.avento.service.tools.ToolProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp")
public class McpController implements ToolProvider {

    private final ObjectMapper mapper = new ObjectMapper();
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
    private GeneratedMediaAssetService generatedMediaAssetService;

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
        allTools.add(tool(
                "directory_tree",
                "Lista a arvore de arquivos e pastas dentro de um workspace autorizado. Use antes de criar ou editar quando precisar entender a estrutura.",
                Map.of(
                        "path", stringProperty("Diretorio absoluto dentro de [Workspace Roots]."),
                        "maxDepth", numberProperty("Profundidade maxima opcional, padrao 4.")),
                List.of("path")));
        allTools.add(tool(
                "read_file",
                "Le o conteudo de um arquivo dentro de um workspace autorizado.",
                Map.of("path", stringProperty("Caminho absoluto do arquivo autorizado.")),
                List.of("path")));
        allTools.add(tool(
                "read_document",
                "Le documentos locais dentro de um workspace autorizado. Converte PDF, Word, Excel, PowerPoint, EPUB, ZIP, imagens com OCR, audio e formatos de texto para Markdown usando MarkItDown local.",
                Map.of("path", stringProperty("Caminho absoluto do documento dentro de um workspace autorizado.")),
                List.of("path")));
        allTools.add(tool(
                "list_mcp_servers",
                "Lista servidores MCP locais disponiveis, configuracao ausente e estado da conexao. Use para descobrir capacidades antes de conectar.",
                Map.of(
                        "projectPaths",
                        arrayProperty("Workspaces absolutos opcionais para validar servidores de arquivos e Git.")),
                List.of()));
        allTools.add(tool(
                "connect_mcp_server",
                "Conecta um servidor do catalogo sob demanda. IDs: filesystem, markitdown, memory, sequential-thinking, time, desktop-commander, macos-automator, apple, playwright, chrome-devtools, puppeteer, fetch, searxng, git, dbhub, docker-gateway.",
                Map.of(
                        "serverId", stringProperty("ID exato do servidor no catalogo."),
                        "projectPaths", arrayProperty("Workspaces absolutos necessarios para filesystem e git.")),
                List.of("serverId")));
        allTools.add(tool(
                "disconnect_mcp_server",
                "Desconecta um servidor MCP do catalogo pelo ID.",
                Map.of("serverId", stringProperty("ID exato do servidor conectado.")),
                List.of("serverId")));
        allTools.add(tool(
                "write_file",
                "Cria ou sobrescreve um arquivo dentro de um workspace autorizado. Cria diretorios pais quando necessario e gera backup antes de sobrescrever.",
                Map.of(
                        "path", stringProperty("Caminho absoluto do arquivo a criar ou sobrescrever."),
                        "content", stringProperty("Conteudo completo que deve ser salvo no arquivo.")),
                List.of("path", "content")));
        allTools.add(tool(
                "edit_file",
                "Substitui um trecho exato dentro de um arquivo existente, sem reescrever o arquivo inteiro. Prefira esta ferramenta a write_file quando o arquivo ja existe e a mudanca e pontual. old_string precisa aparecer exatamente uma vez no arquivo (inclua linhas de contexto ao redor para garantir isso), a menos que replace_all seja true. Gera backup antes de aplicar.",
                Map.of(
                        "path", stringProperty("Caminho absoluto do arquivo existente a editar."),
                        "old_string",
                                stringProperty(
                                        "Trecho exato do conteudo atual do arquivo a ser substituido, incluindo indentacao e contexto suficiente para ser unico."),
                        "new_string",
                                stringProperty(
                                        "Trecho que deve substituir old_string. Pode ser vazio para apagar o trecho."),
                        "replace_all",
                                booleanProperty(
                                        "Se true, substitui todas as ocorrencias de old_string em vez de exigir ocorrencia unica. Padrao false.")),
                List.of("path", "old_string", "new_string")));
        allTools.add(tool(
                "delete_file",
                "Remove um arquivo dentro de um workspace autorizado. Gera backup antes de apagar e exige aprovacao do usuario.",
                Map.of("path", stringProperty("Caminho absoluto do arquivo autorizado a remover.")),
                List.of("path")));
        allTools.add(tool(
                "delete_directory",
                "Remove uma pasta inteira e todo o seu conteudo dentro de um workspace autorizado. Gera backup"
                        + " antes de apagar quando a pasta tem ate 5000 arquivos; acima disso a exclusao roda sem"
                        + " backup (ex.: pastas com node_modules). Sempre exige aprovacao do usuario, mesmo com um"
                        + " plano ja aprovado. Recusa apagar a raiz de um workspace inteiro.",
                Map.of("path", stringProperty("Caminho absoluto da pasta autorizada a remover, com todo o conteudo.")),
                List.of("path")));
        allTools.add(tool(
                "create_directory",
                "Cria um diretorio dentro de um workspace autorizado, incluindo pais ausentes.",
                Map.of("path", stringProperty("Caminho absoluto do diretorio a criar.")),
                List.of("path")));
        allTools.add(tool(
                "search_files",
                "Procura arquivos por nome dentro de um workspace autorizado, ignorando pastas pesadas como node_modules, .git, build e target.",
                Map.of(
                        "path", stringProperty("Diretorio absoluto dentro de [Workspace Roots]."),
                        "pattern", stringProperty("Texto a procurar no nome do arquivo ou pasta."),
                        "maxResults", numberProperty("Quantidade maxima opcional de resultados, padrao 50.")),
                List.of("path", "pattern")));
        allTools.add(tool(
                "create_vite_project",
                "Cria um projeto novo com Vite dentro de um workspace autorizado. Use quando o usuario pedir para criar projeto React/Vite, Vue/Vite etc. Para React com TypeScript, use template react-ts.",
                Map.of(
                        "path",
                                stringProperty(
                                        "Diretorio absoluto autorizado onde o projeto sera criado. Use a raiz do workspace quando o usuario pedir para criar dentro dela."),
                        "projectName",
                                stringProperty(
                                        "Nome da pasta do projeto. Use . apenas quando a pasta path ja for o diretorio vazio do projeto."),
                        "template",
                                stringProperty(
                                        "Template Vite. Exemplos: react-ts, react, react-swc-ts, vue-ts, vanilla-ts."),
                        "installDependencies",
                                booleanProperty(
                                        "Se true, roda npm install apos criar o projeto. Padrao false para evitar travar maquinas lentas.")),
                List.of("path", "projectName", "template")));
        allTools.add(tool(
                "list_macos_apps",
                "Lista os aplicativos instalados no macOS em /Applications, ~/Applications e pastas de sistema. Use quando o usuario pedir todos os apps do Mac, lista de aplicativos, ou procurar um app instalado pelo nome.",
                Map.of(
                        "query",
                        stringProperty("Texto opcional para filtrar por nome do aplicativo, por exemplo Antigravity.")),
                List.of()));
        allTools.add(tool(
                "open_app",
                "Abre um aplicativo instalado no macOS pelo nome. Use para pedidos como abrir VS Code, Finder, Terminal, navegador ou outro app local.",
                Map.of(
                        "appName",
                        stringProperty(
                                "Nome do aplicativo macOS, por exemplo Visual Studio Code, Finder, Terminal ou Safari.")),
                List.of("appName")));
        allTools.add(tool(
                "close_app",
                "Fecha um aplicativo aberto no macOS pelo nome usando AppleScript. Use para pedidos como fechar VS Code, Finder, Terminal, Safari ou Chrome. Nao use terminal_stop para apps abertos por open_app.",
                Map.of(
                        "appName",
                        stringProperty(
                                "Nome do aplicativo macOS, por exemplo Visual Studio Code, Finder, Terminal ou Safari.")),
                List.of("appName")));
        allTools.add(tool(
                "open_browser_tab",
                "Abre uma nova aba em um navegador macOS, como Brave Browser, Google Chrome ou Safari. Use para pedidos como nova aba no Brave ou abrir nova guia no navegador.",
                Map.of(
                        "browserName",
                                stringProperty(
                                        "Nome do navegador macOS, por exemplo Brave Browser, Google Chrome ou Safari."),
                        "url",
                                stringProperty(
                                        "URL http/https opcional para abrir na nova aba. Se ausente, abre aba em branco/pagina inicial.")),
                List.of("browserName")));
        allTools.add(tool(
                "close_browser_tab",
                "Fecha somente a aba ativa de um navegador macOS, sem encerrar o aplicativo. Use para pedidos como fechar aba, fechar guia ou fechar a aba da pesquisa. Nao use close_app para fechar abas.",
                Map.of(
                        "browserName",
                        stringProperty("Nome do navegador macOS, por exemplo Brave Browser, Google Chrome ou Safari.")),
                List.of("browserName")));
        allTools.add(tool(
                "open_url",
                "Abre uma URL http ou https no navegador padrao do sistema.",
                Map.of("url", stringProperty("URL absoluta começando com http:// ou https://.")),
                List.of("url")));
        allTools.add(tool(
                "open_path",
                "Abre um arquivo ou pasta existente dentro de um workspace autorizado usando o app padrao do sistema.",
                Map.of("path", stringProperty("Caminho absoluto existente dentro de um workspace autorizado.")),
                List.of("path")));
        allTools.add(tool(
                "reveal_in_finder",
                "Mostra um arquivo ou pasta existente dentro de um workspace autorizado no Finder.",
                Map.of("path", stringProperty("Caminho absoluto existente dentro de um workspace autorizado.")),
                List.of("path")));
        allTools.add(tool(
                "run_shortcut",
                "Executa um atalho do app Shortcuts do macOS pelo nome. Use apenas quando o usuario pedir explicitamente um atalho existente.",
                Map.of("shortcutName", stringProperty("Nome exato do atalho no app Shortcuts.")),
                List.of("shortcutName")));
        allTools.add(tool(
                "capture_screen",
                "Captura um screenshot da tela atual no macOS e salva em Pictures/Avento Screenshots. Use apenas quando o usuario pedir explicitamente para tirar print/screenshot da tela.",
                Map.of(),
                List.of()));
        allTools.add(tool(
                "generate_pdf",
                "Gera um documento PDF a partir de conteúdo Markdown ou HTML e salva na pasta de media.",
                Map.of(
                        "title", stringProperty("Título do documento PDF."),
                        "markdown", stringProperty("Conteúdo em Markdown para converter."),
                        "html", stringProperty("Conteúdo HTML direto se não usar markdown.")),
                List.of("title")));
        allTools.add(tool(
                "generate_image",
                "Gera uma imagem local usando o modelo de imagem selecionado no header. Pode usar ComfyUI ou Ollama e salva em Pictures/Avento Generated Images. Use quando o usuario pedir para criar/gerar uma imagem, arte, foto, ilustração, mockup visual ou algo parecido.",
                Map.ofEntries(
                        Map.entry(
                                "prompt",
                                stringProperty(
                                        "Prompt detalhado da imagem a gerar. Preserve o idioma e descreva estilo, assunto, composição e detalhes visuais.")),
                        Map.entry(
                                "model",
                                stringProperty(
                                        "Modelo opcional. Use um nome Ollama ou um checkpoint ComfyUI com prefixo comfyui:.")),
                        Map.entry("size", stringProperty("Tamanho opcional em pixels.")),
                        Map.entry("qualityPreset", stringProperty("Qualidade: draft, balanced ou quality.")),
                        Map.entry("aspectRatio", stringProperty("Proporção: square, portrait ou landscape.")),
                        Map.entry(
                                "subjectType",
                                stringProperty(
                                        "Tipo principal escolhido na interface: auto, person, object, environment, vehicle ou animal.")),
                        Map.entry("seed", numberProperty("Seed opcional para repetir uma composição.")),
                        Map.entry(
                                "subjectCount",
                                numberProperty("Quantidade exata de sujeitos; zero detecta pelo prompt.")),
                        Map.entry("enhancePrompt", booleanProperty("Melhora determinística de composição e anatomia.")),
                        Map.entry(
                                "refinementEnabled",
                                booleanProperty("Ativa segundo passe de refinamento em resolução maior.")),
                        Map.entry("refinementStrength", numberProperty("Denoise do segundo passe, entre 0.15 e 0.55.")),
                        Map.entry("detailMode", stringProperty("Detalhamento opcional: none, face ou face-hands.")),
                        Map.entry("cfgScale", numberProperty("CFG opcional entre 1 e 12.")),
                        Map.entry(
                                "referenceImageDataUrl",
                                stringProperty(
                                        "Imagem geral opcional para preservar composição e objetos via img2img.")),
                        Map.entry(
                                "referenceStrength",
                                numberProperty("Fidelidade à imagem geral de referência, entre 0.1 e 0.9.")),
                        Map.entry("poseReferenceDataUrl", stringProperty("Imagem de pose opcional em data URL.")),
                        Map.entry("poseStrength", numberProperty("Força da referência de pose, entre 0.2 e 1.5."))),
                List.of("prompt")));
        allTools.add(tool(
                "generate_video",
                "Gera um vídeo curto local via ComfyUI. No modo auto, usa a imagem mais recente do chat como quadro"
                        + " inicial quando houver uma; use mode=text somente quando o vídeo deve ser criado do zero."
                        + " Salva em Pictures/Avento Generated Images e pode levar vários minutos.",
                Map.of(
                        "prompt",
                                stringProperty(
                                        "Prompt detalhado do vídeo a gerar: assunto, movimento/ação, estilo, câmera."),
                        "size",
                                stringProperty(
                                        "Tamanho opcional em pixels, por exemplo 832x480. O padrão auto preserva a proporção da imagem."),
                        "seconds", numberProperty("Duração opcional em segundos, entre 1 e 5. Padrao 2."),
                        "mode",
                                stringProperty(
                                        "Modo: auto usa a última imagem do chat se existir; image exige essa imagem; text cria do zero.")),
                List.of("prompt")));
        allTools.add(tool(
                "terminal_run",
                "Executa um comando de terminal permitido e curto dentro de um workspace autorizado. Use para npm"
                        + " create vite@latest, npm install, npm run build/test/lint/typecheck/validate, mvn"
                        + " test/package/verify, git status/diff/log, docker compose ps/down/logs, mkdir -p <caminho"
                        + " relativo> para criar pasta e rm -rf <caminho relativo> para apagar arquivo ou pasta (o"
                        + " alvo desses dois tem que ser relativo ao path informado, sem .. e sem comecar com / ou"
                        + " ~; para apagar uma pasta inteira com backup e confirmacao sempre exigida, prefira a"
                        + " ferramenta delete_directory).",
                Map.of(
                        "path", stringProperty("Diretorio absoluto autorizado onde o comando deve rodar."),
                        "command", stringProperty("Comando exato permitido a executar."),
                        "timeoutSeconds",
                                numberProperty(
                                        "Timeout opcional em segundos, maximo 300. Padrao 240 para comandos npm/npx"
                                                + " (instalam dependencias e podem demorar), 120 para os demais. Em"
                                                + " scaffolds pesados (ex.: npx @nestjs/cli new, npm create), passe"
                                                + " 300 explicitamente.")),
                List.of("path", "command")));
        allTools.add(tool(
                "terminal_start",
                "Inicia um processo longo permitido dentro de um workspace autorizado e retorna um processId. Use para npm run dev ou mvn spring-boot:run.",
                Map.of(
                        "path", stringProperty("Diretorio absoluto autorizado onde o processo deve rodar."),
                        "command",
                                stringProperty(
                                        "Comando longo permitido a iniciar. Exemplos: npm run dev, mvn spring-boot:run.")),
                List.of("path", "command")));
        allTools.add(tool("terminal_list", "Lista processos longos iniciados pelo Avento.", Map.of(), List.of()));
        allTools.add(tool(
                "terminal_logs",
                "Retorna logs recentes de um processo iniciado pelo Avento.",
                Map.of(
                        "processId", stringProperty("ID retornado por terminal_start."),
                        "maxChars", numberProperty("Quantidade maxima opcional de caracteres, padrao 8000.")),
                List.of("processId")));
        allTools.add(tool(
                "terminal_stop",
                "Para um processo iniciado pelo Avento usando o processId interno.",
                Map.of("processId", stringProperty("ID retornado por terminal_start.")),
                List.of("processId")));
    }

    private ObjectNode tool(
            String name, String description, Map<String, ObjectNode> properties, List<String> required) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        properties.forEach(props::set);
        schema.set("properties", props);

        ArrayNode requiredNode = mapper.createArrayNode();
        required.forEach(requiredNode::add);
        schema.set("required", requiredNode);
        schema.put("additionalProperties", false);

        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode stringProperty(String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private ObjectNode numberProperty(String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "number");
        property.put("description", description);
        return property;
    }

    private ObjectNode booleanProperty(String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "boolean");
        property.put("description", description);
        return property;
    }

    private ObjectNode arrayProperty(String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "array");
        property.put("description", description);
        property.set("items", stringProperty("Caminho absoluto."));
        return property;
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
            case "terminal_run" -> executeTerminalRun(payload);
            case "terminal_start" -> executeTerminalStart(payload);
            case "terminal_list" -> executeTerminalList();
            case "terminal_logs" -> executeTerminalLogs(payload);
            case "terminal_stop" -> executeTerminalStop(payload);
            default -> mapper.createObjectNode().put("error", "Unknown local tool: " + name);
        };
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

    private JsonNode executeTerminalStart(Map<String, Object> payload) throws IOException {
        Path workingDirectory = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        if (!Files.isDirectory(workingDirectory)) {
            return mapper.createObjectNode().put("error", "Command path must be a directory: " + workingDirectory);
        }

        String commandText = requiredString(payload, "command").trim();
        List<String> command = allowedLongRunningCommand(commandText);
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

        BackupEntry backup = fileBackupService.backupBeforeWrite(file);
        Files.writeString(file, content, StandardCharsets.UTF_8);

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "success");
        result.put("path", file.toString());
        result.put("backupId", backup.id());
        result.put("bytesWritten", Files.size(file));
        return toolResult(result);
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

        BackupEntry backup = fileBackupService.backupBeforeWrite(file);
        Files.writeString(file, updatedContent, StandardCharsets.UTF_8);

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

        BackupEntry backup = fileBackupService.backupBeforeWrite(file);
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

        DirectoryBackupEntry backup = fileBackupService.backupDirectoryBeforeDelete(directory);
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
        Files.createDirectories(directory);

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
        Path workingDirectory = workspaceAccessService.requireAuthorized(requiredString(payload, "path"));
        if (!Files.isDirectory(workingDirectory)) {
            return mapper.createObjectNode().put("error", "Command path must be a directory: " + workingDirectory);
        }

        String commandText = requiredString(payload, "command").trim();
        List<String> command = allowedTerminalCommand(commandText);
        if (command.isEmpty()) {
            return mapper.createObjectNode().put("error", "Command is not allowed: " + commandText);
        }

        int timeoutSeconds =
                boundedInt(payload.get("timeoutSeconds"), defaultTerminalTimeoutSeconds(commandText), 1, 300);
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

    // npm/npx sao liberados por comando inteiro, nao mais por subcomando
    // especifico — o usuario pediu explicitamente para nao precisar manter
    // uma allowlist de cada coisa que o npm sabe fazer (ex.: nest, create-*,
    // scripts customizados de package.json). O ProcessBuilder abaixo recebe
    // List<String> direto (sem invocar shell), entao nao ha risco de
    // injecao por ; && | etc. — split de espaco e seguro aqui. A ferramenta
    // continua exigindo aprovacao visual antes de rodar (ToolCapabilityRegistry).
    private static final Pattern NPM_OR_NPX_COMMAND = Pattern.compile("^(npm|npx)\\s+\\S.*$");

    private List<String> allowedTerminalCommand(String commandText) {
        String command = commandText.trim().replaceAll("\\s+", " ");

        if (NPM_OR_NPX_COMMAND.matcher(command).matches()) {
            return List.of(command.split(" "));
        }

        Matcher mvnMatcher = Pattern.compile("^mvn (\\S+)$").matcher(command);
        if (mvnMatcher.matches() && CommandAllowlists.MAVEN_GOALS.contains(mvnMatcher.group(1))) {
            return List.of("mvn", mvnMatcher.group(1));
        }

        Matcher rmMatcher = Pattern.compile("^rm -rf (\\S+)$").matcher(command);
        if (rmMatcher.matches() && isSafeRelativePathTarget(rmMatcher.group(1))) {
            return List.of("rm", "-rf", rmMatcher.group(1));
        }

        Matcher mkdirMatcher = Pattern.compile("^mkdir -p (\\S+)$").matcher(command);
        if (mkdirMatcher.matches() && isSafeRelativePathTarget(mkdirMatcher.group(1))) {
            return List.of("mkdir", "-p", mkdirMatcher.group(1));
        }

        if (command.equals("git status")) {
            return List.of("git", "status");
        }
        if (command.equals("git diff")) {
            return List.of("git", "diff");
        }
        if (command.equals("git log --oneline")) {
            return List.of("git", "log", "--oneline");
        }
        if (command.equals("docker compose ps")) {
            return List.of("docker", "compose", "ps");
        }
        if (command.equals("docker compose up -d")) {
            return List.of("docker", "compose", "up", "-d");
        }
        if (command.equals("docker compose down")) {
            return List.of("docker", "compose", "down");
        }
        if (command.matches("^docker compose logs --tail=\\d+$")) {
            String tail = command.substring("docker compose logs ".length());
            return List.of("docker", "compose", "logs", tail);
        }

        return List.of();
    }

    // npm/npx installs (create-*, @scope/cli new, install) routinely take longer than 120s on a
    // cold npx cache or slow registry — 120s was timing out real scaffolds like
    // `npx @nestjs/cli new .`. Other commands here (git/mvn/docker) are fast, so they keep the
    // short default.
    private int defaultTerminalTimeoutSeconds(String commandText) {
        return NPM_OR_NPX_COMMAND.matcher(commandText.trim()).matches() ? 240 : 120;
    }

    // rm -rf and mkdir -p both run inside the workspace directory ProcessBuilder was given (see
    // executeTerminalRun), so the target must resolve relative to it — an absolute path or a ".."
    // segment could escape the authorized workspace root entirely.
    private boolean isSafeRelativePathTarget(String target) {
        if (target.startsWith("/") || target.startsWith("~")) {
            return false;
        }
        for (String segment : target.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private List<String> allowedLongRunningCommand(String commandText) {
        String command = commandText.trim().replaceAll("\\s+", " ");
        // Mesma regra ampla do terminal_run — cobre scripts de dev/watch que
        // variam de nome por projeto (start:dev, dev, watch...), nao só "dev".
        if (NPM_OR_NPX_COMMAND.matcher(command).matches()) {
            return List.of(command.split(" "));
        }
        if (command.equals("mvn spring-boot:run")) {
            return List.of("mvn", "spring-boot:run");
        }
        return List.of();
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
