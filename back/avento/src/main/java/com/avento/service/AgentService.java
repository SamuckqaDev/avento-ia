package com.avento.service;

import com.avento.service.dto.*;
import com.avento.service.dto.ApprovalMemory;
import com.avento.service.dto.Skill;
import com.avento.service.image.ImageGenerationOptions;
import com.avento.service.intent.IntentProfile;
import com.avento.service.intent.IntentRouter;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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

@Service
public class AgentService implements AgentExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);
    // Fallback usado somente quando o pedido chega sem modelo ou com um modelo que nao e de chat
    // (ex.: um modelo de imagem no seletor errado). A escolha do usuario no frontend sempre vence
    // quando valida; este valor e configuravel via avento.agent.default-model /
    // AVENTO_AGENT_DEFAULT_MODEL, nunca fixo em codigo.
    private final String defaultChatModel;
    private final String defaultVisionModel;
    private static final long HEAVY_MODEL_BYTES = 4_000_000_000L;
    private static final Pattern TEXTUAL_FUNCTION_PATTERN =
            Pattern.compile("\\{\\s*function\\s+<([A-Za-z0-9_-]+)>\\s+(\\{.*})\\s*}", Pattern.DOTALL);
    // The model-facing instructions live in editable resource files rather than a
    // large Java string. Keep this loader as the single authoritative composition
    // point because frontend system messages are intentionally discarded later.
    private static final String AGENT_SYSTEM_PROMPT = loadAgentInstructions();

    private static String loadAgentInstructions() {
        List<String> resources = List.of(
                "agent/instructions/identity.md",
                "agent/instructions/context.md",
                "agent/instructions/tools.md",
                "agent/instructions/execution.md");
        try {
            StringBuilder instructions = new StringBuilder();
            for (String resource : resources) {
                ClassPathResource file = new ClassPathResource(resource);
                try (var inputStream = file.getInputStream()) {
                    instructions.append(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                }
                instructions.append("\n\n");
            }
            return instructions.toString().trim();
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível carregar as instruções do agente", exception);
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
    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final int maxToolRounds;
    private final int maxToolCalls;
    private final int numCtx;
    private final double temperature;
    private final double topP;
    private final int topK;
    private final double repeatPenalty;
    private final boolean enableThinking;
    private final String keepAlive;
    private final int maxToolsPerRequest;
    private final Set<String> projectToolkit;

    // Teto de ferramentas fora do kit que a intencao da mensagem pode trazer junto em chats
    // com projeto. Pequeno de proposito: extras raros preservam o prefixo estavel do prompt
    // na maioria das mensagens e evitam voltar ao custo de dezenas de schemas por rodada.
    private static final int PROJECT_TOOLKIT_EXTRA_LIMIT = 6;
    private final int maxModelMessages;
    private final int maxMessageContentChars;
    private final int maxTotalMessageContentChars;

    @Value("${avento.agent.policy-mode:maximum}")
    private String policyMode;

    @Value("${avento.agent.policy-override-dir:}")
    private String policyOverrideDirectory;

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
            ObjectMapper mapper,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${avento.agent.max-tool-rounds:6}") int maxToolRounds,
            @Value("${avento.agent.max-tool-calls:16}") int maxToolCalls,
            @Value("${avento.agent.num-ctx:8192}") int numCtx,
            @Value("${avento.agent.temperature:0.15}") double temperature,
            @Value("${avento.agent.top-p:0.9}") double topP,
            @Value("${avento.agent.top-k:30}") int topK,
            @Value("${avento.agent.repeat-penalty:1.08}") double repeatPenalty,
            @Value("${avento.agent.enable-thinking:true}") boolean enableThinking,
            @Value("${avento.agent.keep-alive:30m}") String keepAlive,
            @Value("${avento.agent.max-tools-per-request:12}") int maxToolsPerRequest,
            @Value("${avento.agent.project-toolkit:directory_tree,read_file,read_document,write_file,edit_file,"
                            + "delete_file,delete_directory,create_directory,search_files,terminal_run,"
                            + "terminal_start,terminal_logs}")
                    String projectToolkit,
            @Value("${avento.agent.max-model-messages:10}") int maxModelMessages,
            @Value("${avento.agent.max-message-content-chars:6000}") int maxMessageContentChars,
            @Value("${avento.agent.max-total-message-content-chars:14000}") int maxTotalMessageContentChars,
            @Value("${avento.agent.default-model:qwen3:8b}") String defaultChatModel,
            @Value("${avento.agent.vision-model:qwen2.5vl:7b}") String defaultVisionModel) {
        this.toolGateway = toolGateway;
        this.toolRegistry = toolRegistry;
        this.intentRouter = intentRouter;
        this.systemAutomationService = systemAutomationService;
        this.permissionService = permissionService;
        this.timelineService = timelineService;
        this.skillRegistry = skillRegistry;
        this.mapper = mapper;
        this.webClient = WebClient.builder().baseUrl(ollamaBaseUrl).build();
        this.maxToolRounds = maxToolRounds;
        this.maxToolCalls = maxToolCalls;
        this.numCtx = numCtx;
        this.temperature = Math.max(0.0, Math.min(2.0, temperature));
        this.topP = Math.max(0.0, Math.min(1.0, topP));
        this.topK = Math.max(1, topK);
        this.repeatPenalty = Math.max(0.0, repeatPenalty);
        this.enableThinking = enableThinking;
        this.keepAlive = keepAlive;
        this.maxToolsPerRequest = Math.max(ALWAYS_EXPOSED_TOOLS.size(), maxToolsPerRequest);
        this.projectToolkit = Set.copyOf(java.util.Arrays.stream(projectToolkit.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .toList());
        this.maxModelMessages = Math.max(2, maxModelMessages);
        this.maxMessageContentChars = Math.max(500, maxMessageContentChars);
        this.maxTotalMessageContentChars = Math.max(this.maxMessageContentChars, maxTotalMessageContentChars);
        this.defaultChatModel = defaultChatModel;
        this.defaultVisionModel = defaultVisionModel;
    }

    public Mono<List<String>> getModels() {
        return getModelDetails()
                .map(models -> models.stream().map(LocalModelInfo::name).toList());
    }

    public Mono<List<LocalModelInfo>> getModelDetails() {
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
        String chatModel = resolveChatModel(model, messages);

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

    private String resolveChatModel(String requestedModel, ArrayNode messages) {
        String selectedModel = normalizeChatModel(requestedModel);
        if (!conversationHasImages(messages) || isVisionModel(selectedModel, inferFamily(selectedModel))) {
            return selectedModel;
        }
        return normalizeChatModel(defaultVisionModel);
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
                    userId, directImageToolCall.name(), permissionArguments(directImageToolCall), workspaceRoots)) {
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
                    userId, directToolCall.name(), permissionArguments(directToolCall), workspaceRoots)) {
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
        return runTurn(chatModel, messages, state, 1);
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
        if (resolvedApprovalIds.containsKey(approvalId)) {
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
            PendingToolExecution pending = pendingToolExecutions.remove(approvalId);
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

            Disposable modelStream = webClient
                    .post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ollamaRequest)
                    .retrieve()
                    .bodyToFlux(String.class)
                    // Nada aqui limitava quanto tempo esperar por um novo pedaco do Ollama. Se o
                    // modelo travar gerando (contexto grande, maquina sobrecarregada), o pedido
                    // ficava pendurado pra sempre: sem erro, sem aviso, sem fim de stream. Esse
                    // timeout e por AUSENCIA de sinal (nao duracao total): uma geracao longa mas
                    // ativa nunca aciona isso, só um silencio real do Ollama por mais de 2 minutos.
                    .timeout(Duration.ofSeconds(120))
                    .subscribe(
                            chunk -> handleModelChunk(chunk, sink, capture),
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
                                flushPendingLine(capture, sink);
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

    private ObjectNode buildOllamaRequest(String model, ArrayNode messages, AgentRunState state) {
        ObjectNode ollamaRequest = mapper.createObjectNode();
        ollamaRequest.put("model", model);
        ollamaRequest.set("messages", withBackendIdentityPrompt(messages, state.workspaceRoots));
        ollamaRequest.put("stream", true);
        // Sem isso, o roteamento do raciocinio pro campo dedicado message.thinking (ver
        // handleModelChunk) fica a criterio do default do Ollama/modelo, que e inconsistente
        // entre versoes para modelos hibridos como qwen3 — o raciocinio pode vazar como
        // message.content normal em vez de ficar isolado no campo de thinking.
        ollamaRequest.put("think", enableThinking);
        ollamaRequest.put("keep_alive", keepAlive);
        ObjectNode options = ollamaRequest.putObject("options");
        options.put("num_ctx", numCtx);
        options.put("temperature", temperature);
        options.put("top_p", topP);
        options.put("top_k", topK);
        options.put("repeat_penalty", repeatPenalty);

        boolean conversationHasImages = conversationHasImages(messages);
        ArrayNode availableTools = toolGateway.listTools(state.userId, state.chatId, state.runId);
        ArrayNode tools = modelsWithoutToolSupport.contains(model) || conversationHasImages
                ? mapper.createArrayNode()
                : state.forceFullToolset
                        ? availableTools
                        : selectToolsForCurrentRequest(availableTools, messages, state);
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
        if (state.requiredToolName != null && !state.requiredToolName.isBlank()) {
            ArrayNode required = filterToolsByName(tools, Set.of(state.requiredToolName));
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
        // Pedidos explicitos de imagem/captura continuam cobertos pelos desvios abaixo.
        if (wantsImageGeneration(normalized)) {
            return filterToolsByName(tools, Set.of("generate_image"));
        }
        if (wantsScreenCapture(normalized)) {
            return filterToolsByName(tools, Set.of("capture_screen"));
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
                if (extras >= PROJECT_TOOLKIT_EXTRA_LIMIT) {
                    break;
                }
                String name = tool.path("name").asText("");
                if (!projectToolkit.contains(name) && intentRouter.shouldExposeTool(name, intentProfile)) {
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
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (shouldExposeTool(name, normalized, intentProfile, state)) {
                selectedTools.add(tool);
            }
        }
        return capToolCount(selectedTools);
    }

    // Preserva a ordem do catalogo: com o mesmo conjunto, o payload de tools fica identico
    // entre requisicoes do mesmo chat, que e o que permite o cache de prompt reaproveitar
    // o prefixo em vez de reprocessa-lo.
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
    private ArrayNode capToolCount(ArrayNode selectedTools) {
        if (selectedTools.size() <= maxToolsPerRequest) {
            return selectedTools;
        }
        ArrayNode capped = mapper.createArrayNode();
        for (JsonNode tool : selectedTools) {
            if (capped.size() >= maxToolsPerRequest) {
                break;
            }
            if (!ALWAYS_EXPOSED_TOOLS.contains(tool.path("name").asText(""))) {
                capped.add(tool);
            }
        }
        for (JsonNode tool : selectedTools) {
            if (capped.size() >= maxToolsPerRequest) {
                break;
            }
            if (ALWAYS_EXPOSED_TOOLS.contains(tool.path("name").asText(""))) {
                capped.add(tool);
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
            "open_nodes");

    private boolean shouldExposeTool(
            String toolName, String normalizedMessage, IntentProfile intentProfile, AgentRunState state) {
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

    private boolean wantsImageGeneration(String normalizedMessage) {
        return normalizedMessage.contains("gera imagem")
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

    private ArrayNode withBackendIdentityPrompt(ArrayNode messages, List<String> workspaceRoots) {
        ArrayNode guardedMessages = mapper.createArrayNode();
        ObjectNode identityMessage = guardedMessages.addObject();
        identityMessage.put("role", "system");
        identityMessage.put(
                "content",
                AGENT_SYSTEM_PROMPT
                        + policyInstructions()
                        + workspaceRootsBlock(workspaceRoots)
                        + conversationContinuityBlock(messages));
        guardedMessages.addAll(compactMessagesForModel(messages));
        return guardedMessages;
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
                "gere de novo");
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

        for (int index = start; index < candidates.size(); index++) {
            compacted.add(compactMessage(candidates.get(index)));
        }
        return compacted;
    }

    private ObjectNode compactMessage(JsonNode message) {
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
        compacted.put("content", compactContent(message.path("content").asText("")));
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
    private void handleModelChunk(String chunk, FluxSink<String> sink, TurnCapture capture) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        capture.lineBuffer.append(chunk);
        extractCompleteJsonObjects(capture.lineBuffer, line -> processOllamaChatLine(line, sink, capture));
    }

    private void flushPendingLine(TurnCapture capture, FluxSink<String> sink) {
        extractCompleteJsonObjects(capture.lineBuffer, line -> processOllamaChatLine(line, sink, capture));
        String remaining = capture.lineBuffer.toString().trim();
        capture.lineBuffer.setLength(0);
        if (!remaining.isEmpty()) {
            processOllamaChatLine(remaining, sink, capture);
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

    private void processOllamaChatLine(String line, FluxSink<String> sink, TurnCapture capture) {
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
                sink.next(tokenUsageEventChunk(node.path("eval_count").asInt(0)));
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

        logger.warn("Ollama stream failed for model {}", model, error);

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

        sink.next(eventChunk("agent.error", "Falha no modelo local", message));
        sink.next(contentChunk("\n> Erro ao chamar o modelo local `" + model + "`: " + message + "\n"));
        sink.complete();
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
        List<ToolCall> toolCalls = detectToolCalls(capture);
        if (toolCalls.isEmpty()) {
            planApprovedRuns.remove(state.runId);
            // Turno completamente vazio: o modelo gastou a rodada no thinking e terminou sem
            // texto E sem chamada de ferramenta (comportamento observado do qwen3). O retry
            // com toolset completo abaixo nao cobre chats com [Project Analysis] por design,
            // entao sem esta guarda a run completava em silencio absoluto. Uma unica nova
            // tentativa, com o pedido original repetido e uma instrucao explicita de agir.
            boolean emptyTurn = capture.assistantText.toString().isBlank();
            if (emptyTurn && !state.retriedEmptyTurn) {
                state.retriedEmptyTurn = true;
                sink.next(eventChunk(
                        "agent.round.retry",
                        "Resposta vazia — tentando de novo",
                        "O modelo terminou a rodada sem texto e sem ferramenta; repetindo com instrução"
                                + " explícita."));
                String originalRequest = lastUserMessage(messages);
                ArrayNode nudged = messages.deepCopy();
                ObjectNode nudge = nudged.addObject();
                nudge.put("role", "user");
                nudge.put(
                        "content",
                        (originalRequest == null || originalRequest.isBlank() ? "" : originalRequest + "\n\n")
                                + "[Aviso do Avento] Sua resposta anterior terminou vazia: sem texto e sem chamada"
                                + " de ferramenta. Responda agora de forma útil — chame a ferramenta adequada para"
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
            sink.next(eventChunk(
                    "agent.limit.reached",
                    "Limite de ferramentas atingido",
                    "A execucao foi interrompida para evitar loop infinito."));
            sink.next(contentChunk("\n> Limite de ferramentas atingido. Vou parar aqui para evitar loop infinito.\n"));
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
                                state.userId, toolCall.name(), permissionArguments(toolCall), state.workspaceRoots)
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
                    JsonNode toolResult = executeToolCall(messages, sink, toolCall, state.runId);
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
            JsonNode toolResult = executeToolCall(messages, sink, toolCall, state.runId);
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

    private List<ToolCall> detectToolCalls(TurnCapture capture) {
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
            detectedTools.addAll(detectTextualFallbackToolCalls(capture));
        }

        return detectedTools;
    }

    private List<ToolCall> detectTextualFallbackToolCalls(TurnCapture capture) {
        List<ToolCall> fallbackCalls = new ArrayList<>();
        String fullText = capture.assistantText.toString();
        Matcher functionMatcher = TEXTUAL_FUNCTION_PATTERN.matcher(fullText);
        if (functionMatcher.find()) {
            try {
                String toolName = functionMatcher.group(1);
                JsonNode parsed = mapper.readTree(functionMatcher.group(2));
                JsonNode arguments = parsed.has("arguments")
                        ? parsed.get("arguments")
                        : parsed.has("parameters") ? parsed.get("parameters") : parsed;
                String id = "call_textual_" + UUID.randomUUID().toString().substring(0, 8);
                ObjectNode nativeCall = mapper.createObjectNode();
                nativeCall.put("id", id);
                nativeCall.put("type", "function");
                ObjectNode function = mapper.createObjectNode();
                function.put("name", toolName);
                function.put("arguments", arguments.toString());
                nativeCall.set("function", function);
                capture.nativeToolCalls.add(nativeCall);
                fallbackCalls.add(new ToolCall(id, toolName, arguments));
                return fallbackCalls;
            } catch (Exception e) {
                logger.debug("Invalid textual function tool call detected", e);
            }
        }

        int startIdx = fullText.indexOf("{");
        int endIdx = fullText.lastIndexOf("}");
        if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) {
            return fallbackCalls;
        }

        String jsonCandidate = fullText.substring(startIdx, endIdx + 1);
        try {
            JsonNode parsed = mapper.readTree(jsonCandidate);
            if (!parsed.has("name") || (!parsed.has("parameters") && !parsed.has("arguments"))) {
                return fallbackCalls;
            }

            JsonNode arguments = parsed.has("arguments") ? parsed.get("arguments") : parsed.get("parameters");
            String id = "call_fallback_" + UUID.randomUUID().toString().substring(0, 8);

            ObjectNode nativeCall = mapper.createObjectNode();
            nativeCall.put("id", id);
            nativeCall.put("type", "function");
            ObjectNode function = mapper.createObjectNode();
            function.put("name", parsed.get("name").asText());
            function.put("arguments", arguments.toString());
            nativeCall.set("function", function);
            capture.nativeToolCalls.add(nativeCall);

            fallbackCalls.add(new ToolCall(id, parsed.get("name").asText(), arguments));
        } catch (Exception e) {
            logger.debug("No textual fallback tool call detected", e);
        }

        return fallbackCalls;
    }

    private boolean shouldSuppressTextualToolMarkup(TurnCapture capture, String content) {
        String normalized = content.trim().toLowerCase(Locale.ROOT);
        if (capture.suppressTextualToolMarkup) {
            return true;
        }
        if (normalized.contains("{function")
                || normalized.contains("function <")
                || normalized.matches("^\\{\\s*\"name\"\\s*:.*")
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
            JsonNode toolResult = executeToolCall(messages, sink, toolCall, runId);
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
            PendingToolExecution pending = pendingToolExecutions.remove(approvalId);
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
            JsonNode toolResult = executeToolCall(pending.messages(), sink, pending.toolCall(), pending.runId());
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
        if (approvalId == null || !approvalOwnedBy(approvalId, userId)) {
            return null;
        }

        return new ApprovalVoiceCommand(approvalId, decision, approvalMemory(normalized), lastUserMessage.trim());
    }

    public boolean approvalOwnedBy(String approvalId, UUID userId) {
        PendingToolExecution pending = pendingToolExecutions.get(approvalId);
        if (pending == null) {
            return false;
        }
        UUID ownerId = toolUserId(pending.toolCall());
        return userId == null ? ownerId == null : userId.equals(ownerId);
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
        String lower = message.toLowerCase(Locale.ROOT);
        int marker = lower.lastIndexOf("responda ao seguinte pedido");
        if (marker < 0) {
            return message;
        }

        int separator = message.indexOf(":\n\n", marker);
        if (separator < 0) {
            return message;
        }

        String extracted = message.substring(separator + 3).trim();
        return extracted.isBlank() ? message : extracted;
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
        int toolCount = toolGateway.listTools().size();
        return String.join(
                        "\n",
                        "Neste momento tenho " + toolCount + " ferramentas registradas no Avento.",
                        "",
                        "Elas me permitem ajudar com desenvolvimento local de algumas formas:",
                        "",
                        "- analisar projetos e explicar arquitetura, riscos e próximos passos;",
                        "- ler arquivos autorizados e sugerir alterações com diff;",
                        "- rodar validações seguras, como testes, build, lint e Maven;",
                        "- operar ferramentas locais quando você pedir claramente, como abrir app, nova aba ou Finder;",
                        "- conversar por voz e responder com TTS quando estiver habilitado.",
                        "",
                        "Para ações no sistema, eu só devo agir quando o pedido for explícito, tipo `abre o Brave` ou `fecha o Terminal`.")
                + "\n";
    }

    private String identityResponse() {
        return String.join(
                        "\n",
                        "Sou o Avento, um assistente inteligente criado por **Avento contributors** (Flana Digital).",
                        "",
                        "Eu opero diretamente no seu ambiente local, o que significa que consigo ler seus projetos, analisar código, sugerir melhorias e operar ferramentas do macOS sempre que você precisar — tudo com foco em privacidade, velocidade e automação inteligente.",
                        "",
                        "Como posso te ajudar no desenvolvimento hoje?")
                + "\n";
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

    private void appendAssistantToolRequest(ArrayNode messages, TurnCapture capture) {
        ObjectNode assistantMsg = mapper.createObjectNode();
        assistantMsg.put("role", "assistant");
        String fullText = capture.assistantText.toString();
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

    private JsonNode executeToolCall(ArrayNode messages, FluxSink<String> sink, ToolCall toolCall, String runId) {
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
            toolMsg.put("content", toolResult.toString());
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
            default -> "\nAção executada com sucesso.\n";
        };
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
        Set<String> requiredToolNames = new java.util.HashSet<>();
        Integer maxToolRoundsOverride = null;
        ImageGenerationOptions imageOptions = ImageGenerationOptions.defaults();
        Long chatId;
        UUID userId;
        boolean forceFullToolset = false;
        boolean retriedWithFullToolset = false;
        boolean retriedEmptyTurn = false;
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
