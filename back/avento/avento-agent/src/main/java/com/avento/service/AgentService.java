package com.avento.service;

import com.avento.service.dto.ApprovalMemory;
import com.avento.service.dto.ApprovalVoiceCommand;
import com.avento.service.dto.LocalModelInfo;
import com.avento.service.dto.PendingToolExecution;
import com.avento.service.dto.Skill;
import com.avento.service.dto.SkillResolution;
import com.avento.service.dto.ToolCall;
import com.avento.service.image.ImageGenerationOptions;
import com.avento.service.intent.AgentIntent;
import com.avento.service.intent.IntentProfile;
import com.avento.service.intent.IntentRouter;
import com.avento.service.intent.VisualIntentClassifier;
import com.avento.service.orchestration.AgentExecutionEngine;
import com.avento.service.support.HeuristicWordLists;
import com.avento.service.support.SkillRegistry;
import com.avento.service.tools.ToolCapabilityRegistry;
import com.avento.service.tools.ToolExecutionGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class AgentService implements AgentExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);
    // Fallback usado somente quando o pedido chega sem modelo ou com um modelo que nao e de chat
    // (ex.: um modelo de imagem no seletor errado). A escolha do usuario no frontend sempre vence
    // quando valida; este valor e configuravel via avento.agent.default-model /
    // AVENTO_AGENT_DEFAULT_MODEL, nunca fixo em codigo.
    private final String defaultChatModel;
    private final String defaultVisionModel;
    // Prefixos de modelos que suportam o campo dedicado de thinking; ver thinkingEnabledForRequest.
    private final Set<String> thinkingCapableModels;
    private static final long HEAVY_MODEL_BYTES = 4_000_000_000L;
    private static final Pattern TEXTUAL_FUNCTION_PATTERN =
            Pattern.compile("\\{\\s*function\\s+<([A-Za-z0-9_-]+)>\\s+(\\{.*})\\s*}", Pattern.DOTALL);
    // The model-facing instructions live in editable resource files rather than a
    // large Java string. Keep this loader as the single authoritative composition
    // point because frontend system messages are intentionally discarded later.
    private static final String AGENT_SYSTEM_PROMPT = loadAgentInstructions();
    private static final String AVENTO_PRODUCT_FACTS = loadAgentResource("agent/instructions/product.md");
    private static final String IDENTITY_RESPONSE_PT = loadAgentResource("agent/responses/identity-pt.md");
    private static final String CAPABILITY_RESPONSE_PT = loadAgentResource("agent/responses/capabilities-pt.md");

    private static String loadAgentInstructions() {
        List<String> resources = List.of(
                "agent/instructions/identity.md",
                "agent/instructions/personality.md",
                "agent/instructions/context.md",
                "agent/instructions/tools.md",
                "agent/instructions/execution.md");
        StringBuilder instructions = new StringBuilder();
        for (String resource : resources) {
            instructions.append(loadAgentResource(resource)).append("\n\n");
        }
        return instructions.toString().trim();
    }

    private static String loadAgentResource(String resource) {
        try {
            ClassPathResource file = new ClassPathResource(resource);
            try (var inputStream = file.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível carregar a instrução do agente: " + resource, exception);
        }
    }

    // Palavras-chave editaveis sem recompilar: ver
    // src/main/resources/agent/heuristics/*.txt
    private static final Set<String> CASUAL_PHRASES =
            Set.copyOf(HeuristicWordLists.loadLines("agent/heuristics/casual-phrases.txt"));
    private static final Set<String> PROJECT_ACTION_WORDS =
            Set.copyOf(HeuristicWordLists.loadLines("agent/heuristics/project-action-words.txt"));
    private static final Map<String, List<String>> IMAGE_PROMPT_SIGNALS =
            HeuristicWordLists.loadSections("agent/heuristics/image-prompt-signals.txt");
    private static final Set<String> TERMINAL_TOOLS =
            Set.of("terminal_run", "terminal_start", "terminal_logs", "terminal_stop");
    private static final int MAX_ACTIVITY_OUTPUT_CHARS = 4000;

    private final ToolExecutionGateway toolGateway;
    private final ToolCapabilityRegistry toolRegistry;
    private final IntentRouter intentRouter;
    private final SystemAutomationService systemAutomationService;
    private final AgentPermissionService permissionService;
    private final AgentTimelineService timelineService;
    private final SkillRegistry skillRegistry;
    private final TokenUsageService tokenUsageService;
    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final int maxToolRounds;
    private final int maxToolCalls;
    private final int numCtx;
    private final int numPredict;
    private final double temperature;
    private final double topP;
    private final int topK;
    private final double repeatPenalty;
    private final boolean enableThinking;
    private final String keepAlive;
    private final int maxToolsPerRequest;
    private final boolean exposeAllTools;
    private final Set<String> projectToolkit;

    // Teto de ferramentas fora do kit que a intencao da mensagem pode trazer junto em chats
    // com projeto. Pequeno de proposito: extras raros preservam o prefixo estavel do prompt
    // na maioria das mensagens e evitam voltar ao custo de dezenas de schemas por rodada.
    private static final int PROJECT_TOOLKIT_EXTRA_LIMIT = 6;
    private final int maxModelMessages;
    private final int maxMessageContentChars;
    private final int maxToolResultChars;
    private final int maxTotalMessageContentChars;

    @Value("${avento.agent.policy-mode:maximum}")
    private String policyMode;

    @Value("${avento.agent.policy-override-dir:}")
    private String policyOverrideDirectory;

    // Per-run tool allow-list (agent mode). Optional field injection so the constructor and the
    // tests that build AgentService directly stay untouched; null means "no restriction".
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.avento.service.tools.RunToolPolicyRegistry toolPolicyRegistry;

    // Mesma injecao opcional. Serve so para AVISAR: o chat fala direto com o Ollama (/api/chat, corpo
    // no formato nativo), entao escolher Gemini na tela nao muda para onde a requisicao vai. Sem
    // aviso, o usuario recebe o modelo local achando que falou com a nuvem.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.avento.service.provider.ModelProviderService modelProviderService;

    // Lista, nao um unico bean: o despacho escolhe pelo TIPO do provedor ativo. Com um so, um
    // usuario em OPENAI_COMPATIBLE seria atendido pela implementacao do Gemini.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private java.util.List<com.avento.service.provider.CloudChatProvider> cloudChatProviders;

    // Transportes de modelo. Quem tem as ferramentas e o AGENTE: o laco de rodadas monta o toolset,
    // pede aprovacao, prende ao sandbox e executa — igual para todo provedor. O transporte so
    // traduz o dialeto da chamada, e por isso o Gemini passa a enxergar arquivo, terminal, MCP e RAG.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private java.util.List<com.avento.service.provider.ModelTransport> modelTransports;

    // Progressive tool discovery (activate_tools persists per run in Redis). Optional for the same
    // reason as the field above: tests build AgentService through the constructor.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.avento.service.tools.ToolCatalogService toolCatalogService;

    private final UserSettingsService userSettingsService;
    private final UserMemoryService userMemoryService;
    private final MemoryExtractionService memoryExtractionService;
    private final PendingToolApprovalService pendingApprovalService;
    private final VisualIntentClassifier visualIntentClassifier;

    @Value("${avento.image.default-model:comfyui:RealVisXL_V5.0_fp16.safetensors}")
    private String defaultImageModel;

    // Ferramentas que sempre pedem confirmação própria, mesmo dentro de um plano
    // já aprovado pelo usuário nesta mesma resposta (ver planApprovedRuns).
    private static final Set<String> ALWAYS_CONFIRM_TOOLS =
            Set.of("delete_file", "delete_directory", "terminal_stop", "close_app");
    // terminal_run/terminal_start em si não estão em ALWAYS_CONFIRM_TOOLS (cobrem
    // comandos inofensivos como "npm test"), mas um rm -rf especificamente é tão
    // destrutivo quanto delete_file/delete_directory e não deve ser coberto pela
    // aprovação de plano em lote — ver isAlwaysConfirmToolCall.
    private static final Pattern DESTRUCTIVE_TERMINAL_COMMAND = Pattern.compile("^rm -rf .*$");
    // /nome argumento — invocação explícita de skill (agent/skills/*.md via SkillRegistry).
    private static final Pattern SKILL_INVOCATION = Pattern.compile("^/(\\S+)(?:\\s+([\\s\\S]*))?$");

    private final Map<String, PendingToolExecution> pendingToolExecutions = new ConcurrentHashMap<>();
    // runIds onde o usuário já aprovou um plano de múltiplas ações: as próximas
    // chamadas de ferramenta dessa mesma resposta pulam a aprovação individual,
    // exceto as em ALWAYS_CONFIRM_TOOLS. Um runId é criado por resposta do
    // usuário (ver newRunId()), então isso nunca vaza permissão para uma
    // conversa futura.
    private final Set<String> planApprovedRuns = ConcurrentHashMap.newKeySet();
    private final Set<String> modelsWithoutToolSupport = ConcurrentHashMap.newKeySet();
    private final Map<String, String> latestPendingToolIds = new ConcurrentHashMap<>();
    // An approval can be resolved through two independent paths: the dedicated
    // /api/ai/approvals/{id}/approve endpoint (the UI button) or a chat message
    // whose text
    // matches an approval phrase like "aprovo"/"pode executar"
    // (detectApprovalRequest, e.g. from
    // voice). If both fire for the same approvalId (user speaks "aprovo" while also
    // clicking),
    // pendingToolExecutions.remove() correctly lets only one of them actually run
    // the tool, but
    // the loser used to get a scary "no pending execution found" message. This
    // bounded LRU tracks
    // recently resolved ids so the loser can be told the truth instead.
    private static final int MAX_RESOLVED_APPROVAL_IDS = 200;
    private final Map<String, Boolean> resolvedApprovalIds =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_RESOLVED_APPROVAL_IDS;
                }
            });

    public AgentService(
            ToolExecutionGateway toolGateway,
            ToolCapabilityRegistry toolRegistry,
            IntentRouter intentRouter,
            SystemAutomationService systemAutomationService,
            AgentPermissionService permissionService,
            AgentTimelineService timelineService,
            SkillRegistry skillRegistry,
            TokenUsageService tokenUsageService,
            UserSettingsService userSettingsService,
            UserMemoryService userMemoryService,
            MemoryExtractionService memoryExtractionService,
            PendingToolApprovalService pendingApprovalService,
            VisualIntentClassifier visualIntentClassifier,
            ObjectMapper mapper,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${avento.agent.max-tool-rounds:6}") int maxToolRounds,
            @Value("${avento.agent.max-tool-calls:16}") int maxToolCalls,
            @Value("${avento.agent.num-ctx:16384}") int numCtx,
            @Value("${avento.agent.num-predict:4096}") int numPredict,
            @Value("${avento.agent.temperature:0.15}") double temperature,
            @Value("${avento.agent.top-p:0.9}") double topP,
            @Value("${avento.agent.top-k:30}") int topK,
            @Value("${avento.agent.repeat-penalty:1.08}") double repeatPenalty,
            @Value("${avento.agent.enable-thinking:true}") boolean enableThinking,
            @Value("${avento.agent.keep-alive:30m}") String keepAlive,
            @Value("${avento.agent.max-tools-per-request:12}") int maxToolsPerRequest,
            @Value("${avento.agent.expose-all-tools:false}") boolean exposeAllTools,
            @Value("${avento.agent.project-toolkit:directory_tree,read_file,read_document,write_file,edit_file,"
                            + "delete_file,delete_directory,create_directory,search_files,find_symbol,verify_project,terminal_run,"
                            + "terminal_start,terminal_logs}")
                    String projectToolkit,
            @Value("${avento.agent.max-model-messages:10}") int maxModelMessages,
            @Value("${avento.agent.max-message-content-chars:6000}") int maxMessageContentChars,
            @Value("${avento.agent.max-tool-result-chars:4000}") int maxToolResultChars,
            @Value("${avento.agent.max-total-message-content-chars:14000}") int maxTotalMessageContentChars,
            @Value("${avento.agent.default-model:granite4.1:8b}") String defaultChatModel,
            @Value("${avento.agent.thinking-capable-models:qwen3,qwen3.5,gemma4,deepseek}")
                    String thinkingCapableModels,
            @Value("${avento.agent.vision-model:qwen2.5vl:7b}") String defaultVisionModel) {
        this.toolGateway = toolGateway;
        this.toolRegistry = toolRegistry;
        this.intentRouter = intentRouter;
        this.systemAutomationService = systemAutomationService;
        this.permissionService = permissionService;
        this.timelineService = timelineService;
        this.skillRegistry = skillRegistry;
        this.tokenUsageService = tokenUsageService;
        this.userSettingsService = userSettingsService;
        this.userMemoryService = userMemoryService;
        this.memoryExtractionService = memoryExtractionService;
        this.pendingApprovalService = pendingApprovalService;
        this.visualIntentClassifier = visualIntentClassifier;
        this.mapper = mapper;
        this.webClient = WebClient.builder().baseUrl(ollamaBaseUrl).build();
        this.maxToolRounds = maxToolRounds;
        this.maxToolCalls = maxToolCalls;
        this.numCtx = Math.max(2048, numCtx);
        this.numPredict = Math.max(256, Math.min(numPredict, this.numCtx - 1024));
        this.temperature = Math.max(0.0, Math.min(2.0, temperature));
        this.topP = Math.max(0.0, Math.min(1.0, topP));
        this.topK = Math.max(1, topK);
        this.repeatPenalty = Math.max(0.0, repeatPenalty);
        this.enableThinking = enableThinking;
        this.keepAlive = keepAlive;
        this.maxToolsPerRequest = Math.max(ALWAYS_EXPOSED_TOOLS.size(), maxToolsPerRequest);
        this.exposeAllTools = exposeAllTools;
        this.projectToolkit = Set.copyOf(Arrays.stream(projectToolkit.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .toList());
        this.maxModelMessages = Math.max(2, maxModelMessages);
        this.maxMessageContentChars = Math.max(500, maxMessageContentChars);
        this.maxToolResultChars = Math.max(500, maxToolResultChars);
        this.maxTotalMessageContentChars = Math.max(this.maxMessageContentChars, maxTotalMessageContentChars);
        this.defaultChatModel = defaultChatModel;
        this.defaultVisionModel = defaultVisionModel;
        this.thinkingCapableModels = Arrays.stream(thinkingCapableModels.split(","))
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .filter(name -> !name.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Mono<List<String>> getModels() {
        return getModelDetails()
                .map(models -> models.stream().map(LocalModelInfo::name).toList());
    }

    /** Executa uma conclusão textual isolada, sem identidade, heurísticas ou ferramentas do agente. */
    public Mono<String> completeTextOnly(String model, ArrayNode messages, int maxNewTokens) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", normalizeChatModel(model));
        request.set("messages", messages == null ? mapper.createArrayNode() : messages);
        request.put("stream", false);
        request.put("think", false);
        request.put("keep_alive", keepAlive);
        ObjectNode options = request.putObject("options");
        options.put("num_ctx", numCtx);
        options.put("num_predict", Math.max(128, Math.min(maxNewTokens, numPredict)));
        options.put("temperature", 0.1);
        options.put("top_p", topP);
        options.put("top_k", topK);
        options.put("repeat_penalty", repeatPenalty);

        return webClient
                .post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(300))
                .map(response -> response.path("message").path("content").asText(""));
    }

    // Pre-aquece o modelo default e o prefixo estatico do system prompt no cache do Ollama assim que
    // o backend sobe. Sem isso a PRIMEIRA mensagem paga o carregamento a frio (medido: ~58s na 1a vs
    // ~3s nas seguintes, ja com o fix do cache de prompt). Best-effort: roda async fora da thread de
    // boot e nunca derruba a inicializacao — se o Ollama nao estiver pronto, so loga em debug e segue.
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpDefaultModel() {
        try {
            ArrayNode userMessages = mapper.createArrayNode();
            ObjectNode greeting = userMessages.addObject();
            greeting.put("role", "user");
            greeting.put("content", "oi");
            ArrayNode primed = withBackendIdentityPrompt(userMessages, List.of(), null);
            completeTextOnly(defaultChatModel, primed, 16)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            ignored -> logger.info(
                                    "Modelo {} pre-aquecido no boot; a primeira resposta deve vir mais rapida.",
                                    defaultChatModel),
                            error -> logger.debug(
                                    "Pre-aquecimento do modelo falhou; segue normal: {}", error.toString()));
        } catch (RuntimeException exception) {
            logger.debug("Pre-aquecimento nao iniciou: {}", exception.toString());
        }
    }

    public Mono<List<LocalModelInfo>> getModelDetails() {
        return getModelDetails(null);
    }

    /**
     * Lista os modelos oferecidos ao seletor.
     *
     * <p>Com provedor de nuvem ativo, precisa listar os modelos DA NUVEM: mostrar os modelos locais
     * enquanto a conversa vai para o Gemini faz o usuario escolher um nome que nao tem efeito e
     * concluir, com razao, que a escolha de provedor foi ignorada.
     *
     * <p>O endpoint /api/ollama/models ja fazia isso via ModelProviderService, mas a interface chama
     * /api/ai/models/details, que consultava o Ollama direto e nao conhecia provedor nenhum.
     */
    public Mono<List<LocalModelInfo>> getModelDetails(UUID userId) {
        List<LocalModelInfo> cloudModels = cloudModelsFor(userId);
        if (!cloudModels.isEmpty()) {
            return Mono.just(cloudModels);
        }
        return webClient
                .get()
                .uri("/api/tags")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::parseOllamaTags)
                .onErrorResume(error -> getOpenAiCompatibleModelDetails())
                .map(this::sortModels);
    }

    public Mono<List<LocalModelInfo>> getImageModelDetails() {
        return getImageModelDetails(null);
    }

    /**
     * Modelos de imagem oferecidos ao seletor.
     *
     * <p>Com provedor remoto ativo, o seletor de imagem tem de listar os modelos DELE. Oferecer os
     * checkpoints do ComfyUI local enquanto a conversa vai para o Gemini repete o mesmo erro do
     * seletor de chat: um nome que o provedor ativo nao conhece.
     */
    public Mono<List<LocalModelInfo>> getImageModelDetails(UUID userId) {
        List<LocalModelInfo> providerImages = providerImageModelsFor(userId);
        if (!providerImages.isEmpty()) {
            return Mono.just(providerImages);
        }
        return webClient
                .get()
                .uri("/api/tags")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::parseOllamaImageTags)
                .onErrorResume(error -> getOpenAiCompatibleImageModelDetails())
                .map(this::sortModels);
    }

    private Mono<List<LocalModelInfo>> getOpenAiCompatibleModelDetails() {
        return webClient
                .get()
                .uri("/v1/models")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    List<LocalModelInfo> models = new ArrayList<>();
                    if (json.path("data").isArray()) {
                        for (JsonNode model : json.path("data")) {
                            String name = model.path("id").asText("");
                            if (!name.isBlank() && isChatModel(name)) {
                                models.add(new LocalModelInfo(
                                        name,
                                        0L,
                                        "",
                                        inferParameterSize(name),
                                        inferFamily(name),
                                        isRecommendedModel(name),
                                        isHeavyModel(name, 0L, inferParameterSize(name)),
                                        isVisionModel(name, inferFamily(name)),
                                        isPreferredVisionModel(name)));
                            }
                        }
                    }
                    return models;
                })
                .onErrorReturn(new ArrayList<>());
    }

    private Mono<List<LocalModelInfo>> getOpenAiCompatibleImageModelDetails() {
        return webClient
                .get()
                .uri("/v1/models")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    List<LocalModelInfo> models = new ArrayList<>();
                    if (json.path("data").isArray()) {
                        for (JsonNode model : json.path("data")) {
                            String name = model.path("id").asText("");
                            if (!name.isBlank() && isImageModel(name)) {
                                models.add(new LocalModelInfo(
                                        name,
                                        0L,
                                        "",
                                        inferParameterSize(name),
                                        inferFamily(name),
                                        name.equals(defaultImageModel),
                                        isHeavyModel(name, 0L, inferParameterSize(name)),
                                        false,
                                        false));
                            }
                        }
                    }
                    return models;
                })
                .onErrorReturn(new ArrayList<>());
    }

    private List<LocalModelInfo> parseOllamaTags(JsonNode json) {
        List<LocalModelInfo> models = new ArrayList<>();
        if (!json.path("models").isArray()) {
            return models;
        }

        for (JsonNode model : json.path("models")) {
            String name = model.path("name").asText(model.path("model").asText(""));
            if (name.isBlank() || !isChatModel(name)) {
                continue;
            }

            long sizeBytes = model.path("size").asLong(0L);
            String parameterSize =
                    firstNonBlank(model.path("details").path("parameter_size").asText(""), inferParameterSize(name));
            String family = firstNonBlank(model.path("details").path("family").asText(""), inferFamily(name));

            models.add(new LocalModelInfo(
                    name,
                    sizeBytes,
                    formatSize(sizeBytes),
                    parameterSize,
                    family,
                    isRecommendedModel(name),
                    isHeavyModel(name, sizeBytes, parameterSize),
                    isVisionModel(name, family),
                    isPreferredVisionModel(name)));
        }
        return models;
    }

    private List<LocalModelInfo> parseOllamaImageTags(JsonNode json) {
        List<LocalModelInfo> models = new ArrayList<>();
        if (!json.path("models").isArray()) {
            return models;
        }
        for (JsonNode model : json.path("models")) {
            String name = model.path("name").asText(model.path("model").asText(""));
            if (name.isBlank() || !isImageModel(name)) {
                continue;
            }
            long sizeBytes = model.path("size").asLong(0L);
            String parameterSize =
                    firstNonBlank(model.path("details").path("parameter_size").asText(""), inferParameterSize(name));
            models.add(new LocalModelInfo(
                    name,
                    sizeBytes,
                    formatSize(sizeBytes),
                    parameterSize,
                    firstNonBlank(model.path("details").path("family").asText(""), inferFamily(name)),
                    name.equals(defaultImageModel),
                    isHeavyModel(name, sizeBytes, parameterSize),
                    false,
                    false));
        }
        return models;
    }

    /** Se um provedor remoto esta ativo e pronto. Usado por quem monta listas por provedor. */
    public boolean usesRemoteProvider(java.util.UUID userId) {
        return modelProviderService != null && modelProviderService.remoteProviderReady(userId);
    }

    /** Modelos de imagem do provedor remoto ativo; vazio no modo local. */
    private List<LocalModelInfo> providerImageModelsFor(UUID userId) {
        if (modelProviderService == null || !modelProviderService.remoteProviderReady(userId)) {
            return List.of();
        }
        try {
            List<LocalModelInfo> models = new ArrayList<>();
            for (String name : modelProviderService.listImageModelNames(userId)) {
                models.add(new LocalModelInfo(
                        name,
                        0L,
                        "nuvem",
                        "",
                        modelProviderService.activeKind(userId).name(),
                        false,
                        false,
                        false,
                        false));
            }
            return models;
        } catch (RuntimeException exception) {
            logger.warn(
                    "Falha ao listar modelos de imagem do provedor: {}",
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Modelos do provedor de nuvem ativo, ou lista vazia quando o fluxo e local. */
    private List<LocalModelInfo> cloudModelsFor(UUID userId) {
        if (modelProviderService == null || !modelProviderService.cloudProviderSelected(userId)) {
            return List.of();
        }
        try {
            JsonNode listing = modelProviderService.listAvailableModels(userId);
            List<LocalModelInfo> models = new ArrayList<>();
            String selected = modelProviderService.cloudModelName(userId);
            for (JsonNode model : listing.path("data")) {
                String name = model.path("id").asText(model.path("name").asText(""));
                if (name.isBlank()) {
                    continue;
                }
                models.add(new LocalModelInfo(
                        name,
                        0L,
                        "nuvem",
                        "",
                        // family carrega o TIPO do provedor: e por ele que a interface mostra de
                        // onde a resposta vem, em vez de um "cloud" generico.
                        modelProviderService.activeKind(userId).name(),
                        name.equalsIgnoreCase(selected),
                        false,
                        // Gemini e multimodal; marcar como vision evita a UI trocar para um modelo
                        // local de visao quando o usuario anexa imagem estando na nuvem.
                        true,
                        false));
            }
            return models;
        } catch (RuntimeException exception) {
            logger.warn(
                    "Falha ao listar modelos do provedor de nuvem: {}",
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<LocalModelInfo> sortModels(List<LocalModelInfo> models) {
        models.sort((left, right) -> {
            int recommended = Boolean.compare(right.recommended(), left.recommended());
            if (recommended != 0) {
                return recommended;
            }

            int heavy = Boolean.compare(left.heavy(), right.heavy());
            if (heavy != 0) {
                return heavy;
            }

            return left.name().compareToIgnoreCase(right.name());
        });
        return models;
    }

    private boolean isRecommendedModel(String modelName) {
        String normalized = modelName.toLowerCase(Locale.ROOT);
        String defaultNormalized = defaultChatModel.toLowerCase(Locale.ROOT);
        String defaultFamily = defaultNormalized.contains(":")
                ? defaultNormalized.substring(0, defaultNormalized.indexOf(':'))
                : defaultNormalized;
        return normalized.equals(defaultNormalized)
                || normalized.equals(defaultFamily)
                || normalized.startsWith(defaultFamily + ":");
    }

    private boolean isPreferredVisionModel(String modelName) {
        return modelName != null && modelName.equalsIgnoreCase(defaultVisionModel);
    }

    /** Modelo de imagem configurado na tela, ou o padrao de configuracao. */
    public String imageModelFor(UUID userId) {
        if (modelProviderService != null) {
            String configured = modelProviderService.activeImageModel(userId);
            if (!configured.isBlank()) {
                return configured;
            }
        }
        return defaultImageModel;
    }

    private boolean isVisionModel(String modelName, String family) {
        String normalizedName = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        String normalizedFamily = family == null ? "" : family.toLowerCase(Locale.ROOT);
        return normalizedName.contains("vision")
                || normalizedName.contains("llava")
                || normalizedName.contains("bakllava")
                || normalizedName.contains("moondream")
                || normalizedName.contains("minicpm-v")
                || normalizedName.matches(".*qwen[^:]*vl.*")
                || normalizedFamily.contains("mllama")
                || normalizedFamily.contains("qwen25vl")
                || normalizedFamily.contains("qwen2vl")
                || normalizedFamily.contains("llava")
                || normalizedFamily.contains("gemma3");
    }

    private String normalizeChatModel(String modelName) {
        if (modelName == null || modelName.isBlank() || !isChatModel(modelName)) {
            return defaultChatModel;
        }
        return modelName;
    }

    private boolean isChatModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String normalized = modelName.toLowerCase(Locale.ROOT);
        return !normalized.contains("embed")
                && !normalized.contains("flux")
                && !normalized.contains("stable-diffusion")
                && !normalized.contains("sdxl")
                && !normalized.contains("image-turbo")
                && !normalized.contains("z-image")
                && !normalized.contains("text-to-image");
    }

    private boolean isImageModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String normalized = modelName.toLowerCase(Locale.ROOT);
        return normalized.contains("flux")
                || normalized.contains("stable-diffusion")
                || normalized.contains("sdxl")
                || normalized.contains("image-turbo")
                || normalized.contains("z-image")
                || normalized.contains("text-to-image")
                || normalized.contains("diffusion");
    }

    private boolean isHeavyModel(String modelName, long sizeBytes, String parameterSize) {
        if (sizeBytes >= HEAVY_MODEL_BYTES) {
            return true;
        }

        String normalizedName = modelName.toLowerCase(Locale.ROOT);
        if (normalizedName.contains("70b")
                || normalizedName.contains("32b")
                || normalizedName.contains("14b")
                || normalizedName.contains("13b")
                || normalizedName.contains("8b")
                || normalizedName.contains("7b")) {
            return true;
        }

        String normalizedParams = parameterSize == null ? "" : parameterSize.toLowerCase(Locale.ROOT);
        return normalizedParams.matches(".*\\b([7-9]|[1-9][0-9]+)b\\b.*");
    }

    private String inferFamily(String modelName) {
        String normalized = modelName.toLowerCase(Locale.ROOT);
        if (normalized.contains("llama")) return "llama";
        if (normalized.contains("qwen")) return "qwen";
        if (normalized.contains("mistral")) return "mistral";
        if (normalized.contains("gemma")) return "gemma";
        if (normalized.contains("deepseek")) return "deepseek";
        if (normalized.contains("glm") || normalized.contains("chatglm")) return "glm";
        return "local";
    }

    private String inferParameterSize(String modelName) {
        Matcher matcher = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?b)").matcher(modelName);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "";
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    public Flux<String> streamChat(String model, ArrayNode messages) {
        return streamChat(model, messages, List.of());
    }

    public Flux<String> streamChat(String model, ArrayNode messages, List<String> workspaceRoots) {
        return streamChat(model, messages, workspaceRoots, "");
    }

    public Flux<String> streamChat(String model, ArrayNode messages, List<String> workspaceRoots, String imageModel) {
        return streamChat(
                model, messages, workspaceRoots, imageModel, ImageGenerationOptions.defaults(), newRunId(), null, null);
    }

    @Override
    public Flux<String> streamChat(
            String model,
            ArrayNode messages,
            List<String> workspaceRoots,
            String imageModel,
            ImageGenerationOptions imageOptions,
            String runId,
            Long chatId,
            UUID userId) {
        // Ao terminar o turno com sucesso, dispara em background a extração de memória de longo
        // prazo desta conversa (throttled, fora do caminho da resposta). Nunca bloqueia o stream.
        return streamChatDispatch(model, messages, workspaceRoots, imageModel, imageOptions, runId, chatId, userId)
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
                        memoryExtractionService.maybeExtractAsync(userId, chatId);
                    }
                });
    }

    private Flux<String> streamChatDispatch(
            String model,
            ArrayNode messages,
            List<String> workspaceRoots,
            String imageModel,
            ImageGenerationOptions imageOptions,
            String runId,
            Long chatId,
            UUID userId) {
        String chatModel = resolveChatModel(model, messages, userId);

        // Skill explicita (/nome argumento) ganha de qualquer detector. Sem barra, tenta ativar
        // automaticamente por gatilho (linha "Gatilhos:" do arquivo da skill) — o usuario nao
        // deveria precisar decorar nomes de skill pra elas funcionarem.
        SkillResolution skillResolution = resolveSkillInvocation(messages);
        if (!skillResolution.invoked()) {
            skillResolution = resolveAutoSkillActivation(messages);
        }
        if (skillResolution.invoked() && !skillResolution.found()) {
            return Flux.just(contentChunk(skillResolution.notFoundReply()));
        }
        if (skillResolution.invoked()) {
            messages = skillResolution.augmentedMessages();
            String activatedEvent = eventChunk(
                    "skill.activated",
                    "Skill ativada",
                    "/" + skillResolution.skillName() + " — procedimento injetado nesta rodada.");
            // Skill ativada pula os detectores diretos (conversa, imagem, automacao de app) e vai
            // sempre pro modelo com ferramentas. Esses detectores sao heuristicas de texto, e o
            // procedimento injetado confunde eles: o corpo da skill dizendo "use terminal_run"
            // continha a palavra "terminal", e o detector de automacao abriu o app Terminal em vez
            // de deixar o modelo executar a skill.
            AgentRunState state = new AgentRunState();
            state.runId = runId;
            state.workspaceRoots = workspaceRoots;
            state.imageModel = imageModel;
            state.imageOptions = imageOptions;
            state.chatId = chatId;
            state.userId = userId;
            // A skill sempre passa pelo modelo — ele raciocina sobre o pedido e decide a chamada.
            // O determinismo vem de GARANTIR que a ferramenta declarada esteja exposta com
            // prioridade na selecao (imune as heuristicas de keyword que desviavam video pra
            // imagem), nao de pular o modelo.
            state.requiredToolName = skillResolution.declaresTool() && skillResolution.toolName() != null
                    ? skillResolution.toolName()
                    : "";
            if (skillResolution.tools() != null && !skillResolution.tools().isEmpty()) {
                state.requiredToolNames.addAll(skillResolution.tools());
            }
            if (skillResolution.maxRounds() != null) {
                state.maxToolRoundsOverride = skillResolution.maxRounds();
            }
            return Flux.concat(Flux.just(activatedEvent), runTurn(chatModel, messages, state, 1));
        }
        return streamChatResolved(chatModel, messages, workspaceRoots, imageModel, imageOptions, runId, chatId, userId);
    }

    /**
     * Nome com cara de modelo local do Ollama ({@code familia:tag}), que um provedor de nuvem nao
     * conhece. Mandar {@code qwen3.5:9b} para o Gemini foi o que gerou o primeiro 404.
     */
    static boolean isLocalModelName(String model) {
        return model != null && model.contains(":") && !model.startsWith("http");
    }

    private String resolveChatModel(String requestedModel, ArrayNode messages, UUID userId) {
        // Provedor remoto ativo: o modelo PEDIDO manda. Este trecho antes devolvia sempre o valor
        // gravado, entao escolher outro modelo no chat nao tinha efeito nenhum — o log mostrava
        // "starting for chat X with model gemini-3.1-pro-preview" seguido de "round 1 ... model
        // gemini-2.5-flash". O gravado so entra quando o pedido vem sem modelo, ou traz um nome
        // local que o provedor remoto nao conhece.
        if (transportFor(userId) != null) {
            if (requestedModel != null && !requestedModel.isBlank() && !isLocalModelName(requestedModel)) {
                return requestedModel;
            }
            String remoteModel = modelProviderService.activeModelName(userId);
            if (!remoteModel.isBlank()) {
                return remoteModel;
            }
        }
        String selectedModel = normalizeChatModel(requestedModel);
        if (!conversationHasImages(messages) || isVisionModel(selectedModel, inferFamily(selectedModel))) {
            return selectedModel;
        }
        return normalizeChatModel(visionModelFor(userId));
    }

    /**
     * Modelo de visao configurado na TELA, caindo no valor de configuracao so quando nao ha escolha.
     *
     * <p>Trocar de modelo de visao exigia editar YAML e reiniciar. Provedor e modelo sao decisao de
     * quem usa o produto, nao de quem edita arquivo.
     */
    private String visionModelFor(UUID userId) {
        if (modelProviderService != null) {
            String configured = modelProviderService.activeVisionModel(userId);
            if (!configured.isBlank()) {
                return configured;
            }
        }
        return defaultVisionModel;
    }

    private Flux<String> streamChatResolved(
            String chatModel,
            ArrayNode messages,
            List<String> workspaceRoots,
            String imageModel,
            ImageGenerationOptions imageOptions,
            String runId,
            Long chatId,
            UUID userId) {
        ApprovalVoiceCommand approvalCommand = detectApprovalVoiceCommand(messages, userId);
        if (approvalCommand != null) {
            if (approvalCommand.decision() == ApprovalVoiceDecision.REJECT) {
                return rejectTool(approvalCommand.approvalId(), approvalCommand.comment());
            }
            return approveTool(approvalCommand.approvalId(), approvalCommand.comment(), approvalCommand.memory());
        }

        String directResponse = detectDirectConversationResponse(messages);
        if (directResponse != null) {
            return Flux.just(contentChunk(directResponse));
        }

        ToolCall directImageToolCall = withExecutionContext(
                withImageOptions(
                        withImageModel(detectDirectImageGenerationRequest(messages), imageModel), imageOptions),
                chatId,
                userId);
        if (directImageToolCall != null) {
            if (toolRegistry.canExecuteDirectly(directImageToolCall.name())) {
                return executeDirectTool(messages, directImageToolCall, runId);
            }
            if (permissionService.canAutoApprove(
                    runId,
                    userId,
                    directImageToolCall.name(),
                    permissionArguments(directImageToolCall),
                    workspaceRoots)) {
                return executeDirectTool(messages, directImageToolCall, runId);
            }
            return requestDirectToolApproval(chatModel, messages, directImageToolCall, workspaceRoots, runId);
        }

        ToolCall directToolCall = detectDirectSystemAutomationRequest(messages);
        if (directToolCall != null) {
            if (toolRegistry.canExecuteDirectly(directToolCall.name())) {
                return executeDirectTool(messages, directToolCall, runId);
            }
            if (permissionService.canAutoApprove(
                    runId, userId, directToolCall.name(), permissionArguments(directToolCall), workspaceRoots)) {
                return executeDirectTool(messages, directToolCall, runId);
            }
            return requestDirectToolApproval(chatModel, messages, directToolCall, workspaceRoots, runId);
        }

        AgentRunState state = new AgentRunState();
        state.runId = runId;
        state.workspaceRoots = workspaceRoots;
        state.imageModel = imageModel;
        state.imageOptions = imageOptions;
        state.chatId = chatId;
        state.userId = userId;
        // O aviso vai ANTES da resposta: se o usuario escolheu nuvem e recebe o modelo local sem
        // saber, ele julga a qualidade do Gemini olhando para a saida de um 9B local.
        // Sem desvio: o provedor remoto entra pelo MESMO laco de rodadas, via ModelTransport. E o que
        // faz o Gemini enxergar ferramenta, RAG e memoria — o agente e quem as tem, o modelo so
        // processa. Enquanto nao houver transporte para o tipo ativo, o aviso evita a mentira de
        // responder pelo local em silencio.
        String cloudNotice = cloudProviderNotice(userId, chatModel);
        Flux<String> turn = runTurn(chatModel, messages, state, 1);
        return cloudNotice.isEmpty() ? turn : Flux.concat(Flux.just(contentChunk(cloudNotice)), turn);
    }

    /** Implementacao que atende o tipo ativo, ou null quando ainda nao existe uma. */
    private com.avento.service.provider.CloudChatProvider providerForActiveKind(UUID userId) {
        if (cloudChatProviders == null || modelProviderService == null) {
            return null;
        }
        if (!modelProviderService.remoteProviderReady(userId)) {
            return null;
        }
        com.avento.service.provider.ProviderKind kind = modelProviderService.activeKind(userId);
        return cloudChatProviders.stream()
                .filter(provider -> provider.kind() == kind)
                .findFirst()
                .orElse(null);
    }

    /**
     * Caminho de nuvem: conversa apenas, SEM ferramentas.
     *
     * <p>Desvia antes do laço de rodadas de propósito. O laço monta toolset, interpreta tool_call e
     * reexecuta — tudo no formato do Ollama. Reaproveitá-lo com um provedor que devolve
     * {@code functionCall} dentro de {@code parts} daria erro silencioso; ferramentas na nuvem são a
     * etapa seguinte, sobre esta base já validada.
     */
    private Flux<String> streamThroughCloudProvider(
            com.avento.service.provider.CloudChatProvider provider, ArrayNode messages, UUID userId) {
        String model = modelProviderService.activeModelName(userId);
        String apiKey = modelProviderService.rawApiKey(userId);
        String aviso = "\n> ☁️ Respondendo por **" + provider.providerName() + " (" + model
                + ")**. Neste modo as ferramentas locais ficam indisponíveis — para usá-las, selecione"
                + " o provedor local nas configurações.\n\n";
        return Flux.concat(Flux.just(contentChunk(aviso)), provider.streamChat(messages, model, apiKey));
    }

    /**
     * Texto de aviso quando ha provedor de nuvem selecionado, vazio quando nao ha.
     *
     * <p>O fluxo de chat monta corpo no formato nativo do Ollama e chama {@code /api/chat} num
     * WebClient com a base URL fixada no construtor. Nada disso consulta o provedor — os metodos
     * {@code resolveActiveModelUrl}/{@code resolveActiveModelName} existem no ModelProviderService e
     * nunca foram chamados por ninguem. Ate a camada de provedor existir, o minimo honesto e dizer.
     */
    String cloudProviderNotice(UUID userId, String modelUsed) {
        if (modelProviderService == null || !modelProviderService.remoteProviderReady(userId)) {
            return "";
        }
        if (transportFor(userId) != null) {
            return ""; // ha transporte para este tipo: a resposta vem de la, com ferramentas.
        }
        String selected = modelProviderService.selectedCloudProviderName(userId);
        return "\n> ⚠️ Você selecionou **" + selected + "**, mas o fluxo de conversa ainda fala apenas"
                + " com o modelo local. Esta resposta veio de `" + modelUsed + "`, não da nuvem."
                + " A integração com provedor de nuvem ainda não foi implementada aqui.\n\n";
    }

    private int lastUserMessageIndex(ArrayNode messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).path("role").asText())) {
                return index;
            }
        }
        return -1;
    }

    // messages chega em streamChat como um ArrayNode fresco, parseado direto do corpo da
    // requisicao HTTP (LocalAiOrchestratorController.chatStream) — nao e uma referencia
    // compartilhada com o historico persistido, entao reescrever o conteudo da ultima mensagem do
    // usuario aqui e seguro e nao afeta o que fica salvo/exibido no frontend.
    private SkillResolution resolveSkillInvocation(ArrayNode messages) {
        int lastUserIndex = lastUserMessageIndex(messages);
        if (lastUserIndex == -1) {
            return SkillResolution.NOT_INVOKED;
        }

        String content = messages.get(lastUserIndex).path("content").asText("").trim();
        Matcher matcher = SKILL_INVOCATION.matcher(content);
        if (!matcher.matches()) {
            return SkillResolution.NOT_INVOKED;
        }

        String skillName = matcher.group(1);
        String argument = matcher.group(2) == null ? "" : matcher.group(2).trim();

        if ("skills".equals(skillName)) {
            return new SkillResolution(true, false, skillName, null, null, null, null, null, skillListingReply());
        }

        Optional<Skill> skill = skillRegistry.find(skillName);
        if (skill.isEmpty()) {
            return new SkillResolution(
                    true,
                    false,
                    skillName,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "\n> Não conheço a skill `" + skillName + "`.\n" + skillListingReply());
        }

        String expandedContent =
                "[Skill: " + skill.get().name() + "]\n" + skill.get().body() + "\n\nArgumento fornecido pelo usuário: "
                        + (argument.isBlank() ? "(nenhum)" : argument);
        ArrayNode augmented = messages.deepCopy();
        ((ObjectNode) augmented.get(lastUserIndex)).put("content", expandedContent);
        return new SkillResolution(
                true,
                true,
                skill.get().name(),
                skill.get().tool(),
                skill.get().tools(),
                skill.get().maxRounds(),
                argument,
                augmented,
                null);
    }

    // Sem barra, a skill ainda pode ativar sozinha quando a mensagem bate com um dos gatilhos do
    // arquivo (ex.: "cria um projeto nestjs..." ativa /nestjs-project). Diferente da invocacao
    // explicita, o texto original do usuario e PRESERVADO — o procedimento entra como anexo, pra
    // nao perder o que o usuario realmente pediu (nome do projeto, pasta, detalhes).
    private SkillResolution resolveAutoSkillActivation(ArrayNode messages) {
        int lastUserIndex = lastUserMessageIndex(messages);
        if (lastUserIndex == -1) {
            return SkillResolution.NOT_INVOKED;
        }

        String content = messages.get(lastUserIndex).path("content").asText("").trim();
        if (content.isEmpty()) {
            return SkillResolution.NOT_INVOKED;
        }
        String normalized = normalizeIntentText(extractDirectUserRequest(content));
        if (normalized.isBlank() || isCasualUserMessage(normalized)) {
            return SkillResolution.NOT_INVOKED;
        }

        Optional<Skill> skill = skillRegistry.autoMatch(normalized);
        if (skill.isEmpty()) {
            return SkillResolution.NOT_INVOKED;
        }

        String expandedContent = content + "\n\n[Skill ativada automaticamente: "
                + skill.get().name() + "]\nSiga este procedimento para atender o pedido acima:\n"
                + skill.get().body();
        ArrayNode augmented = messages.deepCopy();
        ((ObjectNode) augmented.get(lastUserIndex)).put("content", expandedContent);
        // Ativacao automatica preserva o texto do usuario e vai pro modelo; o argumento fica
        // vazio de proposito — chamada direta de ferramenta so na invocacao explicita com barra.
        return new SkillResolution(
                true,
                true,
                skill.get().name(),
                skill.get().tool(),
                skill.get().tools(),
                skill.get().maxRounds(),
                "",
                augmented,
                null);
    }

    private String skillListingReply() {
        if (skillRegistry.all().isEmpty()) {
            return "\n> Nenhuma skill instalada. Adicione arquivos `.md` em `agent/skills/` no backend.\n";
        }
        StringBuilder reply = new StringBuilder("\n> Skills disponíveis:\n");
        for (Skill available : skillRegistry.all()) {
            reply.append("> - `/")
                    .append(available.name())
                    .append("` — ")
                    .append(available.description())
                    .append('\n');
        }
        reply.append("> Elas também ativam sozinhas quando o pedido bate com o gatilho da skill.\n");
        return reply.toString();
    }

    @Override
    public Flux<String> approveTool(String approvalId, String comment) {
        return approveTool(approvalId, comment, ApprovalMemory.once());
    }

    private Flux<String> approveTool(String approvalId, String comment, ApprovalMemory approvalMemory) {
        return executeApprovedTool(approvalId, comment, approvalMemory);
    }

    // An approval can be resolved by the UI's approve/reject button OR by a chat
    // message whose
    // text matches an approval phrase (e.g. spoken "aprovo") — see the comment on
    // resolvedApprovalIds above. Whichever request loses that race gets told what
    // actually
    // happened instead of the generic "no pending execution found" message.
    private void sendApprovalNotFoundResponse(FluxSink<String> sink, String approvalId) {
        if (resolvedApprovalIds.containsKey(approvalId)
                || (pendingApprovalService != null && pendingApprovalService.isResolved(approvalId))) {
            sink.next(eventChunk("tool.approval.already_completed", "Aprovação já processada", approvalId));
            sink.next(contentChunk(
                    "\n> Essa ação já foi aprovada/rejeitada e resolvida antes (provavelmente um clique duplicado"
                            + " ou uma confirmação por voz e clique ao mesmo tempo). Nada foi executado de novo.\n"));
        } else {
            sink.next(eventChunk("tool.approval.missing", "Aprovação não encontrada", approvalId));
            sink.next(contentChunk("\n> Não encontrei uma execução pendente para `" + approvalId + "`.\n"));
        }
        sink.complete();
    }

    @Override
    public Flux<String> rejectTool(String approvalId, String comment) {
        return Flux.create(sink -> {
            PendingToolExecution pending = takePendingApproval(approvalId);
            if (pending == null) {
                sendApprovalNotFoundResponse(sink, approvalId);
                return;
            }
            latestPendingToolIds.remove(ownerKey(toolUserId(pending.toolCall())), approvalId);
            resolvedApprovalIds.put(approvalId, Boolean.TRUE);
            resolveSiblingApprovals(pending.runId(), approvalId);
            planApprovedRuns.remove(pending.runId());

            String detail = comment == null || comment.isBlank() ? approvalId : approvalId + ": " + comment.trim();
            timelineService.recordApproval(
                    pending.runId(),
                    approvalId,
                    "tool.rejected",
                    pending.toolCall().name(),
                    detail,
                    null);
            sink.next(eventChunk("tool.rejected", "Ação cancelada", detail));
            sink.next(contentChunk(
                    "\n> Ação cancelada. Não executei `" + pending.toolCall().name() + "`.\n"));
            if (comment != null && !comment.isBlank()) {
                sink.next(contentChunk("> Observação: " + comment.trim() + "\n"));
            }
            sink.complete();
        });
    }

    private Flux<String> runTurn(String model, ArrayNode messages, AgentRunState state, int round) {
        long roundStartNanos = System.nanoTime();
        // Log antes de montar o request tambem, de proposito: a montagem chama
        // selectToolsForCurrentRequest -> intentRouter.classify(), que faz uma chamada de
        // embedding sincrona. Sem esse log "starting", uma trava aqui dentro (antes do Ollama
        // de chat ser sequer chamado) fica com a mesma cara de "nunca comecou" que uma rodada
        // que trava no proprio Ollama — os dois logs juntos isolam em qual lado esta o problema.
        logger.info("Agent round {} starting build for model {}", round, model);
        ObjectNode ollamaRequest = buildOllamaRequest(model, messages, state);
        // Nivel INFO de proposito: sem isso, uma rodada que trava antes mesmo de chamar o
        // Ollama (ex.: algo bloqueando na montagem do request) fica indistinguivel de uma
        // rodada que nunca comecou — ja perdemos tempo de diagnostico por essa lacuna.
        logger.info(
                "Agent round {} sending request: model={} tools={} messages={}",
                round,
                model,
                ollamaRequest.path("tools").size(),
                messages.size());

        return Flux.create(sink -> {
            TurnCapture capture = new TurnCapture(shouldDeferMediaNarration(messages));
            sink.next(eventChunk(
                    "agent.round.started",
                    "Rodada " + round + " iniciada",
                    "Enviando contexto ao modelo " + model + "."));

            Disposable modelStream = streamFromProvider(ollamaRequest, state.userId)
                    // Nada aqui limitava quanto tempo esperar por um novo pedaco do Ollama. Se o
                    // modelo travar gerando (contexto grande, maquina sobrecarregada), o pedido
                    // ficava pendurado pra sempre: sem erro, sem aviso, sem fim de stream. Esse
                    // timeout e por AUSENCIA de sinal (nao duracao total): uma geracao longa mas
                    // ativa nunca aciona isso, só um silencio real do Ollama por mais de 2 minutos.
                    .timeout(Duration.ofSeconds(300))
                    .subscribe(
                            chunk -> handleModelChunk(chunk, sink, capture, state, model),
                            error -> {
                                logger.info(
                                        "Agent round {} failed after {}ms",
                                        round,
                                        (System.nanoTime() - roundStartNanos) / 1_000_000);
                                handleStreamError(model, messages, state, round, sink, error);
                            },
                            () -> {
                                logger.info(
                                        "Agent round {} finished in {}ms: contentChars={} nativeToolCalls={}",
                                        round,
                                        (System.nanoTime() - roundStartNanos) / 1_000_000,
                                        capture.assistantText.length(),
                                        capture.nativeToolCalls.size());
                                flushPendingLine(capture, sink, state, model);
                                capture.deferAssistantOutput = false;
                                finishTurn(model, messages, state, round, sink, capture);
                            });
            // Descarta a chamada HTTP desta rodada E as subscriptions das rodadas seguintes
            // (state.subscriptions): sem o composite, cancelar a run deixava a requisicao da
            // proxima rodada viva no Ollama para sempre — ver comentario em AgentRunState.
            Disposable cleanup = () -> {
                modelStream.dispose();
                state.subscriptions.dispose();
            };
            sink.onCancel(cleanup);
            sink.onDispose(cleanup);
        });
    }

    // Enviar think:true a um modelo que nao suporta o campo dedicado pode devolver resposta vazia
    // (visto com o granite). So respeita o toggle da UI para modelos capazes; os demais sempre false.
    private boolean thinkingEnabledForRequest(UUID userId, String model) {
        return supportsThinking(model) && thinkingEnabledForRequest(userId);
    }

    private boolean supportsThinking(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String normalized = model.toLowerCase(Locale.ROOT);
        return thinkingCapableModels.stream().anyMatch(normalized::contains);
    }

    // Lê o toggle "Thinking" da UI (Redis); sem Redis ou sem valor, usa o default de configuração.
    private boolean thinkingEnabledForRequest(UUID userId) {
        return userSettingsService == null
                ? enableThinking
                : userSettingsService.thinkingEnabled(userId, enableThinking);
    }

    private ObjectNode buildOllamaRequest(String model, ArrayNode messages, AgentRunState state) {
        ObjectNode ollamaRequest = mapper.createObjectNode();
        ollamaRequest.put("model", model);
        ArrayNode guardedMessages = withBackendIdentityPrompt(messages, state.workspaceRoots, state.userId);
        ollamaRequest.put("stream", true);
        // Sem isso, o roteamento do raciocinio pro campo dedicado message.thinking (ver
        // handleModelChunk) fica a criterio do default do Ollama/modelo, que e inconsistente
        // entre versoes para modelos hibridos como qwen3 — o raciocinio pode vazar como
        // message.content normal em vez de ficar isolado no campo de thinking.
        ollamaRequest.put("think", thinkingEnabledForRequest(state.userId, model));
        ollamaRequest.put("keep_alive", keepAlive);
        ObjectNode options = ollamaRequest.putObject("options");
        options.put("num_ctx", effectiveContextTokens(state.userId));
        options.put("num_predict", numPredict);
        options.put("temperature", temperature);
        options.put("top_p", topP);
        options.put("top_k", topK);
        options.put("repeat_penalty", repeatPenalty);

        boolean conversationHasImages = conversationHasImages(messages);
        // Conecta sob demanda os servidores MCP que o pedido precisa (ex.: git) antes de listar, para
        // que as ferramentas deles já entrem no toolset desta rodada — "pega a ferramenta que precisa".
        ToolExecutionGateway.ToolListing listing = toolGateway.listToolsWithAutoConnect(
                state.userId, state.chatId, state.runId, lastUserMessage(messages), state.workspaceRoots);
        ArrayNode availableTools = listing.tools();
        // O catálogo da rodada é a referência do parser de fallback textual: uma chamada escrita
        // como texto só vira execução se o nome existir aqui (local OU MCP externa conectada).
        Set<String> availableNames = new HashSet<>();
        for (JsonNode tool : availableTools) {
            availableNames.add(tool.path("name").asText(""));
        }
        state.availableToolNames = availableNames;
        // Servidor conectado PARA este pedido = ferramentas dele garantidas nesta rodada. Sem isso,
        // o auto-connect ligava o servidor (ex.: fetch para "cotação do dólar") e o filtro de
        // intenção escondia as ferramentas dele na mesma resposta.
        if (!listing.autoConnectedServers().isEmpty()) {
            for (JsonNode tool : availableTools) {
                if (listing.autoConnectedServers()
                        .contains(tool.path("mcpServer").asText(""))) {
                    state.extraExposedToolNames.add(tool.path("name").asText(""));
                }
            }
        }
        // Ferramentas ativadas via activate_tools em rodadas anteriores desta run (Redis).
        if (toolCatalogService != null && !state.runId.isBlank()) {
            state.extraExposedToolNames.addAll(toolCatalogService.getActiveTools(state.runId));
        }
        // finalSynthesis zera o toolset de propósito: é a rodada em que o modelo precisa RESPONDER
        // com o que já coletou. Com ferramentas na mesa ele pede mais uma leitura e o ciclo recomeça.
        ArrayNode tools = modelsWithoutToolSupport.contains(model) || conversationHasImages || state.finalSynthesis
                ? mapper.createArrayNode()
                : state.forceFullToolset
                        ? availableTools
                        : selectToolsForCurrentRequest(availableTools, messages, state);
        // Restringe ao escopo do agente da tarefa (modo agente), se houver allow-list para esta run.
        tools = applyAgentToolPolicy(tools, state);
        appendRoundCapabilitiesNote(guardedMessages, tools);
        ollamaRequest.set("messages", guardedMessages);
        logRoundToolset(state, listing.autoConnectedServers(), tools);
        if (tools != null && tools.size() > 0) {
            ArrayNode openAiTools = mapper.createArrayNode();
            for (JsonNode mcpTool : tools) {
                ObjectNode tool = mapper.createObjectNode();
                tool.put("type", "function");

                ObjectNode function = mapper.createObjectNode();
                function.put("name", mcpTool.path("name").asText());
                function.put("description", mcpTool.path("description").asText());
                JsonNode inputSchemaNode = mcpTool.path("inputSchema");
                ObjectNode parameters;
                if (inputSchemaNode.isObject() && !inputSchemaNode.isEmpty()) {
                    parameters = (ObjectNode) inputSchemaNode.deepCopy();
                    parameters.put("type", "object");
                    if (!parameters.has("properties")) {
                        parameters.set("properties", mapper.createObjectNode());
                    }
                } else {
                    parameters = mapper.createObjectNode();
                    parameters.put("type", "object");
                    parameters.set("properties", mapper.createObjectNode());
                }
                function.set("parameters", parameters);

                tool.set("function", function);
                openAiTools.add(tool);
            }
            ollamaRequest.set("tools", openAiTools);
        }

        return ollamaRequest;
    }

    private ObjectNode buildOllamaRequest(String model, ArrayNode messages, List<String> workspaceRoots) {
        AgentRunState state = new AgentRunState();
        state.workspaceRoots = workspaceRoots == null ? List.of() : List.copyOf(workspaceRoots);
        return buildOllamaRequest(model, messages, state);
    }

    // O prompt de identidade anuncia capacidades em termos gerais; ESTA nota diz ao modelo o que
    // existe DE VERDADE nesta rodada. Sem ela, o modelo "chamava" em texto uma ferramenta que
    // conhecia do prompt mas que não estava declarada — a alucinação de chamada que o usuário via
    // como JSON solto na resposta. Vai no FIM das mensagens (sufixo), não no prefixo, para não
    // invalidar o cache de prompt do Ollama.
    private void appendRoundCapabilitiesNote(ArrayNode guardedMessages, ArrayNode tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText(""));
        }
        Collections.sort(names);
        ObjectNode note = guardedMessages.addObject();
        note.put("role", "system");
        note.put(
                "content",
                "[Ferramentas desta rodada] Somente estas ferramentas nativas existem agora: "
                        + String.join(", ", names)
                        + ". Chame-as SEMPRE pelo mecanismo nativo de tool call — nunca escreva JSON no texto."
                        + " Se precisar de uma capacidade fora desta lista, use search_capabilities para"
                        + " descobrir e activate_tools para ativar.");
    }

    // Uma linha por rodada com o que o diagnóstico sempre precisa: quais servidores o auto-connect
    // ligou e quais ferramentas foram efetivamente entregues ao modelo.
    private void logRoundToolset(AgentRunState state, List<String> autoConnectedServers, ArrayNode tools) {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText(""));
        }
        logger.info(
                "Agent round toolset: run={} autoConnected={} extras={} tools({})={}",
                state.runId,
                autoConnectedServers,
                state.extraExposedToolNames,
                names.size(),
                names);
    }

    private boolean conversationHasImages(ArrayNode messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (JsonNode message : messages) {
            JsonNode images = message.path("images");
            if (images.isArray() && !images.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode selectToolsForCurrentRequest(ArrayNode tools, ArrayNode messages, AgentRunState state) {
        ArrayNode selectedTools = mapper.createArrayNode();
        if (tools == null || tools.isEmpty()) {
            return selectedTools;
        }

        // Skill ativa com `Ferramenta:` declarada: a ferramenta dela e a resposta, ponto.
        // As heuristicas de keyword abaixo ja roubaram pedido de video pro generate_image;
        // a declaracao explicita da skill nao pode perder pra elas.
        if (state.requiredToolNames != null && !state.requiredToolNames.isEmpty()) {
            ArrayNode required = filterToolsByName(tools, state.requiredToolNames);
            if (!required.isEmpty()) {
                return required;
            }
        }

        String lastUserMessage = lastUserMessage(messages);
        if (lastUserMessage == null || lastUserMessage.isBlank()) {
            return selectedTools;
        }

        String normalized = normalizeIntentText(extractDirectUserRequest(lastUserMessage));
        if (isCasualUserMessage(normalized)) {
            return selectedTools;
        }

        // Chat com projeto conectado usa um kit FIXO de ferramentas de desenvolvimento em vez
        // de selecao por intencao. Dois motivos, ambos aprendidos em producao: (1) a selecao
        // por mensagem errava — um pedido de apagar arquivo chegou ao modelo sem delete_file
        // e o turno terminou vazio; (2) a lista variando a cada mensagem muda o prefixo do
        // prompt e quebra o cache de prompt do llama.cpp, forcando reprocessar sistema +
        // schemas toda vez. Kit estavel = ferramentas sempre presentes + prefixo cacheavel.
        // Mockup/tela/interface = bloco ui-preview (HTML), nao ferramenta. Devolve conjunto vazio
        // para o modelo escrever o preview direto — e, crucial, para que generate_image nao fique
        // exposto e o modelo nao caia em gerar uma imagem feia e cara no lugar do preview.
        if (wantsInterfacePrototype(normalized)) {
            return selectedTools;
        }
        // Pedido de imagem/captura PRIORIZA a ferramenta correspondente, mas nao exclui as demais:
        // o retorno exclusivo anterior quebrava pedidos compostos ("gera uma imagem E faz um pdf
        // dela" ficava so com generate_image). A prioridade garante a ferramenta certa no topo e
        // dentro do teto; o resto da selecao continua valendo.
        Set<String> priorityTools = new HashSet<>();
        if (wantsImageGeneration(normalized)) {
            priorityTools.add("generate_image");
        }
        if (wantsScreenCapture(normalized)) {
            priorityTools.add("capture_screen");
        }
        if (!state.workspaceRoots.isEmpty()) {
            // Kit fixo primeiro (prefixo estavel pro cache de prompt), e ate 6 extras que a
            // intencao da mensagem pedir explicitamente — "conecta o mcp do git", "gera uma
            // imagem", "cria um projeto vite" trazem a ferramenta correspondente junto sem
            // abrir mao da estabilidade nas mensagens puras de codigo (que nao ativam extra
            // nenhum e mantem o payload identico ao da mensagem anterior).
            ArrayNode kit = filterToolsByName(tools, projectToolkit);
            IntentProfile intentProfile = intentRouter.classify(normalized);
            int extras = 0;
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText("");
                if (projectToolkit.contains(name)) {
                    continue;
                }
                // Prioridade, auto-conectadas e ativadas via activate_tools entram SEMPRE
                // (fora do limite de extras): o modelo pediu por elas explicitamente.
                if (priorityTools.contains(name) || state.extraExposedToolNames.contains(name)) {
                    kit.add(tool);
                    continue;
                }
                if (extras < PROJECT_TOOLKIT_EXTRA_LIMIT && intentRouter.shouldExposeTool(name, intentProfile)) {
                    kit.add(tool);
                    extras++;
                }
            }
            return kit;
        }

        // classify() dispara uma chamada de embedding; calcular uma vez aqui e
        // reusar no loop evita uma chamada por ferramenta (dezenas de chamadas
        // redundantes ao Ollama para a mesma mensagem com MCP externo conectado).
        IntentProfile intentProfile = intentRouter.classify(normalized);
        // Sob o teto, as locais (inicio do catalogo) enchem as vagas e ferramentas externas de
        // intencao escassa nunca entram — medido ao vivo: "Executa a pesquisa" casou WEB, mas as
        // 12 vagas foram para filesystem e o fetch ficou de fora (o modelo precisou de 2 rodadas
        // de descoberta para alcanca-lo). O leitor web e A ferramenta da intencao WEB: prioridade.
        if (intentProfile.has(AgentIntent.WEB)) {
            priorityTools.add("fetch");
        }
        if (intentProfile.has(AgentIntent.DOCUMENT)) {
            priorityTools.add("generate_pdf");
        }
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (priorityTools.contains(name)
                    || state.extraExposedToolNames.contains(name)
                    || shouldExposeTool(name, normalized, intentProfile, state)) {
                selectedTools.add(tool);
            }
        }
        return capToolCount(selectedTools, priorityTools, state.extraExposedToolNames);
    }

    // Preserva a ordem do catalogo: com o mesmo conjunto, o payload de tools fica identico
    // entre requisicoes do mesmo chat, que e o que permite o cache de prompt reaproveitar
    // o prefixo em vez de reprocessa-lo.
    // No modo agente, restringe o toolset já selecionado à allow-list do agente da tarefa (se houver).
    // Sem registry ou sem allow-list para esta run, o conjunto passa inalterado.
    private ArrayNode applyAgentToolPolicy(ArrayNode tools, AgentRunState state) {
        if (toolPolicyRegistry == null || tools == null || tools.isEmpty()) {
            return tools;
        }
        Set<String> allowed = toolPolicyRegistry.allowed(state.runId);
        return allowed.isEmpty() ? tools : filterToolsByName(tools, allowed);
    }

    private ArrayNode filterToolsByName(ArrayNode tools, Set<String> allowedNames) {
        ArrayNode filtered = mapper.createArrayNode();
        for (JsonNode tool : tools) {
            if (allowedNames.contains(tool.path("name").asText(""))) {
                filtered.add(tool);
            }
        }
        return filtered;
    }

    // Um esquema de ferramenta por si so e barato, mas 20+ deles somados ao prompt de
    // sistema empurram o custo de prompt_eval a ponto de uma rodada nunca terminar dentro
    // do timeout de inatividade da run — medido ao vivo: 23 ferramentas selecionadas levaram
    // uma rodada a exceder 6 minutos sem sinal algum, enquanto o mesmo pedido com poucas
    // ferramentas fecha em menos de 90s.
    //
    // Quando o teto forca uma escolha, as ferramentas casadas com a INTENCAO da tarefa
    // entram primeiro e as ALWAYS_EXPOSED preenchem o que sobrar — nao o contrario. As
    // sempre-expostas sao 10; dando prioridade a elas sobravam so 2 vagas, e um pedido
    // de apagar arquivo chegou ao modelo sem delete_file/edit_file/terminal_run: ele
    // "planejou" a acao no thinking e terminou o turno sem conseguir agir.
    private ArrayNode capToolCount(ArrayNode selectedTools, Set<String> priorityTools, Set<String> extraExposed) {
        // No modo "mostra tudo" nao ha teto: o objetivo e justamente nao esconder ferramenta.
        if (exposeAllTools) {
            return selectedTools;
        }
        if (selectedTools.size() <= maxToolsPerRequest) {
            return selectedTools;
        }
        // Tres faixas, na ordem: (1) prioridade explicita da mensagem + auto-conectadas/ativadas,
        // (2) casadas com a intencao, (3) ALWAYS_EXPOSED preenchendo o que sobrar.
        ArrayNode capped = mapper.createArrayNode();
        Set<String> added = new HashSet<>();
        for (JsonNode tool : selectedTools) {
            String name = tool.path("name").asText("");
            if (capped.size() >= maxToolsPerRequest) {
                break;
            }
            if (priorityTools.contains(name) || extraExposed.contains(name)) {
                capped.add(tool);
                added.add(name);
            }
        }
        for (JsonNode tool : selectedTools) {
            String name = tool.path("name").asText("");
            if (capped.size() >= maxToolsPerRequest) {
                break;
            }
            if (!added.contains(name) && !ALWAYS_EXPOSED_TOOLS.contains(name)) {
                capped.add(tool);
                added.add(name);
            }
        }
        for (JsonNode tool : selectedTools) {
            String name = tool.path("name").asText("");
            if (capped.size() >= maxToolsPerRequest) {
                break;
            }
            if (!added.contains(name) && ALWAYS_EXPOSED_TOOLS.contains(name)) {
                capped.add(tool);
                added.add(name);
            }
        }
        return capped;
    }

    // Ferramentas baratas (schema pequeno) e de alto valor ficam sempre visíveis
    // ao modelo, em vez de dependerem de detecção de intenção por palavra-chave.
    // O filtro por intenção existe para conter o custo de contexto dos clusters
    // grandes de MCP externo (Git, Chrome DevTools etc.), não para ferramentas
    // isoladas como esta, cujo custo de sempre expor é desprezível.
    private static final Set<String> ALWAYS_EXPOSED_TOOLS = Set.of(
            "generate_image",
            "generate_video",
            "capture_screen",
            "read_document",
            "list_mcp_servers",
            "connect_mcp_server",
            "sequentialthinking",
            "read_graph",
            "search_nodes",
            "open_nodes",
            // O par de descoberta progressiva precisa estar SEMPRE na mesa: é a porta de entrada
            // para qualquer capacidade fora do toolset atual ("procura a ferramenta e usa").
            "search_capabilities",
            "activate_tools");

    private boolean shouldExposeTool(
            String toolName, String normalizedMessage, IntentProfile intentProfile, AgentRunState state) {
        // Modo "mostra tudo": entrega o toolset inteiro ao modelo sem triagem por intencao. Viavel
        // agora que o cache de prompt volta a funcionar (schemas ficam no prefixo cacheado e sao
        // avaliados uma vez, nao a cada mensagem). Custo: prompt maior e mais chance de o modelo
        // pequeno escolher errado. Ligar/desligar por AVENTO_AGENT_EXPOSE_ALL_TOOLS.
        if (exposeAllTools) {
            return true;
        }
        if (ALWAYS_EXPOSED_TOOLS.contains(toolName)) {
            return true;
        }

        if (state.forceFullToolset) {
            return true;
        }

        if (!state.requiredToolName.isEmpty() && state.requiredToolName.equals(toolName)) {
            return true;
        }

        if (!state.requiredToolNames.isEmpty() && state.requiredToolNames.contains(toolName)) {
            return true;
        }

        if (wantsImageGeneration(normalizedMessage)) {
            return "generate_image".equals(toolName);
        }
        if (wantsScreenCapture(normalizedMessage)) {
            return "capture_screen".equals(toolName);
        }
        return intentRouter.shouldExposeTool(toolName, intentProfile);
    }

    // Pedido de mockup/tela/interface NUNCA e geracao de imagem — vai para um bloco ui-preview
    // (HTML), que renderiza e vira artefato baixavel. Evita gerar imagem feia e cara no ComfyUI.
    private boolean wantsInterfacePrototype(String normalizedMessage) {
        return visualIntentClassifier.isInterfacePrototype(normalizedMessage);
    }

    private boolean wantsImageGeneration(String normalizedMessage) {
        if (wantsInterfacePrototype(normalizedMessage)) {
            return false;
        }
        boolean productMockup = visualIntentClassifier.isProductMockup(normalizedMessage);
        return productMockup
                || normalizedMessage.contains("gera imagem")
                || normalizedMessage.contains("gerar imagem")
                || normalizedMessage.contains("gere imagem")
                || normalizedMessage.contains("cria imagem")
                || normalizedMessage.contains("criar imagem")
                || normalizedMessage.contains("crie imagem")
                || normalizedMessage.contains("gera a imagem")
                || normalizedMessage.contains("gerar a imagem")
                || normalizedMessage.contains("gere a imagem")
                || normalizedMessage.contains("gera uma imagem")
                || normalizedMessage.contains("gerar uma imagem")
                || normalizedMessage.contains("gere uma imagem")
                || normalizedMessage.contains("gera pra mim imagem")
                || normalizedMessage.contains("gera pra mim a imagem")
                || normalizedMessage.contains("gera pra mim uma imagem")
                || normalizedMessage.contains("gere pra mim imagem")
                || normalizedMessage.contains("gere pra mim a imagem")
                || normalizedMessage.contains("gere pra mim uma imagem")
                || normalizedMessage.contains("gerar pra mim imagem")
                || normalizedMessage.contains("gerar pra mim a imagem")
                || normalizedMessage.contains("gerar pra mim uma imagem")
                || normalizedMessage.contains("cria a imagem")
                || normalizedMessage.contains("criar a imagem")
                || normalizedMessage.contains("crie a imagem")
                || normalizedMessage.contains("cria uma imagem")
                || normalizedMessage.contains("criar uma imagem")
                || normalizedMessage.contains("crie uma imagem")
                || normalizedMessage.contains("cria pra mim imagem")
                || normalizedMessage.contains("cria pra mim a imagem")
                || normalizedMessage.contains("cria pra mim uma imagem")
                || normalizedMessage.contains("faz uma imagem")
                || normalizedMessage.contains("faca uma imagem")
                || normalizedMessage.contains("faz pra mim imagem")
                || normalizedMessage.contains("faz pra mim uma imagem")
                || normalizedMessage.contains("faca pra mim imagem")
                || normalizedMessage.contains("faca pra mim uma imagem")
                || normalizedMessage.contains("produz imagem")
                || normalizedMessage.contains("produzir imagem")
                || normalizedMessage.contains("gera uma foto")
                || normalizedMessage.contains("gerar uma foto")
                || normalizedMessage.contains("gera pra mim uma foto")
                || normalizedMessage.contains("gere pra mim uma foto")
                || normalizedMessage.contains("cria uma foto")
                || normalizedMessage.contains("criar uma foto")
                || normalizedMessage.contains("generate image")
                || normalizedMessage.contains("create image")
                || normalizedMessage.contains("text to image")
                || normalizedMessage.contains("imagem artistica")
                || normalizedMessage.contains("ilustracao de")
                || normalizedMessage.contains("ilustracao artistica")
                || normalizedMessage.contains("retrato artistico")
                || normalizedMessage.contains("gere imagem realista")
                || normalizedMessage.contains("retrato explicito")
                || normalizedMessage.contains("pintura de")
                || normalizedMessage.contains("desenho de")
                || normalizedMessage.contains("concept art")
                || normalizedMessage.contains("quero uma imagem")
                || normalizedMessage.contains("quero uma foto")
                || normalizedMessage.contains("quero um desenho")
                || normalizedMessage.contains("quero uma ilustracao")
                || normalizedMessage.contains("quero um retrato")
                || normalizedMessage.contains("me manda uma imagem")
                || normalizedMessage.contains("me da uma imagem")
                || normalizedMessage.contains("me de uma imagem")
                || normalizedMessage.contains("me mostra uma imagem")
                || normalizedMessage.contains("desenha uma")
                || normalizedMessage.contains("desenha um")
                || normalizedMessage.contains("desenhe uma")
                || normalizedMessage.contains("desenhe um")
                || normalizedMessage.contains("pinta uma")
                || normalizedMessage.contains("pinta um")
                || normalizedMessage.contains("pinte uma")
                || normalizedMessage.contains("pinte um")
                || normalizedMessage.contains("ilustra uma")
                || normalizedMessage.contains("ilustra um")
                || normalizedMessage.contains("ilustre uma")
                || normalizedMessage.contains("ilustre um")
                || normalizedMessage.contains("renderiza uma")
                || normalizedMessage.contains("renderiza um")
                || normalizedMessage.contains("renderize uma")
                || normalizedMessage.contains("renderize um");
    }

    private boolean shouldDeferMediaNarration(ArrayNode messages) {
        String userMessage = lastUserMessage(messages);
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String normalized = normalizeIntentText(extractDirectUserRequest(userMessage));
        return wantsImageGeneration(normalized)
                || normalized.contains("generate image")
                || normalized.contains("generate_image")
                || normalized.contains("gera video")
                || normalized.contains("gerar video")
                || normalized.contains("cria video")
                || normalized.contains("criar video")
                || normalized.contains("crie video")
                || normalized.contains("gera um video")
                || normalized.contains("gerar um video")
                || normalized.contains("gere um video")
                || normalized.contains("cria um video")
                || normalized.contains("criar um video")
                || normalized.contains("crie um video")
                || normalized.contains("generate video")
                || normalized.contains("generate_video")
                || normalized.contains("image to video")
                || normalized.contains("text to video");
    }

    private ToolCall detectDirectImageGenerationRequest(ArrayNode messages) {
        String lastUserMessage = lastUserMessage(messages);
        if (lastUserMessage == null || lastUserMessage.isBlank()) {
            return null;
        }

        String directRequest = extractDirectUserRequest(lastUserMessage);
        String normalized = normalizeIntentText(directRequest);
        // Este e o atalho que PULA o modelo inteiramente e chama generate_image
        // direto — precisa ser de alta precisao (so frase exata), nao de alto
        // recall. O classificador por embedding e recall-oriented (bom pra
        // EXPOR a ferramenta, ruim pra decidir sozinho por ela) e ja tinha
        // causado falso positivo aqui (ex.: "faca uma analisa dessa pasta"
        // acionando generate_image). Ele continua valendo via
        // intentRouter.classify() na exposicao de ferramentas, so nao aqui.
        boolean directImageRequest = wantsImageGeneration(normalized);
        boolean standaloneImagePrompt = looksLikeStandaloneImagePrompt(directRequest);
        boolean retryingPreviousImageRequest = isGenericImageGenerationFollowUp(directRequest)
                && hasPreviousImageGenerationContext(messages, directRequest);
        if (!directImageRequest && !standaloneImagePrompt && !retryingPreviousImageRequest) {
            return null;
        }

        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("prompt", imageGenerationPrompt(messages, directRequest));
        return new ToolCall("call_direct_" + UUID.randomUUID().toString().substring(0, 8), "generate_image", arguments);
    }

    private boolean looksLikeStandaloneImagePrompt(String request) {
        if (request == null || request.length() < 80 || request.contains("?")) {
            return false;
        }
        String normalized = normalizeIntentText(request);
        if (countImagePromptSignals(normalized, "DISCUSSION") > 0) {
            return false;
        }
        return countImagePromptSignals(normalized, "VISUAL_STYLE") >= 2
                && countImagePromptSignals(normalized, "COMPOSITION") >= 2;
    }

    private long countImagePromptSignals(String normalized, String section) {
        return IMAGE_PROMPT_SIGNALS.getOrDefault(section, List.of()).stream()
                .filter(normalized::contains)
                .count();
    }

    private String imageGenerationPrompt(ArrayNode messages, String directRequest) {
        String trimmedRequest = directRequest == null ? "" : directRequest.trim();
        if (!isGenericImageGenerationFollowUp(trimmedRequest)) {
            return trimmedRequest;
        }

        return previousUserPrompt(messages, false)
                .or(() -> previousAssistantImagePrompt(messages))
                .or(() -> imageSubjectPrompt(messages, trimmedRequest))
                .orElse(trimmedRequest.isBlank() ? "Gere uma imagem a partir do pedido do usuário." : trimmedRequest);
    }

    private boolean hasPreviousImageGenerationContext(ArrayNode messages, String directRequest) {
        return previousUserPrompt(messages, true).isPresent()
                || previousAssistantImagePrompt(messages).isPresent()
                || imageSubjectPrompt(messages, directRequest).isPresent();
    }

    private Optional<String> previousUserPrompt(ArrayNode messages, boolean requireImageIntent) {
        for (int index = messages.size() - 2; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (!"user".equals(message.path("role").asText(""))) {
                continue;
            }
            String previousRequest =
                    extractDirectUserRequest(message.path("content").asText("")).trim();
            String normalizedPreviousRequest = normalizeIntentText(previousRequest);
            if (!previousRequest.isBlank()
                    && (!requireImageIntent || wantsImageGeneration(normalizedPreviousRequest))
                    && !isGenericImageGenerationFollowUp(previousRequest)
                    && !isImageGenerationStatusMessage(normalizedPreviousRequest)
                    && !isCasualUserMessage(normalizedPreviousRequest)) {
                return Optional.of(previousRequest);
            }
        }
        return Optional.empty();
    }

    private Optional<String> previousAssistantImagePrompt(ArrayNode messages) {
        Pattern imageSubject = Pattern.compile("(?iu)\\bimagem\\s+(?:do|da|de um|de uma)\\s+([^.!?]+)");
        for (int index = messages.size() - 2; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (!"assistant".equals(message.path("role").asText(""))) {
                continue;
            }
            String content = message.path("content").asText("");
            if (!isImageGenerationStatusMessage(normalizeIntentText(content))) {
                continue;
            }
            Matcher matcher = imageSubject.matcher(content);
            if (matcher.find()) {
                return Optional.of("Gere uma imagem de " + matcher.group(1).trim() + ".");
            }
        }
        return Optional.empty();
    }

    private Optional<String> imageSubjectPrompt(ArrayNode messages, String directRequest) {
        String request = directRequest == null ? "" : directRequest.trim();
        Matcher subjectMatcher = Pattern.compile(
                        "(?iu)^(?:gera|gere|faz|faça|faca|cria|crie)\\s+(?:o|a|um|uma)?\\s*(.+?)(?:\\s+que\\s+(?:eu\\s+)?pedi)?[.!?]*$")
                .matcher(request);
        if (subjectMatcher.matches()) {
            String subject = subjectMatcher.group(1).trim();
            if (!subject.isBlank()
                    && !containsAny(normalizeIntentText(subject), "imagem", "o que", "isso", "de novo")) {
                return Optional.of("Gere uma imagem de " + subject + ".");
            }
        }
        return Optional.empty();
    }

    private boolean isImageGenerationStatusMessage(String normalizedMessage) {
        return containsAny(
                normalizedMessage,
                "erro ao chamar o modelo",
                "falha ao chamar o modelo",
                "nao consegui executar generate_image",
                "nao consegui gerar",
                "imagem nao foi gerada",
                "modelo local",
                "modelo flux",
                "modelo nao esta disponivel",
                "nao esta disponivel no ollama",
                "não está disponível no ollama",
                "does not support chat",
                "ollama image generation failed",
                "model requires more memory");
    }

    private boolean isGenericImageGenerationFollowUp(String request) {
        String normalized = normalizeIntentText(request);
        return containsAny(
                normalized,
                "gera a imagem que pedi",
                "gera a imagem que eu pedi",
                "gera a imagem que eue pedi",
                "gerar a imagem que pedi",
                "gerar a imagem que eu pedi",
                "gerar a imagem que eue pedi",
                "gera imagem que pedi",
                "gera imagem que eu pedi",
                "gera imagem que eue pedi",
                "gerar imagem que pedi",
                "gerar imagem que eu pedi",
                "gerar imagem que eue pedi",
                "faz a imagem que pedi",
                "faca a imagem que pedi",
                "cria a imagem que pedi",
                "criar a imagem que pedi",
                "imagem que pedi",
                "imagem que eu pedi",
                "imagem que eue pedi",
                "gera o que pedi",
                "gere o que pedi",
                "gerar o que pedi",
                "cria o que pedi",
                "crie o que pedi",
                "faca o que pedi",
                "faz o que pedi",
                "gera o pitbull que pedi",
                "gera o pitbull que eu pedi",
                "gere o pitbull que pedi",
                "gere o pitbull que eu pedi",
                "tenta de novo",
                "tenta agora",
                "testa de novo",
                "testa com o que tiver",
                "testa",
                "tente de novo",
                "tente agora",
                "prossiga",
                "pode seguir",
                "pode prosseguir",
                "segue",
                "continue");
    }

    private ArrayNode withBackendIdentityPrompt(ArrayNode messages, List<String> workspaceRoots, UUID userId) {
        ArrayNode guardedMessages = mapper.createArrayNode();
        ObjectNode identityMessage = guardedMessages.addObject();
        identityMessage.put("role", "system");
        identityMessage.put(
                "content",
                AGENT_SYSTEM_PROMPT
                        + productFactsBlock(messages)
                        // SO a data (LocalDate), nao LocalDateTime: um timestamp com hora/nanossegundo
                        // muda a cada requisicao e fica no PREFIXO do prompt, invalidando o cache de
                        // prompt do Ollama — obrigando a reavaliar os ~8192 tokens inteiros toda vez
                        // (medido: ~50s por resposta). Com a data, o prefixo fica estavel o dia todo e
                        // o Ollama reaproveita o contexto ja avaliado. Precisao de dia basta ao agente.
                        + "\n\n[Local Environment]\nData atual: "
                        + LocalDate.now().toString()
                        + policyInstructions()
                        + workspaceRootsBlock(workspaceRoots)
                        + memoryBlock(userId)
                        + conversationContinuityBlock(messages));
        guardedMessages.addAll(compactMessagesForModel(messages));
        return guardedMessages;
    }

    private String productFactsBlock(ArrayNode messages) {
        int start = Math.max(0, messages.size() - 8);
        for (int index = messages.size() - 1; index >= start; index--) {
            String content = messages.get(index).path("content").asText("");
            String normalized = normalizeIntentText(extractDirectUserRequest(content));
            if (containsAny(
                    normalized,
                    "avento",
                    "quem e voce",
                    "o que voce pode fazer",
                    "o que vc pode fazer",
                    "suas ferramentas",
                    "seus servicos",
                    "sua arquitetura",
                    "seu criador",
                    "quem te criou",
                    "post do linkedin",
                    "post para o linkedin")) {
                return "\n\n" + AVENTO_PRODUCT_FACTS;
            }
        }
        return "";
    }

    private ArrayNode withBackendIdentityPrompt(ArrayNode messages, List<String> workspaceRoots) {
        return withBackendIdentityPrompt(messages, workspaceRoots, null);
    }

    // Injeta somente os fatos ACTIVE pertencentes ao usuário autenticado.
    private String memoryBlock(UUID userId) {
        if (userId == null) {
            return "";
        }
        try {
            String block = userMemoryService.promptBlock(userId);
            if (block != null && !block.isBlank()) {
                logger.debug("Bloco de memória injetado para o usuário {} ({} caracteres)", userId, block.length());
            }
            return block == null ? "" : block;
        } catch (RuntimeException exception) {
            logger.debug("Não foi possível montar o bloco de memória; seguindo sem ele", exception);
            return "";
        }
    }

    private String policyInstructions() {
        String mode = policyMode == null ? "maximum" : policyMode.trim().toLowerCase(Locale.ROOT);
        String resource =
                switch (mode) {
                    case "professional" -> "agent/policies/professional.md";
                    case "protected" -> "agent/policies/protected.md";
                    default -> "agent/policies/maximum.md";
                };
        return "\n\n" + localPolicyOverride(mode).orElseGet(() -> loadPolicyResource(resource));
    }

    private Optional<String> localPolicyOverride(String mode) {
        if (policyOverrideDirectory == null || policyOverrideDirectory.isBlank()) {
            return Optional.empty();
        }

        Path overridePath =
                Paths.get(policyOverrideDirectory).resolve(mode + ".md").normalize();
        if (!Files.isRegularFile(overridePath)) {
            return Optional.empty();
        }

        try {
            logger.info("Using local agent policy override from {}", overridePath);
            return Optional.of(Files.readString(overridePath, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            logger.warn(
                    "Could not read local agent policy override at {}; using bundled policy", overridePath, exception);
            return Optional.empty();
        }
    }

    private String loadPolicyResource(String resource) {
        try {
            ClassPathResource policyResource = new ClassPathResource(resource);
            try (var inputStream = policyResource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível carregar a política do agente: " + resource, exception);
        }
    }

    // The prompt and every file-tool description tell the model to use paths
    // "dentro de
    // [Workspace Roots]", but nothing ever showed the model what those roots
    // actually are —
    // it had no choice but to guess a plausible-looking placeholder path, which
    // then failed
    // workspace authorization on every attempt. This renders the literal [Workspace
    // Roots] block
    // the prompt already refers to, from the paths the frontend registered for this
    // request.
    private String workspaceRootsBlock(List<String> workspaceRoots) {
        if (workspaceRoots == null || workspaceRoots.isEmpty()) {
            // Mentioning an absent workspace anchors small local models on an irrelevant limitation,
            // even for ordinary conversation. File tools still enforce authorization in the backend.
            return "";
        }
        StringBuilder block = new StringBuilder("\n\n[Workspace Roots]\n");
        for (String root : workspaceRoots) {
            block.append("- ").append(root).append('\n');
        }
        block.append("Use SEMPRE um desses caminhos absolutos exatos (ou um arquivo dentro deles) como "
                + "argumento \"path\" das ferramentas de arquivo. NUNCA invente, adivinhe ou monte um "
                + "caminho parecido (ex: \"/Users/usuario/projetos/...\") — isso sempre falha. Se não "
                + "souber o caminho exato de um arquivo dentro da raiz, use directory_tree ou "
                + "search_files primeiro para descobrir.");
        return block.toString();
    }

    private String conversationContinuityBlock(ArrayNode messages) {
        String latestRequest = lastUserMessage(messages);
        if (latestRequest == null || !isGenericContinuationRequest(extractDirectUserRequest(latestRequest))) {
            return "";
        }

        boolean skippedLatest = false;
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (!"user".equals(message.path("role").asText(""))) {
                continue;
            }
            if (!skippedLatest) {
                skippedLatest = true;
                continue;
            }
            String request =
                    extractDirectUserRequest(message.path("content").asText("")).trim();
            if (request.isBlank()
                    || isGenericContinuationRequest(request)
                    || isCasualUserMessage(normalizeIntentText(request))) {
                continue;
            }
            String compactRequest = request.length() <= 1_200 ? request : request.substring(0, 1_200) + "...";
            return "\n\n[Conversation Continuity]\n"
                    + "A mensagem atual é uma continuação curta. Preserve este último pedido explícito como objetivo; "
                    + "não invente outro assunto:\n"
                    + compactRequest;
        }
        return "";
    }

    private boolean isGenericContinuationRequest(String request) {
        String normalized = normalizeIntentText(request);
        return containsAny(
                normalized,
                "continue",
                "continua",
                "prossiga",
                "pode seguir",
                "pode prosseguir",
                "segue",
                "tenta de novo",
                "tente de novo",
                "tenta agora",
                "testa de novo",
                "faz de novo",
                "faca de novo",
                "gera de novo",
                "gere de novo",
                "sobre o que estavamos falando",
                "sobre o que estavamos falando antes",
                "sobre o que a gente tava falando",
                "do que a gente tava falando",
                "do que estavamos falando",
                "o que estavamos falando",
                "qual era o assunto",
                "qual o assunto");
    }

    private ArrayNode compactMessagesForModel(ArrayNode messages) {
        ArrayNode compacted = mapper.createArrayNode();
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode message : messages) {
            String role = message.path("role").asText("");
            if (role.isBlank() || "system".equals(role)) {
                continue;
            }
            candidates.add(message);
        }

        int start = Math.max(0, candidates.size() - maxModelMessages);
        int totalChars = 0;
        for (int index = candidates.size() - 1; index >= start; index--) {
            JsonNode message = candidates.get(index);
            String content = message.path("content").asText("");
            int contentLength = Math.min(content.length(), maxMessageContentChars);
            if (totalChars + contentLength > maxTotalMessageContentChars && index != candidates.size() - 1) {
                start = index + 1;
                break;
            }
            totalChars += contentLength;
        }

        // Os turnos anteriores a `start` saem da janela do modelo — mas continuam íntegros no
        // Postgres (fonte da verdade). Em vez de sumir com eles, deixamos um resumo extrativo
        // curto para o modelo não perder o fio da conversa em sessões longas, sem inflar o prompt.
        if (start > 0) {
            String summary = summarizeDroppedTurns(candidates.subList(0, start));
            if (!summary.isBlank()) {
                ObjectNode note = mapper.createObjectNode();
                note.put("role", "system");
                note.put("content", summary);
                compacted.add(note);
            }
        }

        for (int index = start; index < candidates.size(); index++) {
            JsonNode message = candidates.get(index);
            boolean currentUserRequest = index == candidates.size() - 1
                    && "user".equals(message.path("role").asText(""));
            compacted.add(compactMessage(message, currentUserRequest));
        }
        return compacted;
    }

    // Resumo extrativo dos turnos que saíram da janela (sem custo de modelo): uma linha por turno,
    // com o começo do conteúdo achatado. O histórico completo continua no banco; aqui fica só o
    // suficiente para o modelo lembrar o que já foi pedido/feito sem estourar o num_ctx local.
    private static final int SUMMARY_MAX_TURNS = 12;
    private static final int SUMMARY_SNIPPET_CHARS = 140;
    private static final int SUMMARY_MAX_CHARS = 1200;

    private String summarizeDroppedTurns(List<JsonNode> dropped) {
        if (dropped.isEmpty()) {
            return "";
        }
        List<JsonNode> window = dropped.size() > SUMMARY_MAX_TURNS
                ? dropped.subList(dropped.size() - SUMMARY_MAX_TURNS, dropped.size())
                : dropped;
        StringBuilder summary =
                new StringBuilder("[Resumo da conversa anterior — histórico completo preservado no banco]\n");
        for (JsonNode message : window) {
            String role = message.path("role").asText("user");
            String label = "assistant".equals(role) ? "Avento" : "user".equals(role) ? "Usuário" : role;
            String snippet = summarySnippet(message.path("content").asText(""));
            if (snippet.isEmpty()) {
                continue;
            }
            String line = "- " + label + ": " + snippet + "\n";
            if (summary.length() + line.length() > SUMMARY_MAX_CHARS) {
                break;
            }
            summary.append(line);
        }
        return summary.toString().stripTrailing();
    }

    private String summarySnippet(String content) {
        if (content == null) {
            return "";
        }
        String flattened = content.replaceAll("\\s+", " ").strip();
        if (flattened.isEmpty()) {
            return "";
        }
        if (flattened.length() <= SUMMARY_SNIPPET_CHARS) {
            return flattened;
        }
        return flattened.substring(0, SUMMARY_SNIPPET_CHARS).stripTrailing() + "…";
    }

    private ObjectNode compactMessage(JsonNode message, boolean currentUserRequest) {
        ObjectNode compacted = mapper.createObjectNode();
        String role = message.path("role").asText("user");
        compacted.put("role", role);
        if (message.has("name")) {
            compacted.set("name", message.get("name"));
        }
        if (message.has("tool_call_id")) {
            compacted.set("tool_call_id", message.get("tool_call_id"));
        }
        if (message.has("tool_calls")) {
            compacted.set("tool_calls", message.get("tool_calls").deepCopy());
        }
        String content = message.path("content").asText("");
        compacted.put("content", currentUserRequest ? compactCurrentUserContent(content) : compactContent(content));
        if (message.has("images")
                && message.get("images").isArray()
                && message.get("images").size() > 0) {
            compacted.set("images", compactImages(message.get("images")));
        }
        return compacted;
    }

    private ArrayNode compactImages(JsonNode images) {
        ArrayNode compacted = mapper.createArrayNode();
        int count = 0;
        for (JsonNode image : images) {
            if (count >= 4) {
                break;
            }
            if (image.isTextual() && !image.asText("").isBlank()) {
                compacted.add(image.asText());
                count++;
            }
        }
        return compacted;
    }

    private String compactContent(String content) {
        if (content == null || content.length() <= maxMessageContentChars) {
            return content == null ? "" : content;
        }

        int headChars = maxMessageContentChars / 2;
        int tailChars = maxMessageContentChars - headChars;
        return content.substring(0, headChars)
                + "\n\n[... trecho antigo omitido pelo Avento para caber no contexto local ...]\n\n"
                + content.substring(content.length() - tailChars);
    }

    private String compactCurrentUserContent(String content) {
        if (content == null || content.length() <= maxMessageContentChars) {
            return content == null ? "" : content;
        }

        int requestStart = directUserRequestStart(content);
        if (requestStart < 0) {
            return content;
        }

        String request = content.substring(requestStart).trim();
        String separator = "\n\n[... contexto adicional compactado; pedido atual preservado ...]\n\n";
        if (request.isBlank()) {
            return content;
        }
        if (request.length() + separator.length() > maxMessageContentChars) {
            return request;
        }

        int contextBudget = maxMessageContentChars - request.length() - separator.length();
        String context =
                content.substring(0, Math.min(requestStart, contextBudget)).stripTrailing();
        return context + separator + request;
    }

    private String formatSize(long sizeBytes) {
        if (sizeBytes <= 0L) {
            return "";
        }
        double gib = sizeBytes / 1024.0 / 1024.0 / 1024.0;
        return String.format(Locale.ROOT, "%.1f GB", gib);
    }

    // Ollama's native /api/chat endpoint streams newline-delimited JSON (NDJSON),
    // one object per
    // network write, unlike the OpenAI-compatible /v1/chat/completions endpoint
    // which frames each
    // event as "data: {...}\n\n". We switched to /api/chat because Ollama silently
    // ignores
    // options.num_ctx on the OpenAI-compat endpoint, always loading the model's
    // default context
    // (4096 tokens) regardless of the configured avento.agent.num-ctx — which was
    // truncating
    // requests that included several tool schemas and causing the model to respond
    // with neither
    // text nor a tool call.
    //
    // WebClient's bodyToFlux(String.class) does not reliably preserve "\n" as a
    // delimiter here
    // (observed: each emitted chunk is already exactly one JSON object, with no
    // trailing
    // newline), so splitting on "\n" never finds anything and every chunk sits
    // unparsed in the
    // buffer until the stream ends — at which point multiple JSON objects have been
    // concatenated
    // with no separator, fail to parse as one document, and get silently dropped.
    // Instead, this
    // scans the buffered text for balanced-brace top-level JSON objects (tracking
    // string literals
    // so braces inside quoted content don't count), which is correct regardless of
    // whether a
    // given chunk is a whole line, a fragment of one, or several lines glued
    // together.
    /**
     * Envia a requisicao canonica pelo transporte do provedor ativo.
     *
     * <p>Sem transporte para o tipo ativo, cai no Ollama — que e o caminho local e o padrao.
     */
    /**
     * Contexto efetivo, em tokens, para a rodada.
     *
     * <p>O valor configurado deixa de ser chute: o provedor declara quanto o modelo aguenta (o
     * Ollama em {@code /api/show}, o Gemini em {@code inputTokenLimit}). Num modelo de janela grande
     * a gente truncava demais; num de janela pequena, estourava.
     *
     * <p>No LOCAL o declarado e teto, nao alvo: o qwen3.5:9b declara 262144, mas o KV cache disso
     * nao cabe nos 16GB desta maquina — quem manda continua sendo a configuracao. Na NUVEM quem
     * paga a memoria e o provedor, entao vale o que ele declara.
     */
    /** Orcamento de corte do resultado de ferramenta, proporcional a janela do modelo ativo. */
    int toolResultBudget(UUID userId) {
        int tokens = effectiveContextTokens(userId);
        // ~4 chars por token; no maximo um oitavo da janela para UM resultado, para nao engolir o
        // historico e os schemas.
        int proportional = Math.max(maxToolResultChars, tokens / 2);
        return Math.min(proportional, 40_000);
    }

    int effectiveContextTokens(UUID userId) {
        if (modelProviderService == null) {
            return numCtx;
        }
        int declared = modelProviderService.activeContextLimit(userId);
        if (declared <= 0) {
            return numCtx;
        }
        boolean remote = modelProviderService.remoteProviderReady(userId);
        return remote ? declared : Math.min(numCtx, declared);
    }

    private Flux<String> streamFromProvider(ObjectNode canonicalRequest, UUID userId) {
        com.avento.service.provider.ModelTransport transport = transportFor(userId);
        if (transport != null) {
            return transport.stream(
                    canonicalRequest,
                    modelProviderService.activeBaseUrl(userId),
                    modelProviderService.rawApiKey(userId));
        }
        return webClient
                .post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(canonicalRequest)
                .retrieve()
                .bodyToFlux(String.class);
    }

    private com.avento.service.provider.ModelTransport transportFor(UUID userId) {
        if (modelTransports == null || modelProviderService == null) {
            return null;
        }
        if (!modelProviderService.remoteProviderReady(userId)) {
            return null;
        }
        com.avento.service.provider.ProviderKind kind = modelProviderService.activeKind(userId);
        return modelTransports.stream()
                .filter(transport -> transport.kind() == kind)
                .findFirst()
                .orElse(null);
    }

    private void handleModelChunk(
            String chunk, FluxSink<String> sink, TurnCapture capture, AgentRunState state, String model) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        capture.lineBuffer.append(chunk);
        extractCompleteJsonObjects(
                capture.lineBuffer, line -> processOllamaChatLine(line, sink, capture, state, model));
    }

    private void flushPendingLine(TurnCapture capture, FluxSink<String> sink, AgentRunState state, String model) {
        extractCompleteJsonObjects(
                capture.lineBuffer, line -> processOllamaChatLine(line, sink, capture, state, model));
        String remaining = capture.lineBuffer.toString().trim();
        capture.lineBuffer.setLength(0);
        if (!remaining.isEmpty()) {
            processOllamaChatLine(remaining, sink, capture, state, model);
        }
    }

    private void extractCompleteJsonObjects(StringBuilder buffer, Consumer<String> onObject) {
        String pending = buffer.toString();
        int consumed = 0;
        int objectStart = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < pending.length(); i++) {
            char c = pending.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    onObject.accept(pending.substring(objectStart, i + 1));
                    consumed = i + 1;
                    objectStart = -1;
                }
            }
        }

        if (consumed > 0) {
            buffer.delete(0, consumed);
        }
    }

    private void processOllamaChatLine(
            String line, FluxSink<String> sink, TurnCapture capture, AgentRunState state, String model) {
        if (line == null || line.isEmpty()) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(line);
            JsonNode message = node.path("message");
            // Newer Ollama versions stream reasoning-model output (qwen3, etc.) in a separate
            // message.thinking field instead of inline <think> tags inside message.content. The
            // frontend's chunk parser already knows how to pull <think>...</think> segments out of
            // the content stream (useChatStream.ts), so re-wrapping thinking text the same way
            // reuses that UI for free — without this, thinking tokens were silently dropped and a
            // turn that spent its whole budget reasoning showed nothing at all, despite Ollama
            // reporting real eval_count/duration.
            if (message.has("thinking") && !message.get("thinking").isNull()) {
                String thinking = message.get("thinking").asText();
                if (!thinking.isEmpty()) {
                    sink.next(contentChunk("<think>" + thinking + "</think>"));
                }
            }
            if (message.has("content") && !message.get("content").isNull()) {
                String content = message.get("content").asText();
                if (!content.isEmpty()) {
                    capture.assistantText.append(content);
                    if (!capture.deferAssistantOutput && !shouldSuppressTextualToolMarkup(capture, content)) {
                        sink.next(contentChunk(content));
                    }
                }
            }

            if (message.has("tool_calls")) {
                captureNativeToolCalls(message.get("tool_calls"), capture.nativeToolCalls);
            }

            // O frontend estimava tokens contando chunks de streaming recebidos,
            // que nao tem relacao real com token count (um chunk pode ter zero,
            // um ou varios tokens). O Ollama reporta o numero real gerado nesta
            // rodada em eval_count, na ultima linha (done=true) de cada turno.
            if (node.path("done").asBoolean(false) && node.has("eval_count")) {
                int promptTokens = node.path("prompt_eval_count").asInt(0);
                int completionTokens = node.path("eval_count").asInt(0);
                sink.next(tokenUsageEventChunk(completionTokens));
                tokenUsageService.record(
                        state.userId,
                        state.chatId,
                        state.runId,
                        node.path("model").asText(model),
                        promptTokens,
                        completionTokens);
            }
        } catch (Exception e) {
            logger.debug("Ignoring invalid Ollama chat line: {}", line, e);
        }
    }

    private String tokenUsageEventChunk(int evalCount) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode event = root.putObject("avento_event");
        event.put("type", "agent.tokens.usage");
        event.put("title", "Uso de tokens");
        event.put("detail", evalCount + " tokens gerados nesta rodada");
        event.put("evalCount", evalCount);
        event.put("timestamp", LocalDateTime.now().toString());
        return root.toString();
    }

    private void handleStreamError(
            String model, ArrayNode messages, AgentRunState state, int round, FluxSink<String> sink, Throwable error) {
        if (isToolsUnsupportedError(error) && modelsWithoutToolSupport.add(model)) {
            if (!conversationHasImages(messages)) {
                sink.next(eventChunk(
                        "agent.model.tools_unsupported",
                        "Modelo sem suporte a ferramentas",
                        "O modelo " + model + " não suporta chamadas de ferramenta; continuando somente em texto."));
                sink.next(contentChunk(
                        "\n> O modelo `" + model + "` não suporta chamadas de ferramenta (abrir apps, rodar comandos,"
                                + " editar arquivos). Vou continuar só em texto. Para automação, troque para `"
                                + defaultChatModel + "` no seletor de modelo.\n"));
            }
            forward(runTurn(model, messages, state, round), sink, state);
            return;
        }

        // Resposta HTTP de erro nao precisa de stack trace: o Reactor despeja dezenas de linhas de
        // operador interno que nao dizem nada sobre a causa, e enterram o unico dado util — status e
        // corpo. Um 429 esperado enchia o log de ruido.
        if (error instanceof WebClientResponseException httpError) {
            String body = httpError.getResponseBodyAsString();
            logger.warn(
                    "Model stream failed for {}: HTTP {} {}",
                    model,
                    httpError.getStatusCode().value(),
                    body.length() > 300 ? body.substring(0, 300) + "…" : body);
        } else {
            logger.warn("Model stream failed for {}", model, error);
        }

        String message = "Falha ao conversar com a IA.";
        if (error instanceof TimeoutException) {
            message = "O modelo " + model
                    + " ficou mais de 2 minutos sem gerar nada. Pode ser contexto grande demais nesta"
                    + " conversa ou a máquina sobrecarregada — tente numa conversa nova ou com um pedido"
                    + " mais simples.";
        } else if (error instanceof WebClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            message = "O provedor retornou HTTP "
                    + responseException.getStatusCode().value();
            if (body != null && !body.isBlank()) {
                message += ": " + body;
            }
        } else if (error.getMessage() != null && !error.getMessage().isBlank()) {
            message = error.getMessage();
        }

        // "local" era mentira quando o provedor e remoto — e mandava o usuario procurar o problema
        // no Ollama enquanto ele estava no Gemini.
        boolean remote = modelProviderService != null && modelProviderService.remoteProviderReady(state.userId);
        String origem = remote ? modelProviderService.activeKind(state.userId).name() : "local";

        sink.next(eventChunk("agent.error", "Falha no modelo " + origem, message));
        sink.next(contentChunk("\n> Erro ao chamar o modelo " + origem + " `" + model + "`: " + message + "\n"));
        // Um 404 aqui quer dizer que o modelo nao existe (ou foi aposentado) para esta conta, e a
        // mensagem sozinha nao diz qual usar. Perguntar ao provedor transforma o beco num caminho.
        if (remote && message.contains("404")) {
            sink.next(contentChunk(availableModelsHint(state.userId)));
        }
        if (remote && message.contains("429")) {
            sink.next(contentChunk(quotaHint(message, model)));
            // Cota zero significa "escolha outro" — e sem a lista, escolher e tentativa e erro.
            if (message.contains("limit: 0")) {
                sink.next(contentChunk(availableModelsHint(state.userId)));
            }
        }
        sink.complete();
    }

    /**
     * Resume um 429 do provedor.
     *
     * <p>O corpo vem como um muro de JSON com violacoes repetidas e links; o que importa e se a cota
     * e ZERO (modelo fora do plano, e esperar nao resolve) ou se e limite temporario, e em quanto
     * tempo tentar de novo.
     */
    static String quotaHint(String message, String model) {
        boolean semCota = message.contains("limit: 0");
        String espera = "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"")
                .matcher(message);
        if (matcher.find()) {
            espera = matcher.group(1);
        }

        if (semCota) {
            return "\n> ⚠️ O modelo `" + model + "` tem cota **zero** no seu plano — nao e limite"
                    + " atingido, e modelo indisponivel na conta. No Gemini, os modelos **pro**"
                    + " costumam exigir faturamento habilitado; os **flash** sao os do plano"
                    + " gratuito. Troque em Configuracoes > Modelos & Provedores, ou habilite"
                    + " faturamento no Google.\n";
        }
        return "\n> ⚠️ Limite de uso do provedor atingido"
                + (espera.isBlank() ? "" : "; tente de novo em " + espera + "s")
                + ".\n";
    }

    /** Lista os modelos que o provedor realmente oferece, para o 404 virar instrucao. */
    private String availableModelsHint(UUID userId) {
        try {
            JsonNode listing = modelProviderService.listAvailableModels(userId);
            List<String> names = new ArrayList<>();
            for (JsonNode model : listing.path("data")) {
                String id = model.path("id").asText("");
                if (!id.isBlank()) {
                    names.add(id);
                }
            }
            if (names.isEmpty()) {
                return "\n> Nao consegui listar os modelos disponiveis. Verifique a chave em"
                        + " Configuracoes > Modelos & Provedores.\n";
            }
            return "\n> Modelos disponiveis nesta conta: "
                    + String.join(", ", names.subList(0, Math.min(8, names.size())))
                    + (names.size() > 8 ? ", e mais " + (names.size() - 8) : "")
                    + ".\n> Escolha um deles em Configuracoes > Modelos & Provedores.\n";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private boolean isToolsUnsupportedError(Throwable error) {
        if (!(error instanceof WebClientResponseException responseException)) {
            return false;
        }
        String body = responseException.getResponseBodyAsString();
        return body != null && body.contains("does not support tools");
    }

    // Ollama's native /api/chat sends each tool call whole in a single message
    // (arguments as a
    // parsed JSON object), unlike the OpenAI-compatible endpoint's incremental
    // delta.tool_calls
    // streaming (arguments as a partial string built up across chunks).
    // detectToolCalls() still
    // expects function.arguments() as a JSON string, so it's serialized once here to
    // keep that
    // downstream parsing unchanged.
    private void captureNativeToolCalls(JsonNode toolCalls, List<ObjectNode> nativeToolCalls) {
        for (JsonNode toolCall : toolCalls) {
            JsonNode functionNode = toolCall.path("function");
            String name = functionNode.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }

            ObjectNode call = mapper.createObjectNode();
            call.put("id", toolCall.has("id") ? toolCall.get("id").asText() : "call_" + UUID.randomUUID());
            call.put("type", "function");

            ObjectNode function = call.putObject("function");
            function.put("name", name);
            JsonNode argumentsNode = functionNode.path("arguments");
            function.put("arguments", argumentsNode.isTextual() ? argumentsNode.asText() : argumentsNode.toString());

            nativeToolCalls.add(call);
        }
    }

    private void finishTurn(
            String model,
            ArrayNode messages,
            AgentRunState state,
            int round,
            FluxSink<String> sink,
            TurnCapture capture) {
        List<ToolCall> toolCalls = detectToolCalls(capture, state);
        if (toolCalls.isEmpty()) {
            planApprovedRuns.remove(state.runId);
            // Turno completamente vazio: o modelo gastou a rodada no thinking e terminou sem
            // texto E sem chamada de ferramenta (comportamento observado do qwen3). O retry
            // com toolset completo abaixo nao cobre chats com [Project Analysis] por design,
            // entao sem esta guarda a run completava em silencio absoluto. Uma unica nova
            // tentativa, com o pedido original repetido e uma instrucao explicita de agir.
            boolean emptyTurn = capture.assistantText.toString().isBlank();
            // Anunciar sem executar falha igual ao turno vazio, e para o usuario e pior: parece que
            // algo esta acontecendo. A guarda antiga exigia texto em branco e deixava passar.
            boolean announcedOnly = announcedActionWithoutCalling(
                    capture.assistantText.toString(), false, !state.availableToolNames.isEmpty());
            if ((emptyTurn || announcedOnly) && !state.retriedEmptyTurn) {
                state.retriedEmptyTurn = true;
                sink.next(
                        eventChunk(
                                "agent.round.retry",
                                emptyTurn
                                        ? "Resposta vazia — tentando de novo"
                                        : "Ação anunciada sem execução — tentando de novo",
                                emptyTurn
                                        ? "O modelo terminou a rodada sem texto e sem ferramenta; repetindo com instrução explícita."
                                        : "O modelo disse que ia agir mas não chamou nenhuma ferramenta; repetindo com instrução explícita."));
                String originalRequest = lastUserMessage(messages);
                ArrayNode nudged = messages.deepCopy();
                ObjectNode nudge = nudged.addObject();
                nudge.put("role", "user");
                nudge.put(
                        "content",
                        (originalRequest == null || originalRequest.isBlank() ? "" : originalRequest + "\n\n")
                                + (emptyTurn
                                        ? "[Aviso do Avento] Sua resposta anterior terminou vazia: sem texto e sem"
                                                + " chamada de ferramenta."
                                        : "[Aviso do Avento] Você ANUNCIOU que ia agir mas não chamou ferramenta"
                                                + " nenhuma, então nada aconteceu. Não anuncie de novo.")
                                + " Responda agora de forma útil — chame a ferramenta adequada para"
                                + " executar o pedido acima, ou dê a resposta final em texto.");
                forward(runTurn(model, nudged, state, round + 1), sink, state);
                return;
            }
            if (emptyTurn) {
                sink.next(contentChunk("\n> ⚠️ O modelo encerrou o turno sem produzir resposta, mesmo após uma nova"
                        + " tentativa. Tente reformular o pedido ou continuar em uma conversa nova.\n"));
            }
            if (shouldRetryWithFullToolset(state, round, messages)) {
                state.retriedWithFullToolset = true;
                state.forceFullToolset = true;
                sink.next(eventChunk(
                        "agent.round.retry",
                        "Tentando com todas as ferramentas",
                        "Nenhuma ferramenta foi usada na primeira tentativa; tentando de novo com o conjunto"
                                + " completo."));
                forward(runTurn(model, messages, state, round + 1), sink, state);
                return;
            }
            emitDeferredAssistantText(capture, sink);
            // O modelo pode descrever uma acao como concluida em texto (com
            // checkmark, "criado com sucesso" etc.) sem ter chamado nenhuma
            // ferramenta de verdade — ja aconteceu, e nao da pra impedir isso
            // so por instrucao de prompt. Em vez de tentar detectar a mentira
            // no texto (fragil), avisa sempre que o pedido era acionavel e o
            // turno inteiro (com retry ja tentado) executou zero ferramentas.
            if (shouldWarnAboutNoToolExecution(state, messages)) {
                sink.next(eventChunk(
                        "agent.no_tool_warning",
                        "Nenhuma ação confirmada",
                        "A resposta terminou sem executar nenhuma ferramenta real."));
            }
            sink.next(eventChunk(
                    "agent.round.completed", "Resposta final pronta", "Nenhuma ferramenta adicional foi solicitada."));
            sink.complete();
            return;
        }

        if (shouldIgnoreToolCallsForCasualMessage(messages)) {
            sink.next(eventChunk(
                    "tool.ignored", "Ferramenta ignorada", "Mensagem casual curta nao deve acionar ferramentas."));
            sink.next(contentChunk("\nOi! Estou por aqui. Como posso te ajudar?\n"));
            sink.complete();
            return;
        }

        int effectiveMaxRounds = state.maxToolRoundsOverride != null && state.maxToolRoundsOverride > 0
                ? state.maxToolRoundsOverride
                : maxToolRounds;
        if (round > effectiveMaxRounds || state.executedToolCalls >= maxToolCalls) {
            planApprovedRuns.remove(state.runId);
            // Antes daqui saía só "Limite atingido" e sink.complete(): minutos de leitura de arquivo
            // eram descartados e o usuário ficava com a narração e nenhuma análise. Agora o limite
            // corta as FERRAMENTAS, não a resposta — uma última rodada sem toolset pedindo o
            // fechamento com o que já foi coletado. O flag garante que isso acontece uma só vez.
            if (!state.finalSynthesis) {
                state.finalSynthesis = true;
                sink.next(eventChunk(
                        "agent.limit.reached",
                        "Limite de ferramentas atingido",
                        "Fechando com o que já foi coletado, sem novas ferramentas."));
                ArrayNode closing = messages.deepCopy();
                ObjectNode instruction = closing.addObject();
                instruction.put("role", "user");
                instruction.put(
                        "content",
                        "[Aviso do Avento] Você atingiu o limite de ferramentas desta resposta e não pode"
                                + " chamar mais nenhuma. Responda AGORA, em texto, ao pedido original, usando"
                                + " apenas o que já leu nesta conversa. Entregue as conclusões que conseguir"
                                + " sustentar e diga explicitamente o que ficou por verificar. Não anuncie"
                                + " próximos passos nem prometa continuar.");
                forward(runTurn(model, closing, state, round + 1), sink, state);
                return;
            }
            sink.next(contentChunk("\n> Limite de ferramentas atingido.\n"));
            sink.complete();
            return;
        }

        appendAssistantToolRequest(messages, capture);

        boolean mediaGenerationAttempted = false;
        boolean mediaGenerationCompleted = false;
        for (ToolCall toolCall : toolCalls) {
            toolCall = withImageModel(toolCall, state.imageModel);
            toolCall = withImageOptions(toolCall, state.imageOptions);
            toolCall = withExecutionContext(toolCall, state.chatId, state.userId, state.runId);
            if (state.executedToolCalls >= maxToolCalls) {
                sink.next(eventChunk(
                        "agent.limit.reached",
                        "Limite total de ferramentas atingido",
                        "O Avento parou antes de chamar novas ferramentas."));
                sink.next(contentChunk("\n> Limite total de ferramentas atingido.\n"));
                break;
            }

            if (usesFilesystemRoot(toolCall)) {
                planApprovedRuns.remove(state.runId);
                sink.next(
                        eventChunk("tool.rejected", "Ferramenta rejeitada", toolCall.name() + " tentou usar path /."));
                sink.next(
                        contentChunk(
                                "\nNão vou usar `/` como caminho de ferramenta. Se você quiser que eu analise arquivos, selecione ou informe uma pasta de projeto autorizada.\n"));
                sink.complete();
                return;
            }

            if (requiresApproval(toolCall.name())) {
                boolean planApproved = planApprovedRuns.contains(state.runId) && !isAlwaysConfirmToolCall(toolCall);
                if (permissionService.canAutoApprove(
                                state.runId,
                                state.userId,
                                toolCall.name(),
                                permissionArguments(toolCall),
                                state.workspaceRoots)
                        || planApproved) {
                    timelineService.record(
                            state.runId,
                            "tool.permission.auto_approved",
                            toolCall.name(),
                            planApproved
                                    ? "Plano ja aprovado nesta resposta."
                                    : "Permissao salva aplicada automaticamente.",
                            toolCall.arguments());
                    state.executedToolCalls++;
                    JsonNode toolResult = executeToolCall(messages, sink, toolCall, state.runId, state.userId);
                    recordToolOutcome(state, toolCall, toolResult);
                    emitGeneratedMediaCompletion(toolCall, toolResult, sink);
                    if (isMediaGenerationTool(toolCall)) {
                        mediaGenerationAttempted = true;
                        emitMediaGenerationFailure(toolCall, toolResult, sink);
                    }
                    mediaGenerationCompleted |= isSuccessfulMediaGeneration(toolCall, toolResult);
                    continue;
                }
                requestToolApproval(model, messages, state, round, toolCall, true, sink);
                sink.complete();
                return;
            }

            state.executedToolCalls++;
            JsonNode toolResult = executeToolCall(messages, sink, toolCall, state.runId, state.userId);
            recordToolOutcome(state, toolCall, toolResult);
            emitGeneratedMediaCompletion(toolCall, toolResult, sink);
            if (isMediaGenerationTool(toolCall)) {
                mediaGenerationAttempted = true;
                emitMediaGenerationFailure(toolCall, toolResult, sink);
            }
            mediaGenerationCompleted |= isSuccessfulMediaGeneration(toolCall, toolResult);
        }

        if (mediaGenerationAttempted) {
            planApprovedRuns.remove(state.runId);
            sink.next(
                    eventChunk(
                            "agent.round.completed",
                            mediaGenerationCompleted ? "Mídia gerada" : "Geração de mídia encerrada",
                            mediaGenerationCompleted
                                    ? "A geração foi concluída pela ferramenta; nenhuma resposta adicional do modelo foi necessária."
                                    : "A ferramenta retornou um erro técnico; nenhuma explicação inventada pelo modelo foi adicionada."));
            sink.complete();
            return;
        }

        // Guarda: se a mesma ferramenta falhou repetidas vezes seguidas, para e explica em vez de
        // insistir por mais rodadas (cada rodada custa ~50s no modelo local). Melhor um "não deu"
        // rápido e claro do que ficar batendo numa ferramenta indisponível.
        if (state.consecutiveToolFailures >= REPEATED_TOOL_FAILURE_LIMIT) {
            planApprovedRuns.remove(state.runId);
            String failedTool = state.lastFailedTool;
            sink.next(eventChunk(
                    "agent.tool.repeated_failure",
                    "Ferramenta falhando repetidamente",
                    "A ferramenta " + failedTool + " falhou " + state.consecutiveToolFailures
                            + " vezes seguidas; parando para não insistir."));
            sink.next(contentChunk("\n> A ferramenta `" + failedTool + "` falhou "
                    + state.consecutiveToolFailures
                    + " vezes seguidas — provavelmente está indisponível ou não conectada. Parei aqui em vez"
                    + " de insistir. Verifique essa ferramenta ou me peça por outro caminho.\n"));
            sink.complete();
            return;
        }

        if (state.consecutiveIdenticalToolCalls >= 2) {
            String toolName = state.lastToolCallSignature.contains(":")
                    ? state.lastToolCallSignature.substring(0, state.lastToolCallSignature.indexOf(':'))
                    : state.lastToolCallSignature;
            ObjectNode nudge = messages.addObject();
            nudge.put("role", "user");
            nudge.put(
                    "content",
                    "[Aviso de Orientação do Avento] A ação `" + toolName
                            + "` já foi executada e confirmada com sucesso neste passo. Não repita esta mesma chamada nem o texto introdutório. Avance para a próxima ação necessária ou conclua a tarefa fornecendo a resposta dos resultados.");
        }

        forward(runTurn(model, messages, state, round + 1), sink, state);
    }

    // Rede de seguranca contra falso negativo do filtro de intencao (Opcao 2):
    // se a primeira rodada nao chamou nenhuma ferramenta para uma mensagem que
    // nao e conversa casual, tenta de novo uma unica vez com todas as
    // ferramentas visiveis, em vez de assumir que o modelo decidiu nao agir.
    private boolean shouldRetryWithFullToolset(AgentRunState state, int round, ArrayNode messages) {
        if (round != 1 || state.retriedWithFullToolset || state.forceFullToolset) {
            return false;
        }
        String lastUserMessage = lastUserMessage(messages);
        if (lastUserMessage == null || lastUserMessage.contains("[Project Analysis]")) {
            return false;
        }
        String normalized = normalizeIntentText(extractDirectUserRequest(lastUserMessage));
        return isActionableToolRequest(normalized);
    }

    // state.executedToolCalls só sobe quando uma ferramenta é de fato
    // executada (nunca em aprovação rejeitada) — é a única fonte confiável
    // pra saber se "algo aconteceu de verdade" nesta resposta, ao contrário
    // do texto do modelo, que pode alegar sucesso sem ter feito nada.
    private boolean shouldWarnAboutNoToolExecution(AgentRunState state, ArrayNode messages) {
        if (state.executedToolCalls > 0) {
            return false;
        }
        String lastUserMessage = lastUserMessage(messages);
        if (lastUserMessage == null) {
            return false;
        }
        String normalized = normalizeIntentText(extractDirectUserRequest(lastUserMessage));
        return isActionableToolRequest(normalized);
    }

    private boolean isActionableToolRequest(String normalizedMessage) {
        if (normalizedMessage == null || normalizedMessage.isBlank()) {
            return false;
        }
        if (wantsImageGeneration(normalizedMessage) || wantsScreenCapture(normalizedMessage)) {
            return true;
        }
        for (String actionWord : PROJECT_ACTION_WORDS) {
            if (normalizedMessage.contains(actionWord)) {
                return true;
            }
        }
        return false;
    }

    private List<ToolCall> detectToolCalls(TurnCapture capture, AgentRunState state) {
        List<ToolCall> detectedTools = new ArrayList<>();
        for (ObjectNode nativeCall : capture.nativeToolCalls) {
            try {
                ObjectNode function = (ObjectNode) nativeCall.get("function");
                String name = function.get("name").asText();
                JsonNode arguments = mapper.readTree(function.get("arguments").asText());
                String id = nativeCall.has("id") ? nativeCall.get("id").asText() : "call_" + UUID.randomUUID();
                detectedTools.add(new ToolCall(id, name, arguments));
            } catch (Exception e) {
                logger.warn("Ignoring invalid native tool call: {}", nativeCall, e);
            }
        }

        if (detectedTools.isEmpty()) {
            detectedTools.addAll(detectTextualFallbackToolCalls(capture, state));
        }

        return detectedTools;
    }

    // Marca o INICIO de um objeto JSON que parece uma chamada de ferramenta escrita como texto.
    // O objeto completo (com aninhamento) e extraido por contagem de chaves em
    // extractBalancedJson — uma regex nao fecha chaves aninhadas: o padrao antigo ([^}]+\})
    // parava na PRIMEIRA '}', truncando {"tool":"fetch","argument":{"url":...}} e descartando
    // silenciosamente exatamente as chamadas que o modelo pequeno mais produz.
    private static final Pattern PSEUDO_JSON_TOOL_START =
            Pattern.compile("\\{\\s*\"(?:tool|name|action|function)\"\\s*:");

    private List<ToolCall> detectTextualFallbackToolCalls(TurnCapture capture, AgentRunState state) {
        List<ToolCall> fallbackCalls = new ArrayList<>();
        String fullText = capture.assistantText.toString();
        Set<String> knownTools = knownToolNamesForRound(state);
        Matcher functionMatcher = TEXTUAL_FUNCTION_PATTERN.matcher(fullText);
        if (functionMatcher.find()) {
            try {
                String toolName = functionMatcher.group(1);
                JsonNode parsed = mapper.readTree(functionMatcher.group(2));
                JsonNode arguments = extractArguments(parsed);
                fallbackCalls.add(registerFallbackCall(capture, toolName, arguments, "call_textual_"));
                return fallbackCalls;
            } catch (Exception e) {
                logger.debug("Invalid textual function tool call detected", e);
            }
        }

        Matcher startMatcher = PSEUDO_JSON_TOOL_START.matcher(fullText);
        while (startMatcher.find()) {
            String jsonCandidate = extractBalancedJson(fullText, startMatcher.start());
            if (jsonCandidate == null) {
                continue;
            }
            try {
                JsonNode parsed = mapper.readTree(jsonCandidate);
                String toolName = firstNonBlankText(parsed, "tool", "name", "action", "function");

                if (toolName.isBlank()) {
                    continue;
                }
                // Valida contra o catalogo DESTA rodada (locais + MCP externas conectadas), nao
                // contra o registro local: o registro nao conhece fetch/git_*/puppeteer_*, entao
                // toda chamada textual de ferramenta externa era descartada em silencio — o
                // sintoma classico do "escreveu o JSON e nada aconteceu".
                if (!knownTools.contains(toolName)) {
                    logger.info(
                            "Textual tool call dropped: '{}' is not in this round's catalog ({} tools known)",
                            toolName,
                            knownTools.size());
                    continue;
                }

                fallbackCalls.add(registerFallbackCall(capture, toolName, extractArguments(parsed), "call_fallback_"));
            } catch (Exception e) {
                logger.debug("No textual fallback tool call detected for candidate: {}", jsonCandidate, e);
            }
        }

        if (fallbackCalls.isEmpty() && startMatcher.reset().find()) {
            logger.info("Assistant text contained tool-like JSON but no executable call was recovered");
        }
        return fallbackCalls;
    }

    // Modelos pequenos variam o envelope: arguments, parameters, argument, args ou os campos
    // soltos no objeto raiz. Aceitar todos custa nada e evita descartar a intencao correta.
    private JsonNode extractArguments(JsonNode parsed) {
        for (String wrapper : List.of("arguments", "parameters", "argument", "args", "input", "params")) {
            if (parsed.has(wrapper) && parsed.get(wrapper).isObject()) {
                return parsed.get(wrapper);
            }
        }
        ObjectNode args = parsed.deepCopy();
        args.remove("tool");
        args.remove("name");
        args.remove("action");
        args.remove("function");
        return args;
    }

    private String firstNonBlankText(JsonNode parsed, String... fields) {
        for (String field : fields) {
            String value = parsed.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private ToolCall registerFallbackCall(TurnCapture capture, String toolName, JsonNode arguments, String idPrefix) {
        String id = idPrefix + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode nativeCall = mapper.createObjectNode();
        nativeCall.put("id", id);
        nativeCall.put("type", "function");
        ObjectNode function = mapper.createObjectNode();
        function.put("name", toolName);
        function.put("arguments", arguments.toString());
        nativeCall.set("function", function);
        capture.nativeToolCalls.add(nativeCall);
        logger.info("Recovered textual tool call as native: {} {}", toolName, compactJson(arguments));
        return new ToolCall(id, toolName, arguments);
    }

    // Catalogo de referencia do fallback: o da rodada quando existir; senao (testes, caminhos
    // antigos) o registro local — comportamento anterior preservado como piso.
    private Set<String> knownToolNamesForRound(AgentRunState state) {
        if (state != null && state.availableToolNames != null && !state.availableToolNames.isEmpty()) {
            return state.availableToolNames;
        }
        Set<String> registered = new HashSet<>();
        toolRegistry.all().forEach(tool -> registered.add(tool.name()));
        return registered;
    }

    // Extrai o objeto JSON completo a partir de openIndex ('{'), respeitando aninhamento e
    // strings (chaves dentro de "..." nao contam). Devolve null se o objeto nunca fecha.
    static String extractBalancedJson(String text, int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = openIndex; index < text.length(); index++) {
            char current = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(openIndex, index + 1);
                }
            }
        }
        return null;
    }

    private boolean shouldSuppressTextualToolMarkup(TurnCapture capture, String content) {
        String normalized = content.trim().toLowerCase(Locale.ROOT);
        if (capture.suppressTextualToolMarkup) {
            return true;
        }
        if (normalized.contains("{function")
                || normalized.contains("function <")
                || normalized.matches("^\\{\\s*\"name\"\\s*:.*")
                || normalized.matches("^\\{\\s*\"tool\"\\s*:.*")
                || normalized.matches("^\\{\\s*\"action\"\\s*:.*")
                || normalized.matches("^\\{\\s*\"parameters\"\\s*:.*")
                || normalized.matches("^\\{\\s*\"arguments\"\\s*:.*")) {
            capture.suppressTextualToolMarkup = true;
            return true;
        }
        return false;
    }

    private void emitDeferredAssistantText(TurnCapture capture, FluxSink<String> sink) {
        if (!capture.deferAssistantOutput || capture.assistantText.isEmpty()) {
            return;
        }
        String content = capture.assistantText.toString();
        if (!shouldSuppressTextualToolMarkup(capture, content)) {
            sink.next(contentChunk(content));
        }
    }

    private boolean usesFilesystemRoot(ToolCall toolCall) {
        JsonNode path = toolCall.arguments().path("path");
        return path.isTextual() && "/".equals(path.asText().trim());
    }

    private boolean isAlwaysConfirmToolCall(ToolCall toolCall) {
        if (ALWAYS_CONFIRM_TOOLS.contains(toolCall.name())) {
            return true;
        }
        if (!"terminal_run".equals(toolCall.name()) && !"terminal_start".equals(toolCall.name())) {
            return false;
        }
        JsonNode command = toolCall.arguments().path("command");
        return command.isTextual()
                && DESTRUCTIVE_TERMINAL_COMMAND.matcher(command.asText().trim()).matches();
    }

    // O modelo local pode incluir seu proprio "model" no tool call de generate_image (a
    // ferramenta descreve isso como opcional), e antes isso silenciosamente vencia o seletor de
    // imagem da UI — o usuario escolhia um modelo ComfyUI no dropdown e o LLM trocava por outra
    // coisa na hora de montar a chamada, sem avisar. O dropdown e a escolha explicita do usuario;
    // ele agora sempre vence quando presente. So cai pro que o modelo especificar (ou pro default
    // do backend) quando nenhum modelo foi selecionado na UI.
    private ToolCall withImageModel(ToolCall toolCall, String imageModel) {
        if (toolCall == null
                || !"generate_image".equals(toolCall.name())
                || imageModel == null
                || imageModel.isBlank()) {
            return toolCall;
        }
        ObjectNode arguments = toolCall.arguments().deepCopy();
        arguments.put("model", imageModel.trim());
        return new ToolCall(toolCall.id(), toolCall.name(), arguments);
    }

    private ToolCall withImageOptions(ToolCall toolCall, ImageGenerationOptions imageOptions) {
        if (toolCall == null || !"generate_image".equals(toolCall.name())) {
            return toolCall;
        }
        ImageGenerationOptions options = imageOptions == null ? ImageGenerationOptions.defaults() : imageOptions;
        ObjectNode arguments = toolCall.arguments().deepCopy();
        arguments.put("qualityPreset", options.qualityPreset());
        arguments.put("aspectRatio", options.aspectRatio());
        arguments.put("subjectType", options.subjectType());
        arguments.put("size", options.size());
        arguments.put("subjectCount", options.subjectCount());
        arguments.put("enhancePrompt", options.enhancePrompt());
        arguments.put("refinementEnabled", options.refinementEnabled());
        arguments.put("refinementStrength", options.refinementStrength());
        arguments.put("detailMode", options.detailMode());
        arguments.put("referenceStrength", options.referenceStrength());
        arguments.put("poseStrength", options.poseStrength());
        if (options.cfgScale() == null) {
            arguments.remove("cfgScale");
        } else {
            arguments.put("cfgScale", options.cfgScale());
        }
        if (options.hasPoseReference()) {
            arguments.put("poseReferenceDataUrl", options.poseReferenceDataUrl());
        } else {
            arguments.remove("poseReferenceDataUrl");
        }
        if (options.hasReferenceImage()) {
            arguments.put("referenceImageDataUrl", options.referenceImageDataUrl());
        } else {
            arguments.remove("referenceImageDataUrl");
        }
        if (options.seed() == null) {
            arguments.remove("seed");
        } else {
            arguments.put("seed", options.seed());
        }
        return new ToolCall(toolCall.id(), toolCall.name(), arguments);
    }

    private ToolCall withExecutionContext(ToolCall toolCall, Long chatId, UUID userId) {
        return withExecutionContext(toolCall, chatId, userId, "");
    }

    private ToolCall withExecutionContext(ToolCall toolCall, Long chatId, UUID userId, String runId) {
        if (toolCall == null || userId == null) {
            return toolCall;
        }
        ObjectNode arguments = toolCall.arguments().deepCopy();
        if (chatId != null) {
            arguments.put("_chatId", chatId);
        }
        arguments.put("_userId", userId.toString());
        if (runId != null && !runId.isBlank()) {
            arguments.put("_runId", runId);
        }
        return new ToolCall(toolCall.id(), toolCall.name(), arguments);
    }

    private JsonNode permissionArguments(ToolCall toolCall) {
        if (toolCall == null || !toolCall.arguments().isObject()) {
            return toolCall == null ? mapper.createObjectNode() : toolCall.arguments();
        }
        ObjectNode arguments = toolCall.arguments().deepCopy();
        arguments.remove("_chatId");
        arguments.remove("_userId");
        arguments.remove("_runId");
        if (arguments.has("poseReferenceDataUrl")) {
            arguments.remove("poseReferenceDataUrl");
            arguments.put("poseReferenceAttached", true);
        }
        if (arguments.has("referenceImageDataUrl")) {
            arguments.remove("referenceImageDataUrl");
            arguments.put("referenceImageAttached", true);
        }
        return arguments;
    }

    private boolean requiresApproval(String toolName) {
        return toolRegistry.requiresApproval(toolName);
    }

    private Flux<String> requestDirectToolApproval(
            String model, ArrayNode messages, ToolCall toolCall, List<String> workspaceRoots, String runId) {
        return Flux.create(sink -> {
            AgentRunState state = new AgentRunState();
            state.runId = runId;
            state.workspaceRoots = workspaceRoots;
            requestToolApproval(model, messages, state, 1, toolCall, false, sink);
            sink.complete();
        });
    }

    private Flux<String> executeDirectTool(ArrayNode messages, ToolCall toolCall, String runId) {
        return Flux.create(sink -> {
            JsonNode toolResult = executeToolCall(messages, sink, toolCall, runId, null);
            sink.next(contentChunk(directToolCompletionMessage(toolCall, toolResult)));
            sink.complete();
        });
    }

    private void requestToolApproval(
            String model,
            ArrayNode messages,
            AgentRunState state,
            int round,
            ToolCall toolCall,
            boolean continueAfterTool,
            FluxSink<String> sink) {
        String approvalId = "approval_" + UUID.randomUUID();
        pendingToolExecutions.put(
                approvalId,
                new PendingToolExecution(
                        model,
                        messages.deepCopy(),
                        state.executedToolCalls,
                        round,
                        toolCall,
                        continueAfterTool,
                        state.workspaceRoots,
                        state.runId));
        pendingApprovalService.save(approvalId, pendingToolExecutions.get(approvalId));
        latestPendingToolIds.put(ownerKey(state.userId != null ? state.userId : toolUserId(toolCall)), approvalId);

        timelineService.recordApproval(
                state.runId,
                approvalId,
                "tool.approval.required",
                toolCall.name(),
                compactJson(permissionArguments(toolCall)),
                permissionArguments(toolCall));
        sink.next(approvalEventChunk(approvalId, toolCall.name(), permissionArguments(toolCall)));
    }

    private String approvalEventChunk(String approvalId, String toolName, JsonNode arguments) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode event = root.putObject("avento_event");
        event.put("type", "tool.approval.required");
        event.put("title", "Aprovação necessária — " + toolName);
        event.put("detail", approvalId + ": " + toolName);
        event.put("approvalId", approvalId);
        event.put("toolName", toolName);
        event.set("toolArguments", arguments != null ? arguments : mapper.createObjectNode());
        event.put("timestamp", LocalDateTime.now().toString());
        return root.toString();
    }

    private Flux<String> executeApprovedTool(String approvalId, String comment, ApprovalMemory approvalMemory) {
        return Flux.create(sink -> {
            PendingToolExecution pending = takePendingApproval(approvalId);
            if (pending == null) {
                sendApprovalNotFoundResponse(sink, approvalId);
                return;
            }
            resolvedApprovalIds.put(approvalId, Boolean.TRUE);
            latestPendingToolIds.remove(ownerKey(toolUserId(pending.toolCall())), approvalId);
            resolveSiblingApprovals(pending.runId(), approvalId);

            timelineService.recordApproval(
                    pending.runId(),
                    approvalId,
                    "tool.approval.accepted",
                    pending.toolCall().name(),
                    comment,
                    null);
            permissionService.rememberAllow(
                    toolUserId(pending.toolCall()),
                    pending.toolCall().name(),
                    permissionArguments(pending.toolCall()),
                    pending.workspaceRoots(),
                    approvalMemory);
            if (!isAlwaysConfirmToolCall(pending.toolCall())) {
                planApprovedRuns.add(pending.runId());
            }
            sink.next(eventChunk("tool.approval.accepted", "Aprovação recebida", approvalId));
            if (approvalMemory != null && (approvalMemory.always() || approvalMemory.duration() != null)) {
                sink.next(eventChunk(
                        "tool.permission.remembered",
                        "Permissão salva",
                        "Esta ação foi liberada " + approvalMemory.label() + "."));
            }
            AgentRunState state = new AgentRunState();
            state.executedToolCalls = pending.executedToolCalls();
            state.executedToolCalls++;
            state.workspaceRoots = pending.workspaceRoots();
            state.runId = pending.runId();
            state.imageModel = pending.toolCall().arguments().path("model").asText("");
            state.imageOptions = ImageGenerationOptions.from(pending.toolCall().arguments());
            state.chatId = pending.toolCall().arguments().path("_chatId").canConvertToLong()
                    ? pending.toolCall().arguments().path("_chatId").asLong()
                    : null;
            String pendingUserId =
                    pending.toolCall().arguments().path("_userId").asText("");
            state.userId = pendingUserId.isBlank() ? null : UUID.fromString(pendingUserId);
            JsonNode toolResult =
                    executeToolCall(pending.messages(), sink, pending.toolCall(), pending.runId(), state.userId);
            appendApprovalComment(pending.messages(), comment);

            if (!pending.continueAfterTool()) {
                sink.next(contentChunk(directToolCompletionMessage(pending.toolCall(), toolResult)));
                sink.complete();
                return;
            }

            emitGeneratedMediaCompletion(pending.toolCall(), toolResult, sink);
            if (isMediaGenerationTool(pending.toolCall())) {
                boolean completed = isSuccessfulMediaGeneration(pending.toolCall(), toolResult);
                emitMediaGenerationFailure(pending.toolCall(), toolResult, sink);
                planApprovedRuns.remove(state.runId);
                sink.next(
                        eventChunk(
                                "agent.round.completed",
                                completed ? "Mídia gerada" : "Geração de mídia encerrada",
                                completed
                                        ? "A geração foi concluída pela ferramenta; nenhuma resposta adicional do modelo foi necessária."
                                        : "A ferramenta retornou um erro técnico; nenhuma explicação inventada pelo modelo foi adicionada."));
                sink.complete();
                return;
            }

            // Este sink e um Flux.create novo (continuacao pos-aprovacao) sem nenhum disposable
            // registrado ainda; sem esta amarra, cancelar o stream da aprovacao deixaria a
            // rodada continuada orfa, reintroduzindo o vazamento de requisicao no Ollama.
            sink.onCancel(state.subscriptions::dispose);
            sink.onDispose(state.subscriptions::dispose);
            forward(runTurn(pending.model(), pending.messages(), state, pending.round() + 1), sink, state);
        });
    }

    private void resolveSiblingApprovals(String runId, String resolvedApprovalId) {
        if (pendingApprovalService != null) {
            pendingApprovalService.supersedeSiblings(runId, resolvedApprovalId);
        }
        pendingToolExecutions.forEach((approvalId, pending) -> {
            if (approvalId.equals(resolvedApprovalId) || !pending.runId().equals(runId)) {
                return;
            }
            if (pendingToolExecutions.remove(approvalId, pending)) {
                resolvedApprovalIds.put(approvalId, Boolean.TRUE);
                latestPendingToolIds.remove(ownerKey(toolUserId(pending.toolCall())), approvalId);
                timelineService.recordApproval(
                        runId,
                        approvalId,
                        "tool.approval.superseded",
                        pending.toolCall().name(),
                        "Substituída pela decisão " + resolvedApprovalId,
                        null);
            }
        });
    }

    private void forward(Flux<String> source, FluxSink<String> sink, AgentRunState state) {
        // Nao registrar a filha no sink: o slot de disposable dele ja pertence ao cleanup da
        // rodada atual (ver runTurn), e um segundo registro e ignorado pelo Reactor. O composite
        // do estado e descartado por aquele cleanup, entao adicionar aqui garante o encadeamento.
        Disposable child = source.subscribe(sink::next, sink::error, sink::complete);
        state.subscriptions.add(child);
    }

    private ApprovalVoiceCommand detectApprovalVoiceCommand(ArrayNode messages, UUID userId) {
        String lastUserMessage = lastUserMessage(messages);
        if (lastUserMessage == null) {
            return null;
        }

        String normalized = normalizeIntentText(lastUserMessage);
        ApprovalVoiceDecision decision = approvalVoiceDecision(normalized);
        if (decision == null) {
            return null;
        }

        String approvalId = null;
        for (String token : lastUserMessage.split("\\s+")) {
            String cleaned = token.replaceAll("[^A-Za-z0-9_-]", "");
            if (cleaned.startsWith("approval_") && approvalOwnedBy(cleaned, userId)) {
                approvalId = cleaned;
                break;
            }
        }

        if (approvalId == null) {
            approvalId = latestPendingToolIds.get(ownerKey(userId));
        }
        if (approvalId == null && pendingApprovalService != null) {
            approvalId = pendingApprovalService.latestPendingId(userId).orElse(null);
        }
        if (approvalId == null || !approvalOwnedBy(approvalId, userId)) {
            return null;
        }

        return new ApprovalVoiceCommand(approvalId, decision, approvalMemory(normalized), lastUserMessage.trim());
    }

    public boolean approvalOwnedBy(String approvalId, UUID userId) {
        PendingToolExecution pending = pendingToolExecutions.get(approvalId);
        if (pending == null) {
            return pendingApprovalService != null && pendingApprovalService.isOwnedPending(approvalId, userId);
        }
        UUID ownerId = toolUserId(pending.toolCall());
        return userId == null ? ownerId == null : userId.equals(ownerId);
    }

    public Optional<String> persistedRunIdForApproval(String approvalId, UUID userId) {
        return pendingApprovalService == null ? Optional.empty() : pendingApprovalService.runIdFor(approvalId, userId);
    }

    private PendingToolExecution takePendingApproval(String approvalId) {
        PendingToolExecution inMemory = pendingToolExecutions.remove(approvalId);
        Optional<PendingToolExecution> persisted =
                pendingApprovalService == null ? Optional.empty() : pendingApprovalService.resolve(approvalId);
        return inMemory != null ? inMemory : persisted.orElse(null);
    }

    private UUID toolUserId(ToolCall toolCall) {
        String value =
                toolCall == null ? "" : toolCall.arguments().path("_userId").asText("");
        if (value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String ownerKey(UUID userId) {
        return userId == null ? "local" : userId.toString();
    }

    private ApprovalVoiceDecision approvalVoiceDecision(String normalized) {
        if (containsAny(
                normalized,
                "nao executa",
                "nao roda",
                "nao pode",
                "cancela",
                "cancelar",
                "nega",
                "negar",
                "rejeita",
                "rejeitar",
                "para isso",
                "deixa quieto")) {
            return ApprovalVoiceDecision.REJECT;
        }

        if (containsAny(
                normalized,
                "pode executar",
                "pode abrir",
                "pode sim",
                "sim pode",
                "aprovo",
                "aprovado",
                "autoriza",
                "autorizo",
                "executa",
                "pode rodar",
                "pode fazer",
                "permite",
                "permitir",
                "libera",
                "liberar",
                "so agora",
                "por 1 hora",
                "por uma hora",
                "por 24 horas",
                "por vinte quatro horas",
                "sempre neste projeto",
                "sempre nesse projeto")) {
            return ApprovalVoiceDecision.APPROVE;
        }
        return null;
    }

    private ApprovalMemory approvalMemory(String normalized) {
        if (containsAny(normalized, "sempre neste projeto", "sempre nesse projeto", "sempre neste repo")) {
            return new ApprovalMemory(null, true, "sempre neste projeto");
        }
        if (containsAny(normalized, "24 horas", "vinte quatro horas", "um dia", "1 dia")) {
            return new ApprovalMemory(Duration.ofHours(24), false, "por 24 horas neste projeto");
        }
        if (containsAny(normalized, "1 hora", "uma hora", "por hora")) {
            return new ApprovalMemory(Duration.ofHours(1), false, "por 1 hora neste projeto");
        }
        return ApprovalMemory.once();
    }

    private String detectDirectConversationResponse(ArrayNode messages) {
        String lastUserMessage = lastUserMessage(messages);
        if (lastUserMessage == null || lastUserMessage.isBlank()) {
            return null;
        }

        lastUserMessage = extractDirectUserRequest(lastUserMessage);
        String normalized = normalizeIntentText(lastUserMessage);
        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.length() <= 40 && isShortGreeting(normalized)) {
            return "Oi! Sou o Avento. Como posso ajudar?\n";
        }

        if (containsAny(
                normalized,
                "chega",
                "cala boca",
                "calaboca",
                "calabou",
                "para de falar",
                "pare de falar",
                "fica quieto",
                "fica quieta",
                "falando merda",
                "falou merda")) {
            return "Tá. Vou ficar quieta agora.\n";
        }

        if (normalized.length() <= 40
                && containsAny(
                        normalized,
                        "comervais",
                        "comer vais",
                        "como vai",
                        "como vais",
                        "como voce esta",
                        "como vc esta",
                        "como voce ta",
                        "como vc ta")) {
            return "Tudo bem por aqui. E por aí?\n";
        }

        if (normalized.length() <= 120 && isCapabilityQuestion(normalized)) {
            return capabilityResponse();
        }

        if (normalized.length() <= 160 && isIdentityQuestion(normalized)) {
            return identityResponse();
        }

        if (normalized.length() <= 60 && containsAny(normalized, "portugues brasileiro natural")) {
            return "Estou te ouvindo em português brasileiro. Pode falar o pedido normalmente.\n";
        }

        if (normalized.length() <= 80
                && containsAny(
                        normalized,
                        "bom dia",
                        "boa tarde",
                        "boa noite",
                        "boanoite",
                        "boho noici",
                        "boho noite",
                        "bona noite",
                        "boa noici",
                        "noici")) {
            if (normalized.contains("bom dia")) return "Bom dia! Como posso ajudar?\n";
            if (normalized.contains("boa tarde")) return "Boa tarde! Como posso ajudar?\n";
            return "Boa noite. Como posso ajudar?\n";
        }

        return null;
    }

    private String extractDirectUserRequest(String message) {
        int requestStart = directUserRequestStart(message);
        if (requestStart < 0) {
            return message;
        }

        String extracted = message.substring(requestStart).trim();
        return extracted.isBlank() ? message : extracted;
    }

    private int directUserRequestStart(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        int requestStart = -1;
        int marker = lower.lastIndexOf("responda ao seguinte pedido");
        if (marker >= 0) {
            int separator = message.indexOf(":\n\n", marker);
            if (separator >= 0) {
                requestStart = separator + 3;
            }
        }

        String explicitMarker = "[pedido do usuário]";
        int explicitRequest = lower.lastIndexOf(explicitMarker);
        if (explicitRequest >= 0) {
            int explicitStart = explicitRequest + explicitMarker.length();
            while (explicitStart < message.length() && Character.isWhitespace(message.charAt(explicitStart))) {
                explicitStart++;
            }
            requestStart = Math.max(requestStart, explicitStart);
        }
        return requestStart;
    }

    private ToolCall detectDirectSystemAutomationRequest(ArrayNode messages) {
        String lastUserMessage = lastUserMessage(messages);
        if (lastUserMessage == null || lastUserMessage.isBlank()) {
            return null;
        }

        // Strip any injected [Workspace Roots]/[Local Environment]/[Project Analysis]
        // context before scanning for app names. Otherwise the environment block's
        // own "Apps detectados: Finder, Terminal, ..." text (which always lists
        // Finder first) gets matched by detectKnownAppName instead of the app the
        // user actually asked for.
        lastUserMessage = extractDirectUserRequest(lastUserMessage);
        String normalized = normalizeIntentText(lastUserMessage);
        if (isCasualUserMessage(normalized) || isSocialConversationIntent(normalized)) {
            return null;
        }

        if (wantsScreenCapture(normalized)) {
            return new ToolCall(
                    "call_direct_" + UUID.randomUUID().toString().substring(0, 8),
                    "capture_screen",
                    mapper.createObjectNode());
        }

        if (wantsMacAppListing(normalized)) {
            ObjectNode arguments = mapper.createObjectNode();
            String query = macAppListQuery(normalized);
            if (!query.isBlank()) {
                arguments.put("query", query);
            }
            return new ToolCall(
                    "call_direct_" + UUID.randomUUID().toString().substring(0, 8), "list_macos_apps", arguments);
        }

        String appName = detectKnownAppName(normalized);
        if (appName == null) {
            return null;
        }

        if (wantsNewBrowserTab(normalized) && isKnownBrowser(appName)) {
            ObjectNode arguments = mapper.createObjectNode();
            arguments.put("browserName", appName);
            return new ToolCall(
                    "call_direct_" + UUID.randomUUID().toString().substring(0, 8), "open_browser_tab", arguments);
        }

        boolean wantsClose = containsAny(
                normalized,
                "fecha",
                "fesha",
                "fetcha",
                "feicha",
                "fexa",
                "fexar",
                "pecha",
                "fechar",
                "feche",
                "finaliza",
                "finalizar",
                "encerra",
                "encerrar",
                "quit",
                "close",
                "mata",
                "matar");
        boolean wantsOpen = containsAny(
                normalized, "abre", "abrir", "abra", "vaba", "open", "inicia", "iniciar", "amem", "amen", "navegador");

        if (!hasExplicitAppAutomationIntent(normalized, appName, wantsOpen, wantsClose)) {
            return null;
        }

        if (!wantsOpen && !wantsClose && isShortAppOnlyRequest(normalized, appName)) {
            wantsOpen = true;
        }

        if (!wantsOpen && !wantsClose) {
            return null;
        }

        if (wantsClose && isKnownBrowser(appName) && wantsBrowserTabClose(normalized)) {
            ObjectNode arguments = mapper.createObjectNode();
            arguments.put("browserName", appName);
            return new ToolCall(
                    "call_direct_" + UUID.randomUUID().toString().substring(0, 8), "close_browser_tab", arguments);
        }

        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("appName", appName);
        String toolName = wantsClose ? "close_app" : "open_app";
        return new ToolCall("call_direct_" + UUID.randomUUID().toString().substring(0, 8), toolName, arguments);
    }

    private boolean wantsNewBrowserTab(String normalizedMessage) {
        return containsAny(
                normalizedMessage,
                "nova aba",
                "novo aba",
                "nova agua",
                "novo agua",
                "nova guia",
                "novo guia",
                "new tab",
                "new page",
                "nova pagina",
                "novo separador");
    }

    private boolean wantsBrowserTabClose(String normalizedMessage) {
        return containsAny(
                normalizedMessage,
                "aba",
                "guia",
                "tab",
                "pagina",
                "page",
                "separador",
                "pesquisa",
                "busca",
                "resultado");
    }

    private boolean wantsScreenCapture(String normalizedMessage) {
        return containsAny(
                normalizedMessage,
                "tira um print",
                "tirar um print",
                "tira print",
                "tirar print",
                "faz um print",
                "fazer um print",
                "print da minha tela",
                "print da tela",
                "screenshot",
                "captura minha tela",
                "capturar minha tela",
                "captura a tela",
                "capturar a tela");
    }

    private boolean wantsMacAppListing(String normalizedMessage) {
        return containsAny(
                        normalizedMessage,
                        "lista de apps",
                        "listar apps",
                        "lista todos os apps",
                        "listar todos os apps",
                        "todos os apps",
                        "todos aplicativos",
                        "todos os aplicativos",
                        "apps instalados",
                        "aplicativos instalados",
                        "procura nos meus app",
                        "procura nos apps",
                        "procurar nos apps")
                || (normalizedMessage.contains("finder")
                        && containsAny(normalizedMessage, "apps", "aplicativos", "applications"));
    }

    private String macAppListQuery(String normalizedMessage) {
        if (normalizedMessage.contains("antigravity")) {
            return "Antigravity";
        }
        return "";
    }

    private boolean isShortGreeting(String normalizedMessage) {
        return normalizedMessage.equals("oi")
                || normalizedMessage.startsWith("oi ")
                || normalizedMessage.equals("ola")
                || normalizedMessage.startsWith("ola ")
                || normalizedMessage.equals("e ai")
                || normalizedMessage.startsWith("e ai ")
                || normalizedMessage.equals("fala")
                || normalizedMessage.startsWith("fala ")
                || normalizedMessage.equals("salve")
                || normalizedMessage.startsWith("salve ")
                || normalizedMessage.equals("hey")
                || normalizedMessage.startsWith("hey ")
                || normalizedMessage.equals("hello")
                || normalizedMessage.startsWith("hello ")
                || normalizedMessage.equals("hi")
                || normalizedMessage.startsWith("hi ");
    }

    private boolean isKnownBrowser(String appName) {
        return "Brave Browser".equals(appName) || "Google Chrome".equals(appName) || "Safari".equals(appName);
    }

    private String detectKnownAppName(String normalizedMessage) {
        try {
            String resolvedAppName = systemAutomationService.resolveMacApplicationName(normalizedMessage);
            if (!resolvedAppName.equals(normalizedMessage)) {
                return resolvedAppName;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to speech/noise aliases.
        }

        if (normalizedMessage.contains("finder")) {
            return "Finder";
        }
        if (normalizedMessage.contains("terminal")) {
            return "Terminal";
        }
        if (normalizedMessage.contains("visual studio code")
                || normalizedMessage.contains("visual ao estudio code")
                || normalizedMessage.contains("vs code")
                || normalizedMessage.contains("vscode")
                || normalizedMessage.contains("vsc code")
                || normalizedMessage.contains("es code")
                || normalizedMessage.contains("osvs code")
                || normalizedMessage.contains("app do vs code")
                || normalizedMessage.contains("app do visual studio code")
                || normalizedMessage.contains("vaskode")
                || normalizedMessage.contains("vascode")
                || normalizedMessage.contains("vescode")
                || normalizedMessage.contains("versiculo")
                || normalizedMessage.contains("vescunti")
                || normalizedMessage.contains("vesconti")) {
            return "Visual Studio Code";
        }
        if (normalizedMessage.contains("brave")) {
            return "Brave Browser";
        }
        if (normalizedMessage.contains("google chrome") || normalizedMessage.contains("chrome")) {
            return "Google Chrome";
        }
        if (normalizedMessage.contains("safari")) {
            return "Safari";
        }
        if (normalizedMessage.contains("figma")) {
            return "Figma";
        }
        if (normalizedMessage.contains("cursor")) {
            return "Cursor";
        }
        return null;
    }

    private boolean isSocialConversationIntent(String normalizedMessage) {
        if (normalizedMessage.isBlank() || normalizedMessage.length() > 80) {
            return false;
        }

        return containsAny(
                normalizedMessage,
                "comervais",
                "comer vais",
                "como vai",
                "como vais",
                "como voce esta",
                "como vc esta",
                "como voce ta",
                "como vc ta",
                "o que voce pode fazer",
                "o que vc pode fazer",
                "o q voce pode fazer",
                "o q vc pode fazer",
                "que voce pode fazer",
                "que vc pode fazer",
                "com o que voce pode me ajudar",
                "com o que vc pode me ajudar",
                "com o q voce pode me ajudar",
                "com o q vc pode me ajudar",
                "com que voce pode me ajudar",
                "com que vc pode me ajudar",
                "com q voce pode me ajudar",
                "com q vc pode me ajudar",
                "no que voce pode me ajudar",
                "no que vc pode me ajudar",
                "no q voce pode me ajudar",
                "no q vc pode me ajudar",
                "o que mais voce pode fazer",
                "o que mais vc pode fazer",
                "quem e voce",
                "quem voce e",
                "explica para meu amigo",
                "explica para o meu amigo",
                "portugues brasileiro natural",
                "eu disse",
                "nao disse",
                "nao falei",
                "corrigindo",
                "correcao",
                "doida",
                "doitia",
                "boa noite",
                "boanoite",
                "boho noici",
                "boho noite",
                "bona noite",
                "boa noici",
                "noici");
    }

    private boolean isCapabilityQuestion(String normalizedMessage) {
        return containsAny(
                normalizedMessage,
                "o que voce pode fazer",
                "o que vc pode fazer",
                "o q voce pode fazer",
                "o q vc pode fazer",
                "que voce pode fazer",
                "que vc pode fazer",
                "o que mais voce pode fazer",
                "o que mais vc pode fazer",
                "com o que voce pode me ajudar",
                "com o que vc pode me ajudar",
                "com o q voce pode me ajudar",
                "com o q vc pode me ajudar",
                "com que voce pode me ajudar",
                "com que vc pode me ajudar",
                "com q voce pode me ajudar",
                "com q vc pode me ajudar",
                "no que voce pode me ajudar",
                "no que vc pode me ajudar",
                "no q voce pode me ajudar",
                "no q vc pode me ajudar",
                "como voce pode me ajudar",
                "como vc pode me ajudar",
                "fala para mim o que voce pode",
                "fala pra mim o que voce pode",
                "quantas ferramentas",
                "quantas ferramentes",
                "numero de ferramentas");
    }

    private boolean isIdentityQuestion(String normalizedMessage) {
        return containsAny(
                normalizedMessage,
                "quem e voce",
                "quem voce e",
                "fala pra mim quem e voce",
                "fala para mim quem e voce",
                "me diga quem e voce",
                "me fala quem e voce",
                "o que e voce",
                "o que voce e",
                "explica para meu amigo",
                "explica para o meu amigo",
                "apresenta voce",
                "se apresenta");
    }

    private String capabilityResponse() {
        return CAPABILITY_RESPONSE_PT.replace(
                        "{{toolCount}}",
                        Integer.toString(toolGateway.listTools().size()))
                + "\n";
    }

    private String identityResponse() {
        return IDENTITY_RESPONSE_PT + "\n";
    }

    private boolean hasExplicitAppAutomationIntent(
            String normalizedMessage, String appName, boolean wantsOpen, boolean wantsClose) {
        if (isShortAppOnlyRequest(normalizedMessage, appName)) {
            return true;
        }
        if (!wantsOpen && !wantsClose) {
            return false;
        }

        String appToken = appName.toLowerCase(Locale.ROOT);
        if ("Visual Studio Code".equals(appName)) {
            return containsAny(
                    normalizedMessage,
                    "vs code",
                    "vscode",
                    "vsc code",
                    "visual studio code",
                    "app do vs code",
                    "app do visual studio code",
                    "versiculo",
                    "vaskode",
                    "vascode",
                    "vescode",
                    "vescunti",
                    "vesconti");
        }
        if ("Brave Browser".equals(appName)) {
            return containsAny(normalizedMessage, "brave", "brave browser", "navegador brave");
        }
        if ("Google Chrome".equals(appName)) {
            return containsAny(normalizedMessage, "google chrome", "chrome");
        }
        return normalizedMessage.matches(".*\\b" + Pattern.quote(appToken) + "\\b.*");
    }

    private boolean isShortAppOnlyRequest(String normalizedMessage, String appName) {
        if (normalizedMessage.length() > 80) {
            return false;
        }
        return normalizedMessage.contains(" app")
                || normalizedMessage.startsWith("app ")
                || normalizedMessage.equals("vscode")
                || normalizedMessage.equals("vs code")
                || normalizedMessage.equals("vsc code")
                || normalizedMessage.equals("minho vs code")
                || normalizedMessage.equals("meu vs code")
                || normalizedMessage.equals("visual studio code")
                || normalizedMessage.equals("brave")
                || normalizedMessage.equals("brave browser")
                || normalizedMessage.equals(appName.toLowerCase(Locale.ROOT));
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private void appendApprovalComment(ArrayNode messages, String comment) {
        if (comment == null || comment.isBlank()) {
            return;
        }

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", "[Approval note] " + comment.trim());
        messages.add(userMsg);
    }

    private String lastUserMessage(ArrayNode messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if ("user".equals(message.path("role").asText())) {
                return message.path("content").asText("");
            }
        }
        return null;
    }

    private boolean shouldIgnoreToolCallsForCasualMessage(ArrayNode messages) {
        String lastUserMessage = lastUserMessage(messages);
        if (lastUserMessage == null) {
            return false;
        }
        return isCasualUserMessage(extractDirectUserRequest(lastUserMessage));
    }

    private boolean isCasualUserMessage(String message) {
        String normalized = normalizeIntentText(message);
        if (normalized.isBlank() || normalized.length() > 80) {
            return false;
        }

        for (String actionWord : PROJECT_ACTION_WORDS) {
            if (normalized.contains(actionWord)) {
                return false;
            }
        }

        for (String casualPhrase : CASUAL_PHRASES) {
            if (normalized.equals(casualPhrase) || normalized.startsWith(casualPhrase + " ")) {
                return true;
            }
        }
        return false;
    }

    private String normalizeIntentText(String message) {
        if (message == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(message.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // O preâmbulo antes de uma chamada de ferramenta ("Vou continuar a análise...") voltava inteiro
    // para o histórico. Na rodada seguinte o modelo via a própria frase, copiava, e na outra via
    // duas cópias — eco que enche o contexto e produz a resposta repetida que o usuário vê. O que o
    // modelo precisa reter da rodada é o tool_call e o resultado, não o floreio; guarda-se só um
    // trecho curto para não perder um raciocínio que às vezes vem junto.
    private static final int MAX_NARRATION_CHARS_KEPT = 240;

    /**
     * Sem chamada de ferramenta o texto É a resposta ao usuário e vai inteiro. Com chamada, ele é
     * preâmbulo: mantém-se só o começo, o bastante para preservar um raciocínio curto sem realimentar
     * o parágrafo que o modelo vai copiar na rodada seguinte.
     */
    // Verbos de acao que so se cumprem com ferramenta. "vou explicar" nao entra: e coisa que o
    // modelo faz em texto mesmo.
    private static final Pattern ANNOUNCED_ACTION = Pattern.compile(
            "\\b(vou|irei|deixa\\s+eu|deixe-me|estou)\\s+(?:\\w+\\s+)?"
                    + "(pesquisar|pesquisando|buscar|buscando|procurar|procurando|acessar|acessando|"
                    + "consultar|consultando|verificar|verificando|ler|lendo|baixar|baixando|"
                    + "executar|executando|rodar|rodando|abrir|abrindo|analisar|analisando)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Limite acima do qual o texto ja e uma resposta de verdade, nao um preambulo vazio. */
    private static final int ANNOUNCEMENT_MAX_CHARS = 900;

    /**
     * Detecta a rodada em que o modelo ANUNCIA uma acao e nao executa nada.
     *
     * <p>A guarda de turno vazio nao pegava isto: ela exige texto em branco, e aqui o modelo escreve
     * "Vou pesquisar agora!" e encerra. Para o usuario e pior que o silencio — parece que algo esta
     * acontecendo. Observado quatro vezes seguidas, duas delas repetindo o mesmo paragrafo palavra
     * por palavra, com a ferramenta disponivel na mesa.
     *
     * <p>So vale quando havia ferramenta para chamar: sem toolset, prometer e a unica saida.
     */
    static boolean announcedActionWithoutCalling(
            String assistantText, boolean roundCalledATool, boolean toolsAvailable) {
        if (roundCalledATool || !toolsAvailable || assistantText == null) {
            return false;
        }
        String text = assistantText.trim();
        if (text.isEmpty() || text.length() > ANNOUNCEMENT_MAX_CHARS) {
            return false;
        }
        return ANNOUNCED_ACTION.matcher(text).find();
    }

    static String narrationForHistory(String assistantText, boolean roundCalledATool) {
        if (!roundCalledATool || assistantText.length() <= MAX_NARRATION_CHARS_KEPT) {
            return assistantText;
        }
        return assistantText.substring(0, MAX_NARRATION_CHARS_KEPT).stripTrailing() + "…";
    }

    private void appendAssistantToolRequest(ArrayNode messages, TurnCapture capture) {
        ObjectNode assistantMsg = mapper.createObjectNode();
        assistantMsg.put("role", "assistant");
        String fullText = narrationForHistory(capture.assistantText.toString(), !capture.nativeToolCalls.isEmpty());
        if (!fullText.isEmpty()) {
            assistantMsg.put("content", fullText);
        }

        if (!capture.nativeToolCalls.isEmpty()) {
            ArrayNode toolCalls = mapper.createArrayNode();
            capture.nativeToolCalls.forEach(nativeCall -> toolCalls.add(toOutgoingToolCall(nativeCall)));
            assistantMsg.set("tool_calls", toolCalls);
        }
        messages.add(assistantMsg);
    }

    // capture.nativeToolCalls stores function.arguments() as a JSON string (matching
    // what
    // detectToolCalls()/mapper.readTree(...) expects when reading it back).
    // Ollama's native
    // /api/chat endpoint, however, rejects an assistant message whose
    // tool_calls[].function
    // .arguments() is a JSON-encoded string when it's fed back into the next round —
    // it expects
    // arguments as a real JSON object (the same shape it emits itself), and fails
    // the whole
    // request with "Value looks like object, but can't find closing '}' symbol"
    // otherwise. This
    // re-parses the stored string into an object only for the copy sent back to
    // Ollama.
    private ObjectNode toOutgoingToolCall(ObjectNode nativeCall) {
        ObjectNode outgoing = nativeCall.deepCopy();
        JsonNode functionNode = outgoing.path("function");
        if (functionNode.isObject() && functionNode.path("arguments").isTextual()) {
            try {
                ((ObjectNode) functionNode)
                        .set(
                                "arguments",
                                mapper.readTree(functionNode.get("arguments").asText()));
            } catch (Exception e) {
                logger.debug("Could not re-parse tool call arguments as JSON object", e);
            }
        }
        return outgoing;
    }

    // Mesma ferramenta falhando este número de vezes seguidas = para de insistir.
    private static final int REPEATED_TOOL_FAILURE_LIMIT = 2;

    // Atualiza o contador de falhas consecutivas por ferramenta. Sucesso zera; uma ferramenta
    // diferente falhando reinicia a contagem para ela.
    private void recordToolOutcome(AgentRunState state, ToolCall toolCall, JsonNode toolResult) {
        String toolName = toolCall.name();
        boolean failed = toolResult != null && toolResult.has("error");
        if (!failed) {
            state.lastFailedTool = "";
            state.consecutiveToolFailures = 0;
        } else {
            if (toolName.equals(state.lastFailedTool)) {
                state.consecutiveToolFailures++;
            } else {
                state.lastFailedTool = toolName;
                state.consecutiveToolFailures = 1;
            }
        }

        String signature = toolName + ":"
                + (toolCall.arguments() != null ? toolCall.arguments().toString() : "");
        if (signature.equals(state.lastToolCallSignature)) {
            state.consecutiveIdenticalToolCalls++;
        } else {
            state.lastToolCallSignature = signature;
            state.consecutiveIdenticalToolCalls = 1;
        }
    }

    /**
     * Corta o resultado da ferramenta antes de ele virar histórico.
     *
     * <p>Ia inteiro. Um {@code fetch} devolve a página web completa — 20 mil caracteres viram ~5 mil
     * tokens que ficam no prompt e são RELIDOS em toda rodada seguinte. Numa pesquisa com três
     * páginas, a rodada 3 chegava a 10-15 mil tokens só de contexto, e ler prompt custa ~4,4ms por
     * token nesta máquina: um minuto de silêncio antes do primeiro caractere sair. O sintoma
     * aparecia como "ficou pensando muito tempo", mas não era raciocínio — era releitura.
     *
     * <p>O marcador no fim é deliberado: sem ele o modelo trata o texto cortado como o documento
     * inteiro e responde com confiança sobre o que não leu.
     */
    static String truncateToolResultForHistory(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars)
                + "\n\n[...truncado pelo Avento: o resultado tinha " + content.length()
                + " caracteres. Se precisar do trecho que faltou, chame a ferramenta de novo com um"
                + " filtro mais específico em vez de supor o conteúdo.]";
    }

    private JsonNode executeToolCall(
            ArrayNode messages, FluxSink<String> sink, ToolCall toolCall, String runId, UUID userId) {
        JsonNode visibleArguments = permissionArguments(toolCall);
        timelineService.record(runId, "tool.started", toolCall.name(), compactJson(visibleArguments), visibleArguments);
        sink.next(eventChunk("tool.started", "Executando " + toolCall.name(), compactJson(visibleArguments)));

        ObjectNode toolMsg = mapper.createObjectNode();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", toolCall.id());
        toolMsg.put("name", toolCall.name());

        try {
            Map<String, Object> argsMap =
                    mapper.convertValue(toolCall.arguments(), new TypeReference<Map<String, Object>>() {});
            JsonNode toolResult = toolGateway.execute(toolCall.name(), argsMap);
            // O corte acompanha a janela: com 1M de contexto, cortar em 4000 chars joga fora
            // pesquisa que caberia; com 8k, 4000 ja e demais.
            toolMsg.put("content", truncateToolResultForHistory(toolResult.toString(), toolResultBudget(userId)));
            messages.add(toolMsg);
            if (toolResult.has("error")) {
                String error = toolResult.get("error").asText();
                timelineService.record(runId, "tool.failed", toolCall.name(), error, toolResult);
                sink.next(eventChunk("tool.failed", "Ferramenta indisponivel", toolCall.name() + ": " + error));
                return toolResult;
            }
            timelineService.record(
                    runId,
                    "tool.completed",
                    toolCall.name(),
                    extractToolOutputForActivity(toolCall.name(), toolResult),
                    toolResult);
            sink.next(toolCompletionEventChunk(toolCall.name(), toolResult));
            return toolResult;
        } catch (Exception e) {
            logger.warn("Tool execution failed: {}", toolCall.name(), e);
            toolMsg.put("content", "Erro: " + e.getMessage());
            messages.add(toolMsg);
            timelineService.record(runId, "tool.failed", toolCall.name(), e.getMessage(), null);
            sink.next(eventChunk("tool.failed", "Ferramenta falhou", toolCall.name() + ": " + e.getMessage()));
            ObjectNode error = mapper.createObjectNode();
            error.put("error", e.getMessage());
            return error;
        }
    }

    private void emitGeneratedMediaCompletion(ToolCall toolCall, JsonNode toolResult, FluxSink<String> sink) {
        if (toolResult == null || toolResult.has("error")) {
            return;
        }
        if ("generate_image".equals(toolCall.name())) {
            sink.next(contentChunk(generatedImageMessage(toolResult)));
        } else if ("generate_video".equals(toolCall.name())) {
            sink.next(contentChunk(generatedVideoMessage(toolResult)));
        }
    }

    private boolean isSuccessfulMediaGeneration(ToolCall toolCall, JsonNode toolResult) {
        return toolCall != null && toolResult != null && !toolResult.has("error") && isMediaGenerationTool(toolCall);
    }

    private boolean isMediaGenerationTool(ToolCall toolCall) {
        return toolCall != null
                && ("generate_image".equals(toolCall.name()) || "generate_video".equals(toolCall.name()));
    }

    private void emitMediaGenerationFailure(ToolCall toolCall, JsonNode toolResult, FluxSink<String> sink) {
        if (toolResult != null && toolResult.has("error")) {
            sink.next(contentChunk(directToolCompletionMessage(toolCall, toolResult)));
        }
    }

    private String directToolCompletionMessage(ToolCall toolCall, JsonNode toolResult) {
        if (toolResult != null && toolResult.has("error")) {
            StringBuilder message = new StringBuilder("\n> Não consegui executar `")
                    .append(toolCall.name())
                    .append("`: ")
                    .append(toolResult.get("error").asText())
                    .append('\n');
            String details = toolResult.path("details").asText("");
            if (!details.isBlank()) {
                message.append("> Detalhes: ").append(details).append('\n');
            }
            String hint = toolResult.path("hint").asText("");
            if (!hint.isBlank()) {
                message.append("> ").append(hint).append('\n');
            }
            return message.toString();
        }

        String appName = toolCall.arguments().path("appName").asText("");
        return switch (toolCall.name()) {
            case "open_app" -> "\nAplicativo aberto: " + appName + ".\n";
            case "close_app" -> "\nAplicativo fechado: " + appName + ".\n";
            case "open_browser_tab" ->
                "\nNova aba aberta em "
                        + toolCall.arguments().path("browserName").asText("navegador") + ".\n";
            case "close_browser_tab" ->
                "\nAba fechada em " + toolCall.arguments().path("browserName").asText("navegador") + ".\n";
            case "capture_screen" ->
                "\nPrint salvo em: " + toolResult.path("path").asText("arquivo de screenshot") + ".\n";
            case "generate_image" -> generatedImageMessage(toolResult);
            case "generate_video" -> generatedVideoMessage(toolResult);
            case "list_macos_apps" -> macAppsListMessage(toolResult);
            default -> formattedGenericToolResult(toolCall, toolResult);
        };
    }

    private String formattedGenericToolResult(ToolCall toolCall, JsonNode toolResult) {
        if (toolResult == null || toolResult.isEmpty()) {
            return "\nAção `" + toolCall.name() + "` executada com sucesso.\n";
        }
        String output = "";
        if (toolResult.has("output")) output = toolResult.get("output").asText("");
        else if (toolResult.has("result")) output = toolResult.get("result").asText("");
        else if (toolResult.has("content")) output = toolResult.get("content").asText("");
        else if (toolResult.has("text")) output = toolResult.get("text").asText("");
        else if (toolResult.has("data")) output = toolResult.get("data").toString();
        else output = toolResult.toString();

        if (output.isBlank()) {
            return "\nAção `" + toolCall.name() + "` executada com sucesso.\n";
        }

        if (output.length() > 2000) {
            output = output.substring(0, 2000) + "\n... (saída truncada)";
        }
        return "\nResultado da ferramenta `" + toolCall.name() + "`:\n```\n" + output + "\n```\n";
    }

    private String generatedImageMessage(JsonNode toolResult) {
        String jobId = toolResult.path("jobId").asText("");
        if (!jobId.isBlank()) {
            return "\nA imagem foi enfileirada. Você pode continuar usando o Avento enquanto ela fica pronta."
                    + "\n\n[[avento-image-job:" + jobId + "]]\n";
        }
        String path = toolResult.path("path").asText("");
        String filename = path.isBlank() ? "" : Paths.get(path).getFileName().toString();
        if (filename.isBlank()) {
            return "\nImagem gerada, mas o arquivo não foi identificado.\n";
        }
        StringBuilder message = new StringBuilder("\nImagem gerada:\n\n![Imagem gerada](/api/media/")
                .append(filename)
                .append(")\n\nArquivo salvo em: ")
                .append(path)
                .append(".\n");
        if (toolResult.has("seed")) {
            message.append("Parâmetros: ")
                    .append(toolResult.path("qualityPreset").asText("personalizado"));
            if (toolResult.has("steps")) {
                message.append(" · ").append(toolResult.path("steps").asInt()).append(" steps");
            }
            message.append(" · seed ").append(toolResult.path("seed").asLong()).append(".\n");
        }
        if (toolResult.path("refinementEnabled").asBoolean(false)) {
            message.append("Acabamento: segundo passe");
            String detailMode = toolResult.path("detailMode").asText("none");
            if ("face".equals(detailMode)) {
                message.append(" · correção de rosto");
            } else if ("face-hands".equals(detailMode)) {
                message.append(" · correção de rosto e mãos");
            }
            if (toolResult.path("poseReferenceApplied").asBoolean(false)) {
                message.append(" · pose de referência");
            }
            message.append(".\n");
        }
        JsonNode warnings = toolResult.path("warnings");
        if (warnings.isArray() && !warnings.isEmpty()) {
            message.append("Avisos do pipeline:\n");
            warnings.forEach(
                    warning -> message.append("- ").append(warning.asText()).append("\n"));
        }
        return message.toString();
    }

    // O vídeo sai como WEBP animado, que o navegador toca dentro de uma tag <img> — por isso o
    // embed usa a mesma sintaxe markdown de imagem e nenhuma mudança de player foi necessária no
    // frontend.
    private String generatedVideoMessage(JsonNode toolResult) {
        String jobId = toolResult.path("jobId").asText("");
        if (!jobId.isBlank()) {
            String modeMessage = "image-to-video".equals(toolResult.path("mode").asText(""))
                    ? "Animando a imagem mais recente deste chat."
                    : "Criando um vídeo novo a partir do texto.";
            return "\n" + modeMessage + " Você pode continuar usando o Avento enquanto ele fica pronto."
                    + "\n\n[[avento-video-job:" + jobId + "]]\n";
        }
        String path = toolResult.path("path").asText("");
        String filename = path.isBlank() ? "" : Paths.get(path).getFileName().toString();
        if (filename.isBlank()) {
            return "\nVídeo gerado, mas o arquivo não foi identificado.\n";
        }
        return "\nVídeo gerado:\n\n![Vídeo gerado](/api/media/" + filename + ")\n\n" + "Arquivo salvo em: " + path
                + ".\n";
    }

    private String macAppsListMessage(JsonNode toolResult) {
        JsonNode apps = toolResult.path("apps");
        int count = toolResult.path("count").asInt(0);
        if (!apps.isArray() || apps.isEmpty()) {
            String query = toolResult.path("query").asText("");
            return query.isBlank()
                    ? "\nNão encontrei aplicativos instalados nas pastas padrão do macOS.\n"
                    : "\nNão encontrei app instalado com esse filtro: " + query + ".\n";
        }

        StringBuilder message = new StringBuilder("\nEncontrei ")
                .append(count)
                .append(count == 1 ? " app instalado" : " apps instalados")
                .append(" no Mac:\n\n");
        int index = 1;
        for (JsonNode app : apps) {
            message.append(index++)
                    .append(". ")
                    .append(app.path("name").asText("App"))
                    .append(" — ")
                    .append(app.path("path").asText(""))
                    .append('\n');
            if (index > 80) {
                message.append("\nLista truncada em 80 itens. Posso filtrar por nome se quiser.\n");
                break;
            }
        }
        return message.toString();
    }

    private String contentChunk(String content) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        ObjectNode delta = choice.putObject("delta");
        delta.put("content", content);
        return root.toString();
    }

    private String eventChunk(String type, String title, String detail) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode event = root.putObject("avento_event");
        event.put("type", type);
        event.put("title", title);
        event.put("detail", detail == null ? "" : detail);
        event.put("timestamp", LocalDateTime.now().toString());
        return root.toString();
    }

    // The plain tool.completed event only ever showed the tool name, with no
    // indication of what a
    // terminal command actually printed. This surfaces real command output
    // (truncated) so the
    // "Atividade do agente" panel reads like an actual terminal instead of a bare
    // status line, and
    // includes processId for terminal_start so the frontend can poll terminal_logs
    // directly against
    // /api/mcp/execute to show live output for long-running processes without going
    // through the model.
    private String toolCompletionEventChunk(String toolName, JsonNode toolResult) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode event = root.putObject("avento_event");
        event.put("type", "tool.completed");
        event.put("title", "Ferramenta concluida");
        event.put("toolName", toolName);

        String output = extractToolOutputForActivity(toolName, toolResult);
        event.put("detail", output.isBlank() ? toolName : toolName + "\n" + truncateForActivity(output));

        if (TERMINAL_TOOLS.contains(toolName)) {
            String processId = toolResult.path("processId").asText("");
            if (!processId.isBlank()) {
                event.put("processId", processId);
            }
            if (toolResult.has("running")) {
                event.put("running", toolResult.path("running").asBoolean());
            }
            if ("terminal_start".equals(toolName)) {
                event.put("running", true);
                event.put("command", toolResult.path("command").asText(""));
            }
        }

        event.put("timestamp", LocalDateTime.now().toString());
        return root.toString();
    }

    private String extractToolOutputForActivity(String toolName, JsonNode toolResult) {
        return switch (toolName) {
            case "terminal_run" -> toolResult.path("execution").path("output").asText("");
            case "terminal_start" ->
                "Processo iniciado: " + toolResult.path("command").asText("");
            case "terminal_logs", "terminal_stop" -> toolResult.path("logs").asText("");
            default -> "";
        };
    }

    private String truncateForActivity(String text) {
        if (text.length() <= MAX_ACTIVITY_OUTPUT_CHARS) {
            return text;
        }
        return "... (truncado)\n" + text.substring(text.length() - MAX_ACTIVITY_OUTPUT_CHARS);
    }

    private String compactJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }

        String value = node.toString();
        if (value.length() <= 180) {
            return value;
        }
        return value.substring(0, 177) + "...";
    }

    private String newRunId() {
        return "run_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static class AgentRunState {
        String runId = "";
        int executedToolCalls = 0;
        List<String> workspaceRoots = List.of();
        String imageModel = "";
        // Ferramenta declarada pela skill ativa nesta run ("" = nenhuma): quando presente,
        // a selecao de ferramentas expoe ela com prioridade, ignorando heuristica de keyword.
        String requiredToolName = "";
        Set<String> requiredToolNames = new HashSet<>();
        // União (não exclusão): ferramentas com exposição GARANTIDA nesta run além da seleção
        // normal — servidores auto-conectados para o pedido + ativadas via activate_tools.
        Set<String> extraExposedToolNames = new HashSet<>();
        // Catálogo completo da rodada (locais + MCP conectadas): referência do fallback textual.
        Set<String> availableToolNames = Set.of();
        Integer maxToolRoundsOverride = null;
        ImageGenerationOptions imageOptions = ImageGenerationOptions.defaults();
        Long chatId;
        UUID userId;
        boolean forceFullToolset = false;
        boolean retriedWithFullToolset = false;
        boolean retriedEmptyTurn = false;
        // Rodada final, sem ferramentas: responder com o contexto ja coletado em vez de descartar
        // tudo ao bater o limite. Marcada uma unica vez, senao o proprio fecho viraria outro loop.
        boolean finalSynthesis = false;
        // Guarda contra insistir numa ferramenta quebrada: nome da última ferramenta que falhou e
        // quantas vezes seguidas. Um sucesso zera. Ao bater no limite, o Avento para e explica em
        // vez de gastar mais rodadas (~50s cada no modelo local) repetindo a mesma falha.
        String lastFailedTool = "";
        int consecutiveToolFailures = 0;
        String lastToolCallSignature = "";
        int consecutiveIdenticalToolCalls = 0;
        // Rodadas 2+ sao subscriptions filhas criadas por forward() dentro do sink da rodada 1.
        // O slot de onCancel/onDispose de um FluxSink so guarda um Disposable, entao registrar
        // cada filha direto no sink deixava as continuacoes orfas: cancelar a run matava so a
        // rodada 1 e a requisicao HTTP da rodada seguinte continuava viva no Ollama ocupando a
        // GPU (zumbi observado em producao). Todas as filhas entram neste composite, que e
        // descartado junto com o cleanup de cada rodada.
        final Disposable.Composite subscriptions = Disposables.composite();
    }

    private static class TurnCapture {
        StringBuilder assistantText = new StringBuilder();
        StringBuilder lineBuffer = new StringBuilder();
        List<ObjectNode> nativeToolCalls = new ArrayList<>();
        boolean suppressTextualToolMarkup = false;
        boolean deferAssistantOutput = false;

        TurnCapture() {}

        TurnCapture(boolean deferAssistantOutput) {
            this.deferAssistantOutput = deferAssistantOutput;
        }
    }

    public enum ApprovalVoiceDecision {
        APPROVE,
        REJECT
    }
}
