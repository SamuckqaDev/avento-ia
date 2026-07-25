package com.avento.service;

import com.avento.service.dto.*;
import com.avento.service.dto.LocalModelInfo;
import com.avento.service.image.ImageGenerationOptions;
import com.avento.service.image.ImageModelPreset;
import com.avento.service.image.ImageModelPresetCatalog;
import com.avento.service.image.ImagePromptPlan;
import com.avento.service.image.ImagePromptPlanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class ComfyUiImageService {

    private static final Logger logger = LoggerFactory.getLogger(ComfyUiImageService.class);

    private static final String COMFY_MODEL_PREFIX = "comfyui:";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int DEFAULT_IMAGE_TIMEOUT_MINUTES = 20;
    // Video diffusion é muito mais pesado que imagem — num Mac de 16GB, poucos segundos de vídeo
    // levam vários minutos. O timeout maior é por geração inteira, não por chunk.
    private static final Duration VIDEO_GENERATION_TIMEOUT = Duration.ofMinutes(25);
    private static final int MIN_DIMENSION = 256;
    private static final int MAX_DIMENSION = 1024;
    private static final int MIN_VIDEO_FRAMES = 33;
    private static final int MAX_VIDEO_FRAMES = 81;
    private static final int VIDEO_FPS = 16;
    private static final String DEFAULT_VIDEO_DIFFUSION_MODEL = "wan2.2_ti2v_5B_fp16.safetensors";
    private static final String DEFAULT_VIDEO_TEXT_ENCODER = "umt5_xxl_fp8_e4m3fn_scaled.safetensors";
    private static final String DEFAULT_VIDEO_VAE = "wan2.2_vae.safetensors";
    private static final String DEFAULT_IMAGE_VAE = "vae-ft-mse-840000-ema-pruned.safetensors";
    private static final String DEFAULT_OPENPOSE_MODEL = "control_v11p_sd15_openpose.pth";
    private static final String DEFAULT_SDXL_VAE = "sdxl_vae.safetensors";
    private static final String DEFAULT_SDXL_OPENPOSE_MODEL = "xinsir-openpose-sdxl-1.0.safetensors";
    private static final String DEFAULT_SDXL_CANNY_MODEL = "xinsir-canny-sdxl-1.0.safetensors";
    private static final String DEFAULT_SDXL_DEPTH_MODEL = "xinsir-depth-sdxl-1.0.safetensors";
    private static final String DEFAULT_FLUX2_TEXT_ENCODER = "qwen_3_4b.safetensors";
    private static final String DEFAULT_FLUX2_VAE = "flux2-vae.safetensors";
    private static final int MAX_POSE_REFERENCE_BYTES = 6 * 1024 * 1024;
    private static final long SEVERE_MEMORY_PRESSURE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long LOW_MEMORY_PRESSURE_BYTES = 4L * 1024 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final ImageModelPresetCatalog presetCatalog;
    private final WebClient webClient;
    private final HttpClient httpClient;
    private final ConcurrentMap<Long, String> activeImagePrompts = new ConcurrentHashMap<>();

    @Value("${avento.image.provider:auto}")
    private String provider;

    @Value("${avento.comfyui.enabled:true}")
    private boolean enabled;

    @Value("${avento.comfyui.base-url:http://127.0.0.1:8188}")
    private String baseUrl;

    @Value("${avento.comfyui.workflow:classpath:comfyui/workflows/text-to-image-api.json}")
    private String workflowLocation;

    @Value("${avento.comfyui.sdxl-workflow:classpath:comfyui/workflows/sdxl-text-to-image-api.json}")
    private String sdxlWorkflowLocation;

    @Value("${avento.comfyui.sdxl-vae:" + DEFAULT_SDXL_VAE + "}")
    private String sdxlVae = DEFAULT_SDXL_VAE;

    @Value("${avento.comfyui.sdxl-openpose-model:" + DEFAULT_SDXL_OPENPOSE_MODEL + "}")
    private String sdxlOpenPoseModel = DEFAULT_SDXL_OPENPOSE_MODEL;

    @Value("${avento.comfyui.sdxl-canny-model:" + DEFAULT_SDXL_CANNY_MODEL + "}")
    private String sdxlCannyModel = DEFAULT_SDXL_CANNY_MODEL;

    @Value("${avento.comfyui.sdxl-depth-model:" + DEFAULT_SDXL_DEPTH_MODEL + "}")
    private String sdxlDepthModel = DEFAULT_SDXL_DEPTH_MODEL;

    @Value("${avento.comfyui.flux2-workflow:classpath:comfyui/workflows/flux2-klein-text-to-image-api.json}")
    private String flux2WorkflowLocation;

    @Value("${avento.comfyui.flux2-text-encoder:" + DEFAULT_FLUX2_TEXT_ENCODER + "}")
    private String flux2TextEncoder = DEFAULT_FLUX2_TEXT_ENCODER;

    @Value("${avento.comfyui.flux2-vae:" + DEFAULT_FLUX2_VAE + "}")
    private String flux2Vae = DEFAULT_FLUX2_VAE;

    @Value("${avento.comfyui.video-workflow:classpath:comfyui/workflows/text-to-video-api.json}")
    private String videoWorkflowLocation;

    @Value("${avento.comfyui.video-diffusion-model:" + DEFAULT_VIDEO_DIFFUSION_MODEL + "}")
    private String videoDiffusionModel = DEFAULT_VIDEO_DIFFUSION_MODEL;

    @Value("${avento.comfyui.video-text-encoder:" + DEFAULT_VIDEO_TEXT_ENCODER + "}")
    private String videoTextEncoder = DEFAULT_VIDEO_TEXT_ENCODER;

    @Value("${avento.comfyui.video-vae:" + DEFAULT_VIDEO_VAE + "}")
    private String videoVae = DEFAULT_VIDEO_VAE;

    @Value("${avento.comfyui.default-model:}")
    private String defaultModel;

    @Value("${avento.comfyui.image-vae:" + DEFAULT_IMAGE_VAE + "}")
    private String imageVae = DEFAULT_IMAGE_VAE;

    @Value("${avento.comfyui.openpose-model:" + DEFAULT_OPENPOSE_MODEL + "}")
    private String openPoseModel = DEFAULT_OPENPOSE_MODEL;

    @Value("${avento.comfyui.image-timeout-minutes:" + DEFAULT_IMAGE_TIMEOUT_MINUTES + "}")
    private int imageTimeoutMinutes = DEFAULT_IMAGE_TIMEOUT_MINUTES;

    @Value("${avento.comfyui.memory-guard-enabled:true}")
    private boolean memoryGuardEnabled = true;

    @Value("${spring.ai.ollama.base-url:http://127.0.0.1:11434}")
    private String ollamaBaseUrl = "http://127.0.0.1:11434";

    @Value("${avento.agent.vision-model:qwen2.5vl:7b}")
    private String visionModel = "qwen2.5vl:7b";

    @Value("${avento.comfyui.adherence-min-score:85}")
    private int adherenceMinScore = 85;

    public ComfyUiImageService(
            ObjectMapper mapper,
            @Value("${avento.comfyui.base-url:http://127.0.0.1:8188}") String configuredBaseUrl,
            ImageModelPresetCatalog presetCatalog) {
        this.mapper = mapper;
        this.presetCatalog = presetCatalog;
        this.webClient = WebClient.builder()
                .baseUrl(trimTrailingSlash(configuredBaseUrl))
                .build();
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    public boolean isEnabled() {
        return enabled && !"ollama".equalsIgnoreCase(provider);
    }

    public boolean shouldUseComfy(String model) {
        return isEnabled() && (isComfyModel(model) || "comfyui".equalsIgnoreCase(provider));
    }

    public boolean isComfyOnly() {
        return "comfyui".equalsIgnoreCase(provider);
    }

    public boolean isComfyModel(String model) {
        return model != null && model.startsWith(COMFY_MODEL_PREFIX);
    }

    public Mono<List<LocalModelInfo>> getModels() {
        if (!isEnabled()) {
            return Mono.just(List.of());
        }
        return Mono.zip(loadModelList("checkpoints"), loadModelList("diffusion_models"))
                .map(modelLists -> {
                    List<LocalModelInfo> models = new ArrayList<>();
                    models.addAll(parseModels(modelLists.getT1(), false));
                    models.addAll(parseModels(modelLists.getT2(), true));
                    return models;
                })
                .onErrorReturn(List.of());
    }

    private Mono<JsonNode> loadModelList(String category) {
        return webClient
                .get()
                .uri("/models/" + category)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorResume(error -> webClient
                        .get()
                        .uri("/api/models/" + category)
                        .retrieve()
                        .bodyToMono(JsonNode.class))
                .onErrorReturn(mapper.createArrayNode());
    }

    public ObjectNode generateImage(String prompt, String model, String size) {
        ImageGenerationOptions options = ImageGenerationOptions.defaults();
        return generateImage(ImagePromptPlanner.plan(prompt, options), model, size, options);
    }

    public ObjectNode generateImage(
            ImagePromptPlan promptPlan, String model, String size, ImageGenerationOptions options) {
        long startedNanos = System.nanoTime();
        ObjectNode result = mapper.createObjectNode();
        ImageGenerationOptions requestedOptions = options == null ? ImageGenerationOptions.defaults() : options;
        List<String> workflowWarnings = new ArrayList<>();
        ImageGenerationOptions effectiveOptions = applyMemoryBudget(requestedOptions, workflowWarnings);
        long seed = effectiveOptions.seed() == null
                ? Math.floorMod(UUID.randomUUID().getMostSignificantBits(), Long.MAX_VALUE)
                : effectiveOptions.seed();
        try {
            String selectedModel = stripPrefix(model);
            if (isComfyOnly() && !isComfyModel(model)) {
                selectedModel = defaultModel.trim();
            }
            if (selectedModel.isBlank()) {
                selectedModel = defaultModel.trim();
            }
            if (selectedModel.isBlank()) {
                logger.warn("ComfyUI image rejected because no checkpoint is configured");
                return failed(
                        result,
                        "Nenhum checkpoint do ComfyUI foi selecionado.",
                        "Configure avento.comfyui.default-model ou escolha um checkpoint no header.");
            }

            boolean flux2Klein = isFlux2KleinModel(selectedModel);
            boolean sdxl = isSdxlModel(selectedModel);
            ImageModelPreset preset = presetCatalog.forModel(selectedModel);
            int[] dimensions = preset.hasNativeDimensions()
                    ? preset.dimensions(effectiveOptions.qualityPreset(), effectiveOptions.aspectRatio())
                    : parseSize(size);
            logger.info(
                    "ComfyUI image preparing model={} workflow={} size={}x{} preset={}",
                    selectedModel,
                    flux2Klein ? "flux2-klein" : sdxl ? "sdxl" : "stable-diffusion",
                    dimensions[0],
                    dimensions[1],
                    effectiveOptions.qualityPreset());
            long finalSeed = seed;
            String retryCorrection = "";
            PreparedImageWorkflow prepared = null;
            byte[] imageBytes = null;
            AdherenceReview adherenceReview = AdherenceReview.unavailable();
            int attempts = 0;
            int maxRetries = effectiveOptions.adherenceValidationEnabled() ? effectiveOptions.maxAdherenceRetries() : 0;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                attempts = attempt + 1;
                finalSeed = Math.floorMod(seed + attempt, Long.MAX_VALUE);
                String attemptPrompt = promptPlan.positivePrompt();
                String attemptOriginal = promptPlan.originalPrompt();
                if (!retryCorrection.isBlank()) {
                    attemptPrompt += ", strict correction for the next candidate: " + retryCorrection;
                    // Modelos de prompt natural (FLUX.2) leem originalPrompt, nao positivePrompt —
                    // a correcao de aderencia precisa chegar la tambem, como frase.
                    attemptOriginal += ". Strict correction for this attempt: " + retryCorrection;
                }
                ImagePromptPlan attemptPlan = new ImagePromptPlan(
                        attemptOriginal,
                        attemptPrompt,
                        promptPlan.negativePrompt(),
                        promptPlan.subjectCount(),
                        promptPlan.humanSubject());
                prepared = prepareImageWorkflow(
                        attemptPlan,
                        selectedModel,
                        dimensions,
                        effectiveOptions,
                        finalSeed,
                        flux2Klein,
                        sdxl,
                        preset,
                        workflowWarnings);
                imageBytes = executeImageWorkflow(prepared.workflow(), selectedModel, attempt + 1);

                if (!effectiveOptions.adherenceValidationEnabled()) {
                    break;
                }
                releaseComfyMemory();
                adherenceReview = reviewImageAdherence(promptPlan.originalPrompt(), imageBytes);
                if (!adherenceReview.available()
                        || adherenceReview.score() >= boundedAdherenceMinScore()
                        || attempt >= maxRetries) {
                    break;
                }
                retryCorrection = adherenceReview.correction();
                workflowWarnings.add("Aderência visual " + adherenceReview.score()
                        + "/100: uma nova tentativa foi iniciada para corrigir "
                        + String.join(", ", adherenceReview.missing()));
            }

            if (prepared == null || imageBytes == null) {
                return failed(
                        result, "O ComfyUI terminou sem retornar uma imagem.", "Nenhuma tentativa foi concluída.");
            }
            Path outputDirectory = Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images");
            Files.createDirectories(outputDirectory);
            String filename = "avento-image-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".png";
            Path outputPath = outputDirectory.resolve(filename);
            Files.write(outputPath, imageBytes);
            logger.info(
                    "ComfyUI image completed attempts={} in {}s output={}",
                    attempts,
                    elapsedSeconds(startedNanos),
                    outputPath);

            result.put("status", "success");
            result.put("provider", "comfyui");
            result.put("path", outputPath.toString());
            result.put("sizeBytes", imageBytes.length);
            result.put("model", COMFY_MODEL_PREFIX + selectedModel);
            result.put("size", dimensions[0] + "x" + dimensions[1]);
            result.put("prompt", promptPlan.originalPrompt());
            result.put("enhancedPrompt", promptPlan.positivePrompt());
            result.put("negativePrompt", promptPlan.negativePrompt());
            result.put("qualityPreset", effectiveOptions.qualityPreset());
            result.put("subjectType", effectiveOptions.subjectType());
            result.put("steps", imageSteps(effectiveOptions, preset));
            result.put("cfg", imageCfg(effectiveOptions, preset));
            result.put("preset", preset.name());
            result.put("seed", finalSeed);
            result.put("attempts", attempts);
            result.put("adherenceValidationEnabled", effectiveOptions.adherenceValidationEnabled());
            if (adherenceReview.available()) {
                result.put("adherenceScore", adherenceReview.score());
                ArrayNode missing = result.putArray("adherenceMissing");
                adherenceReview.missing().forEach(missing::add);
            }
            result.put("subjectCount", promptPlan.subjectCount());
            result.put("refinementEnabled", !flux2Klein && effectiveOptions.refinementEnabled());
            result.put(
                    "refinementSteps",
                    !flux2Klein && effectiveOptions.refinementEnabled()
                            ? imageRefinementSteps(effectiveOptions, preset)
                            : 0);
            result.put("refinementStrength", effectiveOptions.refinementStrength());
            result.put("detailMode", prepared.detailModeApplied());
            result.put("referenceImageApplied", prepared.referenceApplied());
            result.put("identityReferenceApplied", prepared.identityReferenceApplied());
            result.put("structureReferenceApplied", prepared.structureReferenceApplied());
            result.put("referenceMode", effectiveOptions.referenceMode());
            result.put("structureControl", effectiveOptions.structureControl());
            result.put("structureStrength", effectiveOptions.structureStrength());
            result.put("referenceStrength", effectiveOptions.referenceStrength());
            result.put("poseReferenceApplied", prepared.poseApplied());
            result.put("poseStrength", effectiveOptions.poseStrength());
            if (!workflowWarnings.isEmpty()) {
                ArrayNode warnings = result.putArray("warnings");
                workflowWarnings.forEach(warnings::add);
            }
            result.put("message", "Imagem gerada pelo ComfyUI e salva em " + outputPath);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("ComfyUI image interrupted after {}s", elapsedSeconds(startedNanos));
            return failed(result, "A geração no ComfyUI foi interrompida.", exception.getMessage());
        } catch (Exception exception) {
            logger.warn("ComfyUI image failed after {}s: {}", elapsedSeconds(startedNanos), safeMessage(exception));
            return failed(result, "Não foi possível conectar ou gerar imagem no ComfyUI.", exception.getMessage());
        }
    }

    private PreparedImageWorkflow prepareImageWorkflow(
            ImagePromptPlan promptPlan,
            String selectedModel,
            int[] dimensions,
            ImageGenerationOptions options,
            long seed,
            boolean flux2Klein,
            boolean sdxl,
            ImageModelPreset preset,
            List<String> workflowWarnings)
            throws IOException {
        ObjectNode workflow =
                loadWorkflow(flux2Klein ? flux2WorkflowLocation : sdxl ? sdxlWorkflowLocation : workflowLocation);
        if (flux2Klein) {
            // O encoder do FLUX.2 e um LLM (qwen): segue frases naturais e trata a sopa de
            // tags do planner SDXL como ruido — era a causa das imagens ignorarem o pedido.
            String fluxPrompt = preset.usesNaturalPrompt() ? promptPlan.originalPrompt() : promptPlan.positivePrompt();
            applyFlux2WorkflowInputs(workflow, fluxPrompt, selectedModel, dimensions, seed, options, preset);
            appendUnsupportedFlux2Warnings(options, workflowWarnings);
            return new PreparedImageWorkflow(workflow, false, false, false, false, "native");
        }

        applyWorkflowInputs(
                workflow,
                promptPlan.positivePrompt(),
                promptPlan.negativePrompt(),
                selectedModel,
                dimensions[0],
                dimensions[1],
                options,
                seed,
                sdxl,
                preset);
        JsonNode objectInfo = loadOptionalImageNodeInfo(options, promptPlan.humanSubject(), workflowWarnings);
        boolean referenceApplied = configureImageReference(workflow, options, objectInfo, dimensions, workflowWarnings);
        boolean identityReferenceApplied =
                configureIdentityReference(workflow, options, objectInfo, promptPlan.humanSubject(), workflowWarnings);
        boolean structureReferenceApplied =
                configureStructureReference(workflow, options, objectInfo, sdxl, workflowWarnings);
        boolean poseApplied = configurePoseReference(workflow, options, objectInfo, sdxl, workflowWarnings);
        String detailModeApplied = configureDetailing(
                workflow,
                options,
                objectInfo,
                seed,
                promptPlan.humanSubject(),
                promptPlan.subjectCount(),
                workflowWarnings);
        return new PreparedImageWorkflow(
                workflow,
                referenceApplied,
                identityReferenceApplied,
                structureReferenceApplied,
                poseApplied,
                detailModeApplied);
    }

    private byte[] executeImageWorkflow(ObjectNode workflow, String selectedModel, int attempt)
            throws IOException, InterruptedException {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.set("prompt", workflow);
        requestBody.put("client_id", "avento-" + UUID.randomUUID());

        JsonNode queued = postJson("/prompt", requestBody);
        String promptId = queued.path("prompt_id").asText("");
        if (promptId.isBlank()) {
            throw new IOException("O ComfyUI não retornou um prompt_id: " + queued);
        }
        logger.info("ComfyUI image queued promptId={} model={} attempt={}", promptId, selectedModel, attempt);
        long workerId = Thread.currentThread().threadId();
        activeImagePrompts.put(workerId, promptId);
        try {
            ImageReference image = waitForImage(promptId);
            if (image == null) {
                throw new IOException("O ComfyUI terminou sem retornar uma imagem.");
            }
            return getImage(image);
        } finally {
            activeImagePrompts.remove(workerId, promptId);
        }
    }

    public void cancelImageGeneration(Thread worker) {
        if (worker == null) {
            return;
        }
        String promptId = activeImagePrompts.get(worker.threadId());
        if (promptId == null || promptId.isBlank()) {
            return;
        }
        try {
            cancelPrompt(promptId);
        } catch (IOException exception) {
            logger.warn("Could not cancel ComfyUI image prompt {}: {}", promptId, safeMessage(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseComfyMemory() {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("unload_models", true);
            body.put("free_memory", true);
            postWithoutResponse("/free", body);
        } catch (Exception exception) {
            logger.debug("ComfyUI memory release before visual validation failed: {}", safeMessage(exception));
        }
    }

    private AdherenceReview reviewImageAdherence(String requestedPrompt, byte[] imageBytes) {
        if (visionModel == null || visionModel.isBlank() || imageBytes == null || imageBytes.length == 0) {
            return AdherenceReview.unavailable();
        }
        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("model", visionModel.trim());
            request.put("stream", false);
            request.put("format", "json");
            request.put("keep_alive", 0);
            ObjectNode options = request.putObject("options");
            options.put("temperature", 0.0);
            ObjectNode message = request.putArray("messages").addObject();
            message.put("role", "user");
            message.put(
                    "content",
                    "Act as a visual quality evaluator. Compare the generated image with the exact user request below. "
                            + "Judge subject, count, identity attributes, objects, colors, pose, spatial relationships, "
                            + "framing and photorealism. Do not discuss policy and do not rewrite the request. Return only "
                            + "JSON with score (0-100), missing (array of short factual mismatches), and correction (one "
                            + "concise positive prompt instruction for another generation). User request: "
                            + requestedPrompt);
            message.putArray("images").add(Base64.getEncoder().encodeToString(imageBytes));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(ollamaBaseUrl) + "/api/chat"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("Visual adherence validation returned HTTP {}", response.statusCode());
                return AdherenceReview.unavailable();
            }
            String content = mapper.readTree(response.body())
                    .path("message")
                    .path("content")
                    .asText("");
            int objectStart = content.indexOf('{');
            int objectEnd = content.lastIndexOf('}');
            if (objectStart < 0 || objectEnd <= objectStart) {
                return AdherenceReview.unavailable();
            }
            JsonNode review = mapper.readTree(content.substring(objectStart, objectEnd + 1));
            int rawScore = review.path("score").asInt(-1);
            if (rawScore < 0) {
                return AdherenceReview.unavailable();
            }
            int score = Math.max(0, Math.min(100, rawScore));
            List<String> missing = new ArrayList<>();
            if (review.path("missing").isArray()) {
                review.path("missing").forEach(item -> {
                    String value = item.asText("").trim();
                    if (!value.isBlank() && missing.size() < 8) {
                        missing.add(value);
                    }
                });
            }
            String correction = review.path("correction").asText("").trim();
            if (correction.length() > 800) {
                correction = correction.substring(0, 800);
            }
            if (correction.isBlank()) {
                correction = missing.isEmpty()
                        ? "follow every requested visual requirement exactly"
                        : String.join(", ", missing);
            }
            logger.info("Visual adherence score={} missing={}", score, missing);
            return new AdherenceReview(true, score, List.copyOf(missing), correction);
        } catch (Exception exception) {
            logger.warn("Visual adherence validation unavailable: {}", safeMessage(exception));
            return AdherenceReview.unavailable();
        }
    }

    private int boundedAdherenceMinScore() {
        return Math.max(60, Math.min(98, adherenceMinScore));
    }

    public ObjectNode generateVideo(String prompt, String size, int seconds) {
        ObjectNode result = mapper.createObjectNode();
        try {
            VideoSubmission submission = submitVideo(prompt, size, seconds);
            ImageReference video = waitForOutput(submission.promptId(), VIDEO_GENERATION_TIMEOUT);
            if (video == null) {
                return failedVideo(
                        result,
                        "O ComfyUI terminou sem retornar um vídeo.",
                        "Confira se os modelos de vídeo listados no workflow estão instalados.");
            }

            Path outputPath = saveVideoOutput(video);

            result.put("status", "success");
            result.put("provider", "comfyui");
            result.put("path", outputPath.toString());
            result.put("sizeBytes", Files.size(outputPath));
            result.put("frames", submission.frames());
            result.put("fps", submission.fps());
            result.put("size", submission.width() + "x" + submission.height());
            result.put("prompt", prompt);
            result.put("message", "Vídeo gerado pelo ComfyUI e salvo em " + outputPath);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failedVideo(result, "A geração de vídeo no ComfyUI foi interrompida.", exception.getMessage());
        } catch (Exception exception) {
            return failedVideo(result, "Não foi possível conectar ou gerar vídeo no ComfyUI.", exception.getMessage());
        }
    }

    public VideoSubmission submitVideo(String prompt, String size, int seconds)
            throws IOException, InterruptedException {
        return submitVideo(prompt, size, seconds, null);
    }

    public VideoSubmission submitVideo(String prompt, String size, int seconds, Path sourceImage)
            throws IOException, InterruptedException {
        if (!isEnabled()) {
            throw new IOException("Geração de vídeo exige o ComfyUI como provider.");
        }

        List<String> missingModels = missingVideoModels();
        if (!missingModels.isEmpty()) {
            throw new IOException("Modelos de vídeo ausentes no ComfyUI: " + String.join(", ", missingModels));
        }

        int[] dimensions = parseVideoSize(size);
        int frames = boundedFrames(seconds);
        ObjectNode workflow = loadWorkflow(videoWorkflowLocation);
        applyVideoWorkflowInputs(workflow, prompt, dimensions[0], dimensions[1], frames);
        if (sourceImage != null) {
            applyVideoSourceImage(workflow, sourceImage);
        }
        int steps = findVideoSteps(workflow);
        String clientId = "avento-video-" + UUID.randomUUID();

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.set("prompt", workflow);
        requestBody.put("client_id", clientId);

        JsonNode queued = postJson("/prompt", requestBody);
        String promptId = queued.path("prompt_id").asText("");
        if (promptId.isBlank()) {
            throw new IOException("O ComfyUI não retornou um prompt_id: " + queued);
        }
        return new VideoSubmission(promptId, clientId, dimensions[0], dimensions[1], frames, VIDEO_FPS, steps);
    }

    public VideoStatus inspectVideo(String promptId) throws IOException, InterruptedException {
        JsonNode history = getJson("/history/" + urlEncode(promptId));
        JsonNode promptHistory = history.path(promptId);
        ImageReference output = findImage(promptHistory.path("outputs"));
        if (output != null) {
            return new VideoStatus("completed", output, "");
        }

        String status = promptHistory.path("status").path("status_str").asText("");
        if ("error".equalsIgnoreCase(status)) {
            return new VideoStatus(
                    "failed",
                    null,
                    promptHistory.path("status").path("messages").toString());
        }

        JsonNode queue = getJson("/queue");
        if (queueContains(queue.path("queue_running"), promptId)) {
            return new VideoStatus("running", null, "");
        }
        if (queueContains(queue.path("queue_pending"), promptId)) {
            return new VideoStatus("queued", null, "");
        }
        return new VideoStatus(promptHistory.isMissingNode() ? "missing" : "running", null, "");
    }

    public Path saveVideoOutput(ImageReference video) throws IOException, InterruptedException {
        byte[] videoBytes = getImage(video);
        Path outputDirectory = Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images");
        Files.createDirectories(outputDirectory);
        String extension = video.filename().contains(".")
                ? video.filename().substring(video.filename().lastIndexOf('.'))
                : ".webp";
        String filename = "avento-video-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + extension;
        Path outputPath = outputDirectory.resolve(filename);
        Files.write(outputPath, videoBytes);
        return outputPath;
    }

    public void cancelVideo(String promptId) throws IOException, InterruptedException {
        cancelPrompt(promptId);
    }

    private void cancelPrompt(String promptId) throws IOException, InterruptedException {
        JsonNode queue = getJson("/queue");
        if (queueContains(queue.path("queue_pending"), promptId)) {
            ObjectNode body = mapper.createObjectNode();
            body.putArray("delete").add(promptId);
            postWithoutResponse("/queue", body);
        }
        if (queueContains(queue.path("queue_running"), promptId)) {
            postWithoutResponse("/interrupt", mapper.createObjectNode());
        }
    }

    private int findVideoSteps(ObjectNode workflow) {
        var fields = workflow.fields();
        while (fields.hasNext()) {
            JsonNode node = fields.next().getValue();
            if ("KSampler".equals(node.path("class_type").asText())) {
                return Math.max(1, node.path("inputs").path("steps").asInt(20));
            }
        }
        return 20;
    }

    private boolean queueContains(JsonNode entries, String promptId) {
        if (!entries.isArray()) {
            return false;
        }
        for (JsonNode entry : entries) {
            if (entry.isArray()
                    && entry.size() > 1
                    && promptId.equals(entry.get(1).asText())) {
                return true;
            }
        }
        return false;
    }

    // WAN 2.2 TI2V usa um único workflow para texto e imagem. Sem start_image ele cria do zero;
    // com start_image preserva o quadro inicial e aplica o movimento descrito no prompt.
    private void applyVideoWorkflowInputs(ObjectNode workflow, String prompt, int width, int height, int frames) {
        workflow.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            if (!node.isObject()) {
                return;
            }
            String classType = node.path("class_type").asText("");
            JsonNode inputsNode = node.path("inputs");
            if (!inputsNode.isObject()) {
                return;
            }
            ObjectNode inputs = (ObjectNode) inputsNode;
            switch (classType) {
                case "UNETLoader" -> inputs.put("unet_name", videoDiffusionModel);
                case "CLIPLoader" -> inputs.put("clip_name", videoTextEncoder);
                case "VAELoader" -> inputs.put("vae_name", videoVae);
                case "CLIPTextEncode" -> {
                    String text = inputs.path("text").asText("");
                    if ("__POSITIVE_PROMPT__".equals(text)) {
                        inputs.put("text", prompt);
                    } else if ("__NEGATIVE_PROMPT__".equals(text)) {
                        inputs.put(
                                "text",
                                "low quality, blurry, distorted, deformed, static image, watermark, text, jittery"
                                        + " motion, flickering");
                    }
                }
                case "Wan22ImageToVideoLatent",
                        "EmptyHunyuanLatentVideo",
                        "EmptyLatentVideo",
                        "EmptyMochiLatentVideo" -> {
                    inputs.put("width", width);
                    inputs.put("height", height);
                    inputs.put("length", frames);
                    inputs.put("batch_size", 1);
                }
                case "KSampler" -> inputs.put("seed", Math.abs(UUID.randomUUID().getMostSignificantBits()));
                case "SaveAnimatedWEBP" -> inputs.put("fps", VIDEO_FPS);
                default -> {
                    // Loaders e nós custom ficam como estão no workflow.
                }
            }
        });
    }

    private void applyVideoSourceImage(ObjectNode workflow, Path sourceImage) throws IOException, InterruptedException {
        Path normalized = sourceImage.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("A imagem de referência do vídeo não existe mais: " + normalized);
        }
        String uploadedName = uploadImage(normalized, "avento-video-source-");
        connectVideoSourceImage(workflow, uploadedName);
    }

    private void connectVideoSourceImage(ObjectNode workflow, String uploadedName) throws IOException {
        ObjectNode loadImage = addNode(workflow, "avento_video_source", "LoadImage", "Avento Video Source");
        loadImage.with("inputs").put("image", uploadedName);

        boolean connected = false;
        var fields = workflow.fields();
        while (fields.hasNext()) {
            JsonNode node = fields.next().getValue();
            if ("Wan22ImageToVideoLatent".equals(node.path("class_type").asText(""))) {
                ((ObjectNode) node.path("inputs")).set("start_image", reference("avento_video_source", 0));
                connected = true;
                break;
            }
        }
        if (!connected) {
            throw new IOException("O workflow de vídeo não possui o nó Wan22ImageToVideoLatent.");
        }
    }

    private int[] parseVideoSize(String size) {
        String[] parts =
                size == null ? new String[0] : size.toLowerCase(Locale.ROOT).split("x");
        int width = alignVideoDimension(parts.length == 2 ? parseDimension(parts[0], 832) : 832);
        int height = alignVideoDimension(parts.length == 2 ? parseDimension(parts[1], 480) : 480);
        return new int[] {width, height};
    }

    private int alignVideoDimension(int dimension) {
        return Math.max(MIN_DIMENSION, dimension - dimension % 32);
    }

    private int boundedFrames(int seconds) {
        int requested = seconds <= 0 ? 2 * VIDEO_FPS : seconds * VIDEO_FPS;
        int bounded = Math.max(MIN_VIDEO_FRAMES, Math.min(MAX_VIDEO_FRAMES, requested));
        // Modelos WAN esperam length no formato 4n+1.
        return bounded - ((bounded - 1) % 4);
    }

    private ObjectNode failedVideo(ObjectNode result, String error, String details) {
        result.put("status", "failed");
        result.put("provider", "comfyui");
        result.put("error", error);
        if (details != null && !details.isBlank()) {
            result.put("details", details);
        }
        result.put(
                "hint",
                "Vídeo usa o workflow em " + videoWorkflowLocation
                        + " (WAN 2.2 TI2V 5B por padrão). Execute ./scripts/setup-comfyui-video.sh para instalar"
                        + " ou retomar o download dos modelos oficiais.");
        return result;
    }

    private List<String> missingVideoModels() throws IOException, InterruptedException {
        List<String> missing = new ArrayList<>();
        if (!comfyModelExists("diffusion_models", videoDiffusionModel)) {
            missing.add("models/diffusion_models/" + videoDiffusionModel);
        }
        if (!comfyModelExists("text_encoders", videoTextEncoder)) {
            missing.add("models/text_encoders/" + videoTextEncoder);
        }
        if (!comfyModelExists("vae", videoVae)) {
            missing.add("models/vae/" + videoVae);
        }
        return missing;
    }

    private boolean comfyModelExists(String category, String model) throws IOException, InterruptedException {
        JsonNode models = getJson("/models/" + urlEncode(category));
        if (!models.isArray()) {
            return false;
        }
        for (JsonNode availableModel : models) {
            if (model.equals(availableModel.asText())) {
                return true;
            }
        }
        return false;
    }

    private List<LocalModelInfo> parseModels(JsonNode json, boolean flux2Only) {
        List<LocalModelInfo> models = new ArrayList<>();
        JsonNode modelList = json.isArray() ? json : json.path("models");
        if (!modelList.isArray()) {
            return models;
        }
        for (JsonNode item : modelList) {
            String name = item.isTextual() ? item.asText() : item.path("name").asText("");
            if (!name.isBlank() && (!flux2Only || isFlux2KleinModel(name))) {
                models.add(new LocalModelInfo(
                        COMFY_MODEL_PREFIX + name,
                        0L,
                        "",
                        "",
                        "comfyui",
                        name.equals(defaultModel),
                        flux2Only,
                        false,
                        false));
            }
        }
        return models;
    }

    private ObjectNode loadWorkflow() throws IOException {
        return loadWorkflow(workflowLocation);
    }

    private ObjectNode loadWorkflow(String workflowLocationValue) throws IOException {
        String location = workflowLocationValue.startsWith("classpath:")
                ? workflowLocationValue.substring("classpath:".length())
                : workflowLocationValue;
        ClassPathResource resource = new ClassPathResource(location);
        try (var inputStream = resource.getInputStream()) {
            JsonNode workflow = mapper.readTree(inputStream);
            if (!workflow.isObject()) {
                throw new IOException("O workflow do ComfyUI precisa ser um objeto JSON no formato API.");
            }
            return (ObjectNode) workflow;
        }
    }

    private void applyWorkflowInputs(
            ObjectNode workflow,
            String prompt,
            String negativePrompt,
            String model,
            int width,
            int height,
            ImageGenerationOptions options,
            long seed,
            boolean sdxl,
            ImageModelPreset preset) {
        int[] refinedDimensions = refinedDimensions(width, height, sdxl ? 1280 : 896);
        workflow.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            if (!node.isObject()) {
                return;
            }
            String classType = node.path("class_type").asText("");
            JsonNode inputsNode = node.path("inputs");
            if (!inputsNode.isObject()) {
                return;
            }
            ObjectNode inputs = (ObjectNode) inputsNode;
            switch (classType) {
                case "CheckpointLoaderSimple" -> inputs.put("ckpt_name", model);
                case "CLIPTextEncode" -> {
                    String text = inputs.path("text").asText("");
                    if ("__POSITIVE_PROMPT__".equals(text)) {
                        inputs.put("text", prompt);
                    } else if ("__NEGATIVE_PROMPT__".equals(text)) {
                        inputs.put("text", negativePrompt);
                    }
                }
                case "EmptyLatentImage" -> {
                    inputs.put("width", width);
                    inputs.put("height", height);
                    inputs.put("batch_size", 1);
                }
                case "LatentUpscale" -> {
                    inputs.put("width", refinedDimensions[0]);
                    inputs.put("height", refinedDimensions[1]);
                }
                case "VAELoader" -> inputs.put("vae_name", sdxl ? sdxlVae : imageVae);
                case "KSampler" -> {
                    boolean refinementSampler =
                            node.path("_meta").path("title").asText("").contains("Refinement");
                    inputs.put("seed", refinementSampler ? Math.floorMod(seed + 1, Long.MAX_VALUE) : seed);
                    inputs.put(
                            "steps",
                            refinementSampler ? imageRefinementSteps(options, preset) : imageSteps(options, preset));
                    inputs.put("cfg", imageCfg(options, preset));
                    if (preset.hasSampler()) {
                        inputs.put("sampler_name", preset.sampler());
                    }
                    if (preset.hasScheduler()) {
                        inputs.put("scheduler", preset.scheduler());
                    }
                    if (refinementSampler) {
                        inputs.put("denoise", options.refinementStrength());
                    }
                }
                default -> {
                    // Custom nodes remain untouched so users can extend the workflow.
                }
            }
        });
        if (!options.refinementEnabled()) {
            removeNodeByTitle(workflow, "Avento Latent Upscale");
            removeNodeByTitle(workflow, "Avento Refinement Sampler");
            findNodeByTitle(workflow, "Avento Output Decode")
                    .map(node -> (ObjectNode) node.path("inputs"))
                    .ifPresent(inputs -> inputs.set("samples", reference("5", 0)));
        }
    }

    private void applyFlux2WorkflowInputs(
            ObjectNode workflow,
            String prompt,
            String model,
            int[] dimensions,
            long seed,
            ImageGenerationOptions options,
            ImageModelPreset preset) {
        String naturalPrompt = prompt.replaceAll("\\(([^()]+):\\d+(?:\\.\\d+)?\\)", "$1");
        workflow.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            if (!node.isObject() || !node.path("inputs").isObject()) {
                return;
            }
            ObjectNode inputs = (ObjectNode) node.path("inputs");
            switch (node.path("class_type").asText("")) {
                case "UNETLoader" -> inputs.put("unet_name", model);
                case "CLIPLoader" -> {
                    inputs.put("clip_name", flux2TextEncoder);
                    inputs.put("type", "flux2");
                }
                case "VAELoader" -> inputs.put("vae_name", flux2Vae);
                case "CLIPTextEncode" -> inputs.put("text", naturalPrompt);
                case "EmptyFlux2LatentImage" -> {
                    inputs.put("width", dimensions[0]);
                    inputs.put("height", dimensions[1]);
                    inputs.put("batch_size", 1);
                }
                case "RandomNoise" -> inputs.put("noise_seed", seed);
                case "Flux2Scheduler" -> {
                    inputs.put("steps", imageSteps(options, preset));
                    inputs.put("width", dimensions[0]);
                    inputs.put("height", dimensions[1]);
                }
                case "CFGGuider" -> inputs.put("cfg", imageCfg(options, preset));
                case "KSamplerSelect" -> {
                    if (preset.hasSampler()) {
                        inputs.put("sampler_name", preset.sampler());
                    }
                }
                default -> {
                    // The remaining native nodes keep their versioned workflow defaults.
                }
            }
        });
    }

    private boolean isSdxlModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String normalized = stripPrefix(model).replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("realvisxl") || normalized.contains("sdxl") || normalized.contains("juggernautxl");
    }

    private int imageSteps(ImageGenerationOptions options, ImageModelPreset preset) {
        return preset.steps(options.qualityPreset());
    }

    private int imageRefinementSteps(ImageGenerationOptions options, ImageModelPreset preset) {
        return preset.refinementSteps(options.qualityPreset());
    }

    private double imageCfg(ImageGenerationOptions options, ImageModelPreset preset) {
        // An explicit manual CFG from the user always beats the model preset.
        if (options.cfgScale() != null) {
            return options.cfg();
        }
        return preset.cfg(options.qualityPreset());
    }

    private void appendUnsupportedFlux2Warnings(ImageGenerationOptions options, List<String> workflowWarnings) {
        if (options.hasReferenceImage()) {
            workflowWarnings.add("A imagem de referência ainda não é aplicada pelo workflow de texto do FLUX.2 Klein.");
        }
        if (options.hasPoseReference()) {
            workflowWarnings.add("A pose de referência ainda não é aplicada pelo workflow de texto do FLUX.2 Klein.");
        }
    }

    private boolean isFlux2KleinModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String normalized = stripPrefix(model).replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.equals("flux-2-klein-4b-fp8.safetensors")
                || normalized.endsWith("/flux-2-klein-4b-fp8.safetensors")
                || normalized.equals("flux-2-klein-4b.safetensors")
                || normalized.endsWith("/flux-2-klein-4b.safetensors");
    }

    private JsonNode loadOptionalImageNodeInfo(
            ImageGenerationOptions options, boolean humanSubject, List<String> workflowWarnings) {
        if (!options.hasReferenceImage()
                && !options.hasPoseReference()
                && (!humanSubject || "none".equals(options.detailMode()))) {
            return mapper.createObjectNode();
        }
        try {
            return getJson("/object_info");
        } catch (Exception exception) {
            workflowWarnings.add(
                    "Não foi possível consultar os nós opcionais do ComfyUI; referência, pose e detailer foram ignorados.");
            return mapper.createObjectNode();
        }
    }

    private ImageGenerationOptions applyMemoryBudget(ImageGenerationOptions options, List<String> workflowWarnings) {
        if (!memoryGuardEnabled) {
            return options;
        }
        try {
            return applyMemoryBudget(options, availableComfyMemoryBytes(), workflowWarnings);
        } catch (Exception exception) {
            return options;
        }
    }

    private ImageGenerationOptions applyMemoryBudget(
            ImageGenerationOptions options, long availableBytes, List<String> workflowWarnings) {
        if (availableBytes <= 0 || availableBytes >= LOW_MEMORY_PRESSURE_BYTES) {
            return options;
        }
        String availableLabel = String.format(Locale.ROOT, "%.1f GB", availableBytes / 1_073_741_824.0);
        if (availableBytes < SEVERE_MEMORY_PRESSURE_BYTES) {
            workflowWarnings.add(
                    "Memória livre baixa (" + availableLabel
                            + "): a pose foi preservada; qualidade, segundo passe e detailers foram reduzidos para evitar travamento.");
            return new ImageGenerationOptions(
                    "draft",
                    options.aspectRatio(),
                    options.seed(),
                    options.subjectCount(),
                    options.enhancePrompt(),
                    false,
                    options.refinementStrength(),
                    "none",
                    options.cfgScale(),
                    options.referenceImageDataUrl(),
                    options.referenceStrength(),
                    options.poseReferenceDataUrl(),
                    options.poseStrength(),
                    options.subjectType(),
                    options.referenceMode(),
                    options.structureControl(),
                    options.structureStrength(),
                    options.adherenceValidationEnabled(),
                    options.maxAdherenceRetries());
        }
        if (options.detailsHands()) {
            workflowWarnings.add("Memória livre limitada (" + availableLabel
                    + "): o detalhamento de mãos foi reduzido para somente rosto.");
            return new ImageGenerationOptions(
                    options.qualityPreset(),
                    options.aspectRatio(),
                    options.seed(),
                    options.subjectCount(),
                    options.enhancePrompt(),
                    options.refinementEnabled(),
                    options.refinementStrength(),
                    "face",
                    options.cfgScale(),
                    options.referenceImageDataUrl(),
                    options.referenceStrength(),
                    options.poseReferenceDataUrl(),
                    options.poseStrength(),
                    options.subjectType(),
                    options.referenceMode(),
                    options.structureControl(),
                    options.structureStrength(),
                    options.adherenceValidationEnabled(),
                    options.maxAdherenceRetries());
        }
        return options;
    }

    private boolean configureImageReference(
            ObjectNode workflow,
            ImageGenerationOptions options,
            JsonNode objectInfo,
            int[] dimensions,
            List<String> workflowWarnings) {
        if (!options.usesImg2ImgReference()) {
            return false;
        }
        if (!supportsNodes(objectInfo, "LoadImage", "ImageScale", "VAEEncode")) {
            workflowWarnings.add("Imagem de referência ignorada: nós img2img não estão disponíveis no ComfyUI.");
            return false;
        }

        String baseSamplerId = findNodeIdByTitle(workflow, "Avento Base Sampler");
        String vaeId = findNodeIdByTitle(workflow, "Avento VAE");
        if (baseSamplerId.isBlank() || vaeId.isBlank()) {
            workflowWarnings.add("Imagem de referência ignorada: o workflow não expõe os pontos de img2img do Avento.");
            return false;
        }

        try {
            String uploadedImage = uploadDataUrlReference(
                    options.referenceImageDataUrl(), "imagem de referência", "avento-reference-");
            ObjectNode loadImage = addNode(workflow, "avento_reference_image", "LoadImage", "Avento Image Reference");
            loadImage.with("inputs").put("image", uploadedImage);

            ObjectNode imageScale =
                    addNode(workflow, "avento_reference_scale", "ImageScale", "Avento Scale Image Reference");
            ObjectNode scaleInputs = imageScale.with("inputs");
            scaleInputs.set("image", reference("avento_reference_image", 0));
            scaleInputs.put("upscale_method", "lanczos");
            scaleInputs.put("width", dimensions[0]);
            scaleInputs.put("height", dimensions[1]);
            scaleInputs.put("crop", "center");

            ObjectNode vaeEncode =
                    addNode(workflow, "avento_reference_encode", "VAEEncode", "Avento Encode Image Reference");
            ObjectNode encodeInputs = vaeEncode.with("inputs");
            encodeInputs.set("pixels", reference("avento_reference_scale", 0));
            encodeInputs.set("vae", reference(vaeId, 0));

            ObjectNode samplerInputs = (ObjectNode) workflow.path(baseSamplerId).path("inputs");
            samplerInputs.set("latent_image", reference("avento_reference_encode", 0));
            samplerInputs.put("denoise", referenceDenoise(options.referenceStrength()));
            return true;
        } catch (Exception exception) {
            workflowWarnings.add("Imagem de referência ignorada: " + safeMessage(exception));
            removeNodesWithPrefix(workflow, "avento_reference_");
            return false;
        }
    }

    private boolean configureIdentityReference(
            ObjectNode workflow,
            ImageGenerationOptions options,
            JsonNode objectInfo,
            boolean humanSubject,
            List<String> workflowWarnings) {
        if (!options.usesIdentityReference()) {
            return false;
        }
        if (!supportsNodes(objectInfo, "LoadImage", "IPAdapterUnifiedLoader", "IPAdapter")) {
            workflowWarnings.add("Referência de identidade ignorada: o nó IP-Adapter não está carregado no ComfyUI.");
            return false;
        }

        String checkpointId = findNodeIdByTitle(workflow, "Avento Checkpoint");
        if (checkpointId.isBlank()) {
            workflowWarnings.add("Referência de identidade ignorada: checkpoint não encontrado no workflow.");
            return false;
        }

        try {
            String uploadedImage = uploadDataUrlReference(
                    options.referenceImageDataUrl(), "referência de identidade", "avento-identity-");
            ObjectNode loadImage = addNode(workflow, "avento_identity_image", "LoadImage", "Avento Identity Reference");
            loadImage.with("inputs").put("image", uploadedImage);

            ObjectNode loader =
                    addNode(workflow, "avento_ipadapter_loader", "IPAdapterUnifiedLoader", "Avento IP-Adapter Loader");
            ObjectNode loaderInputs = loader.with("inputs");
            loaderInputs.set("model", reference(checkpointId, 0));
            loaderInputs.put("preset", humanSubject ? "PLUS FACE (portraits)" : "PLUS (high strength)");

            ObjectNode apply = addNode(workflow, "avento_ipadapter_apply", "IPAdapter", "Avento Apply IP-Adapter");
            ObjectNode applyInputs = apply.with("inputs");
            applyInputs.set("model", reference("avento_ipadapter_loader", 0));
            applyInputs.set("ipadapter", reference("avento_ipadapter_loader", 1));
            applyInputs.set("image", reference("avento_identity_image", 0));
            applyInputs.put("weight", options.referenceStrength());
            applyInputs.put("start_at", 0.0);
            applyInputs.put("end_at", 0.9);
            applyInputs.put("weight_type", "prompt is more important");
            setSamplerModel(workflow, "avento_ipadapter_apply", 0);
            return true;
        } catch (Exception exception) {
            workflowWarnings.add("Referência de identidade ignorada: " + safeMessage(exception));
            removeNodesWithPrefix(workflow, "avento_identity_", "avento_ipadapter_");
            return false;
        }
    }

    private boolean configureStructureReference(
            ObjectNode workflow,
            ImageGenerationOptions options,
            JsonNode objectInfo,
            boolean sdxl,
            List<String> workflowWarnings) {
        if (!options.usesStructureReference()) {
            return false;
        }
        if (!sdxl) {
            workflowWarnings.add("Controle de composição Depth/Canny requer um checkpoint SDXL, como RealVisXL V5.");
            return false;
        }

        boolean depth = "depth".equals(options.structureControl());
        String preprocessor = depth ? "DepthAnythingV2Preprocessor" : "CannyEdgePreprocessor";
        String controlModel = depth ? sdxlDepthModel : sdxlCannyModel;
        if (!supportsNodes(objectInfo, "LoadImage", preprocessor, "ControlNetLoader", "ControlNetApplyAdvanced")) {
            workflowWarnings.add("Controle de composição ignorado: preprocessor " + preprocessor
                    + " ou ControlNet não está carregado no ComfyUI.");
            return false;
        }
        if (!nodeOptionAvailable(objectInfo, "ControlNetLoader", "control_net_name", controlModel)) {
            workflowWarnings.add("Controle de composição ignorado: modelo " + controlModel + " não está instalado.");
            return false;
        }

        try {
            String uploadedImage = uploadDataUrlReference(
                    options.referenceImageDataUrl(), "referência de composição", "avento-structure-");
            ObjectNode loadImage =
                    addNode(workflow, "avento_structure_image", "LoadImage", "Avento Structure Reference");
            loadImage.with("inputs").put("image", uploadedImage);

            ObjectNode preprocess =
                    addNode(workflow, "avento_structure_preprocess", preprocessor, "Avento Structure Preprocessor");
            ObjectNode preprocessInputs = preprocess.with("inputs");
            preprocessInputs.set("image", reference("avento_structure_image", 0));
            preprocessInputs.put("resolution", 1024);
            if (depth) {
                preprocessInputs.put("ckpt_name", "depth_anything_v2_vits.pth");
            } else {
                preprocessInputs.put("low_threshold", 80);
                preprocessInputs.put("high_threshold", 180);
            }

            ObjectNode loader =
                    addNode(workflow, "avento_structure_loader", "ControlNetLoader", "Avento Structure ControlNet");
            loader.with("inputs").put("control_net_name", controlModel);

            ConditioningReferences conditioning = currentConditioning(workflow);
            ObjectNode apply =
                    addNode(workflow, "avento_structure_apply", "ControlNetApplyAdvanced", "Avento Apply Structure");
            ObjectNode applyInputs = apply.with("inputs");
            applyInputs.set("positive", conditioning.positive());
            applyInputs.set("negative", conditioning.negative());
            applyInputs.set("control_net", reference("avento_structure_loader", 0));
            applyInputs.set("image", reference("avento_structure_preprocess", 0));
            applyInputs.put("strength", options.structureStrength());
            applyInputs.put("start_percent", 0.0);
            applyInputs.put("end_percent", 0.85);
            setSamplerConditioning(workflow, "avento_structure_apply", 0, 1);
            return true;
        } catch (Exception exception) {
            workflowWarnings.add("Controle de composição ignorado: " + safeMessage(exception));
            removeNodesWithPrefix(workflow, "avento_structure_");
            return false;
        }
    }

    private double referenceDenoise(double referenceStrength) {
        return Math.max(0.15, Math.min(0.85, 1.0 - referenceStrength));
    }

    private long availableComfyMemoryBytes() throws IOException, InterruptedException {
        JsonNode stats = getJson("/system_stats");
        long available = stats.path("system").path("ram_free").asLong(0L);
        JsonNode devices = stats.path("devices");
        if (devices.isArray()) {
            for (JsonNode device : devices) {
                long deviceFree = device.path("vram_free").asLong(0L);
                if (deviceFree > 0) {
                    available = available <= 0 ? deviceFree : Math.min(available, deviceFree);
                }
            }
        }
        return available;
    }

    private boolean configurePoseReference(
            ObjectNode workflow,
            ImageGenerationOptions options,
            JsonNode objectInfo,
            boolean sdxl,
            List<String> workflowWarnings) {
        if (!options.hasPoseReference()) {
            return false;
        }
        if (!supportsNodes(objectInfo, "LoadImage", "DWPreprocessor", "ControlNetLoader", "ControlNetApplyAdvanced")) {
            workflowWarnings.add("Referência de pose ignorada: DWPose ou ControlNet não está carregado no ComfyUI.");
            return false;
        }
        String poseModel = sdxl ? sdxlOpenPoseModel : openPoseModel;
        if (!nodeOptionAvailable(objectInfo, "ControlNetLoader", "control_net_name", poseModel)) {
            workflowWarnings.add("Referência de pose ignorada: modelo OpenPose não está instalado.");
            return false;
        }

        try {
            String uploadedImage =
                    uploadDataUrlReference(options.poseReferenceDataUrl(), "referência de pose", "avento-pose-");
            ObjectNode loadImage = addNode(workflow, "avento_pose_image", "LoadImage", "Avento Pose Reference");
            loadImage.with("inputs").put("image", uploadedImage);

            ObjectNode dwPose = addNode(workflow, "avento_dwpose", "DWPreprocessor", "Avento DWPose");
            ObjectNode dwInputs = dwPose.with("inputs");
            dwInputs.set("image", reference("avento_pose_image", 0));
            dwInputs.put("detect_hand", "enable");
            dwInputs.put("detect_body", "enable");
            dwInputs.put("detect_face", "enable");
            dwInputs.put("resolution", sdxl ? 1024 : 512);
            dwInputs.put("bbox_detector", "yolox_l.onnx");
            dwInputs.put("pose_estimator", "dw-ll_ucoco_384.onnx");
            dwInputs.put("scale_stick_for_xinsr_cn", "disable");

            ObjectNode loader = addNode(workflow, "avento_openpose_loader", "ControlNetLoader", "Avento OpenPose");
            loader.with("inputs").put("control_net_name", poseModel);

            ConditioningReferences conditioning = currentConditioning(workflow);
            ObjectNode apply =
                    addNode(workflow, "avento_openpose_apply", "ControlNetApplyAdvanced", "Avento Apply OpenPose");
            ObjectNode applyInputs = apply.with("inputs");
            applyInputs.set("positive", conditioning.positive());
            applyInputs.set("negative", conditioning.negative());
            applyInputs.set("control_net", reference("avento_openpose_loader", 0));
            applyInputs.set("image", reference("avento_dwpose", 0));
            applyInputs.put("strength", options.poseStrength());
            applyInputs.put("start_percent", 0.0);
            applyInputs.put("end_percent", 0.9);

            setSamplerConditioning(workflow, "avento_openpose_apply", 0, 1);
            return true;
        } catch (Exception exception) {
            workflowWarnings.add("Referência de pose ignorada: " + safeMessage(exception));
            removeNodesWithPrefix(workflow, "avento_pose_", "avento_dwpose", "avento_openpose_");
            return false;
        }
    }

    private String configureDetailing(
            ObjectNode workflow,
            ImageGenerationOptions options,
            JsonNode objectInfo,
            long seed,
            boolean humanSubject,
            int subjectCount,
            List<String> workflowWarnings) {
        if (!humanSubject) {
            if (!"none".equals(options.detailMode())) {
                workflowWarnings.add("Detailer de rosto desativado: o prompt não pede uma pessoa.");
            }
            return "none";
        }
        if ("none".equals(options.detailMode())) {
            return "none";
        }
        if (!supportsNodes(
                objectInfo,
                "BboxDetectorSEGS",
                "ImpactSEGSOrderedFilter",
                "DetailerForEach",
                "UltralyticsDetectorProvider")) {
            workflowWarnings.add("Detailer filtrado ignorado: Impact Pack não está carregado no ComfyUI.");
            return "none";
        }

        String checkpointId = findNodeIdByTitle(workflow, "Avento Checkpoint");
        String positiveId = workflow.has("avento_openpose_apply")
                ? "avento_openpose_apply"
                : workflow.has("avento_structure_apply")
                        ? "avento_structure_apply"
                        : findNodeIdByTitle(workflow, "Avento Positive Prompt");
        int negativeOutput = positiveId.startsWith("avento_") ? 1 : 0;
        String negativeId =
                positiveId.startsWith("avento_") ? positiveId : findNodeIdByTitle(workflow, "Avento Negative Prompt");
        String baseSamplerId = findNodeIdByTitle(workflow, "Avento Base Sampler");
        JsonNode samplingModel =
                workflow.path(baseSamplerId).path("inputs").path("model").deepCopy();
        String vaeId = findNodeIdByTitle(workflow, "Avento VAE");
        String sourceId = findNodeIdByTitle(workflow, "Avento Output Decode");
        String saveId = findNodeIdByTitle(workflow, "Avento Final Output");
        if (checkpointId.isBlank()
                || positiveId.isBlank()
                || negativeId.isBlank()
                || samplingModel.isMissingNode()
                || vaeId.isBlank()
                || sourceId.isBlank()
                || saveId.isBlank()) {
            workflowWarnings.add(
                    "Detailer ignorado: o workflow personalizado não possui os pontos de integração do Avento.");
            return "none";
        }

        int expectedSubjects = Math.max(1, subjectCount);
        String appliedMode = "none";
        if (options.detailsFace()
                && nodeOptionAvailable(
                        objectInfo, "UltralyticsDetectorProvider", "model_name", "bbox/face_yolov8m.pt")) {
            sourceId = addDetailer(
                    workflow,
                    "face",
                    "bbox/face_yolov8m.pt",
                    sourceId,
                    samplingModel,
                    checkpointId,
                    positiveId,
                    negativeId,
                    negativeOutput,
                    vaeId,
                    seed + 2,
                    options,
                    384,
                    0.68,
                    2.2,
                    expectedSubjects);
            appliedMode = "face";
        } else if (options.detailsFace()) {
            workflowWarnings.add("Detalhamento de rosto ignorado: detector face_yolov8m.pt não está instalado.");
        }

        if (options.detailsHands()
                && nodeOptionAvailable(
                        objectInfo, "UltralyticsDetectorProvider", "model_name", "bbox/hand_yolov8s.pt")) {
            sourceId = addDetailer(
                    workflow,
                    "hands",
                    "bbox/hand_yolov8s.pt",
                    sourceId,
                    samplingModel,
                    checkpointId,
                    positiveId,
                    negativeId,
                    negativeOutput,
                    vaeId,
                    seed + 3,
                    options,
                    256,
                    0.60,
                    1.8,
                    expectedSubjects * 2);
            appliedMode = "face-hands";
        } else if (options.detailsHands()) {
            workflowWarnings.add("Detalhamento de mãos ignorado: detector hand_yolov8s.pt não está instalado.");
        }

        ((ObjectNode) workflow.path(saveId).path("inputs")).set("images", reference(sourceId, 0));
        return appliedMode;
    }

    private String addDetailer(
            ObjectNode workflow,
            String suffix,
            String detectorModel,
            String sourceId,
            JsonNode samplingModel,
            String checkpointId,
            String positiveId,
            String negativeId,
            int negativeOutput,
            String vaeId,
            long seed,
            ImageGenerationOptions options,
            int guideSize,
            double threshold,
            double cropFactor,
            int maxDetections) {
        String detectorId = "avento_" + suffix + "_detector";
        ObjectNode detector =
                addNode(workflow, detectorId, "UltralyticsDetectorProvider", "Avento " + suffix + " detector");
        detector.with("inputs").put("model_name", detectorModel);

        String segmentsId = "avento_" + suffix + "_segments";
        ObjectNode segments = addNode(workflow, segmentsId, "BboxDetectorSEGS", "Avento " + suffix + " segments");
        ObjectNode segmentInputs = segments.with("inputs");
        segmentInputs.set("bbox_detector", reference(detectorId, 0));
        segmentInputs.set("image", reference(sourceId, 0));
        segmentInputs.put("threshold", threshold);
        segmentInputs.put("dilation", "hands".equals(suffix) ? 6 : 4);
        segmentInputs.put("crop_factor", cropFactor);
        segmentInputs.put("drop_size", "hands".equals(suffix) ? 20 : 24);
        segmentInputs.put("labels", "all");

        String filterId = "avento_" + suffix + "_filter";
        ObjectNode filter =
                addNode(workflow, filterId, "ImpactSEGSOrderedFilter", "Avento " + suffix + " confidence filter");
        ObjectNode filterInputs = filter.with("inputs");
        filterInputs.set("segs", reference(segmentsId, 0));
        filterInputs.put("target", "confidence");
        filterInputs.put("order", true);
        filterInputs.put("take_start", 0);
        filterInputs.put("take_count", Math.max(1, maxDetections));

        String detailerId = "avento_" + suffix + "_detailer";
        ObjectNode detailer = addNode(workflow, detailerId, "DetailerForEach", "Avento " + suffix + " detailer");
        ObjectNode inputs = detailer.with("inputs");
        inputs.set("image", reference(sourceId, 0));
        inputs.set("segs", reference(filterId, 0));
        inputs.set("model", samplingModel.deepCopy());
        inputs.set("clip", reference(checkpointId, 1));
        inputs.set("vae", reference(vaeId, 0));
        inputs.put("guide_size", guideSize);
        inputs.put("guide_size_for", true);
        inputs.put("max_size", "hands".equals(suffix) ? 512 : 768);
        inputs.put("seed", Math.floorMod(seed, Long.MAX_VALUE));
        inputs.put("steps", options.detailerSteps());
        inputs.put("cfg", options.cfg());
        inputs.put("sampler_name", "dpmpp_2m");
        inputs.put("scheduler", "karras");
        inputs.set("positive", reference(positiveId, 0));
        inputs.set("negative", reference(negativeId, negativeOutput));
        inputs.put("denoise", Math.min(0.32, options.refinementStrength()));
        inputs.put("feather", 8);
        inputs.put("noise_mask", true);
        inputs.put("force_inpaint", true);
        inputs.put("wildcard", "");
        inputs.put("cycle", 1);
        return detailerId;
    }

    private int[] refinedDimensions(int width, int height, int maxRefinedDimension) {
        double scale = Math.min(1.5, (double) maxRefinedDimension / Math.max(width, height));
        int refinedWidth = Math.max(width, ((int) Math.floor(width * scale) / 8) * 8);
        int refinedHeight = Math.max(height, ((int) Math.floor(height * scale) / 8) * 8);
        return new int[] {refinedWidth, refinedHeight};
    }

    private ConditioningReferences currentConditioning(ObjectNode workflow) throws IOException {
        String samplerId = findNodeIdByTitle(workflow, "Avento Base Sampler");
        JsonNode inputs = workflow.path(samplerId).path("inputs");
        JsonNode positive = inputs.path("positive");
        JsonNode negative = inputs.path("negative");
        if (!positive.isArray() || !negative.isArray()) {
            throw new IOException("O workflow não expõe os condicionamentos do sampler base.");
        }
        return new ConditioningReferences(positive.deepCopy(), negative.deepCopy());
    }

    private void setSamplerConditioning(ObjectNode workflow, String nodeId, int positiveOutput, int negativeOutput) {
        workflow.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            if ("KSampler".equals(node.path("class_type").asText(""))) {
                ObjectNode inputs = (ObjectNode) node.path("inputs");
                inputs.set("positive", reference(nodeId, positiveOutput));
                inputs.set("negative", reference(nodeId, negativeOutput));
            }
        });
    }

    private void setSamplerModel(ObjectNode workflow, String nodeId, int output) {
        workflow.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            if ("KSampler".equals(node.path("class_type").asText(""))) {
                ((ObjectNode) node.path("inputs")).set("model", reference(nodeId, output));
            }
        });
    }

    private ObjectNode addNode(ObjectNode workflow, String id, String classType, String title) {
        ObjectNode node = workflow.putObject(id);
        node.put("class_type", classType);
        node.putObject("_meta").put("title", title);
        node.putObject("inputs");
        return node;
    }

    private ArrayNode reference(String nodeId, int outputIndex) {
        return mapper.createArrayNode().add(nodeId).add(outputIndex);
    }

    private Optional<ObjectNode> findNodeByTitle(ObjectNode workflow, String title) {
        var fields = workflow.fields();
        while (fields.hasNext()) {
            JsonNode node = fields.next().getValue();
            if (node.isObject() && title.equals(node.path("_meta").path("title").asText(""))) {
                return Optional.of((ObjectNode) node);
            }
        }
        return Optional.empty();
    }

    private String findNodeIdByTitle(ObjectNode workflow, String title) {
        var fields = workflow.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (title.equals(entry.getValue().path("_meta").path("title").asText(""))) {
                return entry.getKey();
            }
        }
        return "";
    }

    private void removeNodeByTitle(ObjectNode workflow, String title) {
        String nodeId = findNodeIdByTitle(workflow, title);
        if (!nodeId.isBlank()) {
            workflow.remove(nodeId);
        }
    }

    private void removeNodesWithPrefix(ObjectNode workflow, String... prefixes) {
        List<String> nodeIds = new ArrayList<>();
        workflow.fieldNames().forEachRemaining(nodeId -> {
            for (String prefix : prefixes) {
                if (nodeId.startsWith(prefix)) {
                    nodeIds.add(nodeId);
                    break;
                }
            }
        });
        workflow.remove(nodeIds);
    }

    private boolean supportsNodes(JsonNode objectInfo, String... nodeNames) {
        if (!objectInfo.isObject()) {
            return false;
        }
        for (String nodeName : nodeNames) {
            if (!objectInfo.has(nodeName)) {
                return false;
            }
        }
        return true;
    }

    private boolean nodeOptionAvailable(JsonNode objectInfo, String nodeName, String inputName, String expected) {
        JsonNode options = objectInfo
                .path(nodeName)
                .path("input")
                .path("required")
                .path(inputName)
                .path(0);
        return containsText(options, expected);
    }

    private boolean containsText(JsonNode node, String expected) {
        if (node.isTextual()) {
            return expected.equals(node.asText());
        }
        if (node.isContainerNode()) {
            var elements = node.elements();
            while (elements.hasNext()) {
                if (containsText(elements.next(), expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String uploadDataUrlReference(String dataUrl, String description, String filenamePrefix)
            throws IOException, InterruptedException {
        int separator = dataUrl.indexOf(',');
        if (separator <= 5) {
            throw new IOException("data URL da " + description + " é inválida");
        }
        String metadata = dataUrl.substring(5, separator).toLowerCase(Locale.ROOT);
        String mimeType = metadata.substring(0, metadata.indexOf(';'));
        String extension =
                switch (mimeType) {
                    case "image/png" -> "png";
                    case "image/webp" -> "webp";
                    default -> "jpg";
                };
        byte[] imageBytes;
        try {
            imageBytes = Base64.getMimeDecoder().decode(dataUrl.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            throw new IOException("base64 da " + description + " é inválido", exception);
        }
        if (imageBytes.length == 0 || imageBytes.length > MAX_POSE_REFERENCE_BYTES) {
            throw new IOException("a " + description + " precisa ter no máximo 6 MB");
        }

        return uploadImageBytes(imageBytes, mimeType, extension, filenamePrefix);
    }

    private String uploadImage(Path imagePath, String filenamePrefix) throws IOException, InterruptedException {
        byte[] imageBytes = Files.readAllBytes(imagePath);
        if (imageBytes.length == 0 || imageBytes.length > MAX_POSE_REFERENCE_BYTES) {
            throw new IOException("a imagem de referência precisa ter no máximo 6 MB");
        }
        String originalName = imagePath.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = originalName.endsWith(".webp") ? "webp" : originalName.endsWith(".png") ? "png" : "jpg";
        String mimeType =
                switch (extension) {
                    case "png" -> "image/png";
                    case "webp" -> "image/webp";
                    default -> "image/jpeg";
                };
        return uploadImageBytes(imageBytes, mimeType, extension, filenamePrefix);
    }

    private String uploadImageBytes(byte[] imageBytes, String mimeType, String extension, String filenamePrefix)
            throws IOException, InterruptedException {
        String filename = filenamePrefix + UUID.randomUUID() + "." + extension;
        String boundary = "----AventoBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] header = ("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: " + mimeType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + "/upload/image"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(header, imageBytes, footer)))
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("upload da imagem retornou HTTP " + response.statusCode());
        }
        String uploadedName = mapper.readTree(response.body()).path("name").asText("");
        if (uploadedName.isBlank()) {
            throw new IOException("ComfyUI não confirmou o nome da imagem de referência");
        }
        return uploadedName;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private ImageReference waitForImage(String promptId) throws IOException, InterruptedException {
        int timeoutMinutes = Math.max(2, Math.min(60, imageTimeoutMinutes));
        return waitForOutput(promptId, Duration.ofMinutes(timeoutMinutes));
    }

    private ImageReference waitForOutput(String promptId, Duration timeout) throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        long nextProgressLogSeconds = 10L;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode history = getJson("/history/" + urlEncode(promptId));
            JsonNode promptHistory = history.path(promptId);
            JsonNode outputs = promptHistory.path("outputs");
            ImageReference image = findImage(outputs);
            if (image != null) {
                return image;
            }
            if (promptHistory.path("status").path("status_str").asText("").equalsIgnoreCase("error")) {
                throw new IOException(extractExecutionError(promptHistory));
            }
            long elapsedSeconds = elapsedSeconds(startedNanos);
            if (elapsedSeconds >= nextProgressLogSeconds) {
                logger.info("ComfyUI prompt {} still running after {}s", promptId, elapsedSeconds);
                nextProgressLogSeconds += 10L;
            }
            Thread.sleep(250L);
        }
        throw new IOException("O ComfyUI ultrapassou o tempo limite de " + timeout.toMinutes() + " minutos (prompt "
                + promptId + "). A geração pode continuar na fila do ComfyUI.");
    }

    private long elapsedSeconds(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)).toSeconds();
    }

    private String extractExecutionError(JsonNode promptHistory) {
        JsonNode messages = promptHistory.path("status").path("messages");
        if (messages.isArray()) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                JsonNode entry = messages.get(index);
                if (!entry.isArray()
                        || entry.size() < 2
                        || !"execution_error".equals(entry.get(0).asText())) {
                    continue;
                }
                JsonNode error = entry.get(1);
                String nodeType = error.path("node_type").asText("");
                String exceptionType = error.path("exception_type").asText("");
                String exceptionMessage =
                        error.path("exception_message").asText("").trim();
                StringBuilder details = new StringBuilder("ComfyUI falhou");
                if (!nodeType.isBlank()) {
                    details.append(" no nó ").append(nodeType);
                }
                if (!exceptionType.isBlank()) {
                    details.append(" (").append(exceptionType).append(')');
                }
                if (!exceptionMessage.isBlank()) {
                    details.append(": ").append(exceptionMessage);
                }
                return details.toString();
            }
        }
        return "O ComfyUI informou erro na execução, mas não retornou detalhes.";
    }

    private ImageReference findImage(JsonNode outputs) {
        if (!outputs.isObject()) {
            return null;
        }
        var fields = outputs.fields();
        while (fields.hasNext()) {
            JsonNode output = fields.next().getValue();
            for (String mediaKey : List.of("images", "gifs", "videos")) {
                JsonNode media = output.path(mediaKey);
                if (media.isArray() && !media.isEmpty()) {
                    JsonNode item = media.get(0);
                    return new ImageReference(
                            item.path("filename").asText(""),
                            item.path("subfolder").asText(""),
                            item.path("type").asText("output"));
                }
            }
        }
        return null;
    }

    private byte[] getImage(ImageReference image) throws IOException, InterruptedException {
        String query = "filename=" + urlEncode(image.filename())
                + "&subfolder=" + urlEncode(image.subfolder())
                + "&type=" + urlEncode(image.type());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + "/view?" + query))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ComfyUI /view retornou HTTP " + response.statusCode());
        }
        return response.body();
    }

    private JsonNode postJson(String path, JsonNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ComfyUI retornou HTTP " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    private void postWithoutResponse(String path, JsonNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ComfyUI retornou HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ComfyUI retornou HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private int[] parseSize(String size) {
        String[] parts =
                size == null ? new String[0] : size.toLowerCase(Locale.ROOT).split("x");
        int width = parts.length == 2 ? parseDimension(parts[0]) : 768;
        int height = parts.length == 2 ? parseDimension(parts[1]) : 768;
        return new int[] {width, height};
    }

    private int parseDimension(String value) {
        return parseDimension(value, 768);
    }

    private int parseDimension(String value, int fallback) {
        try {
            int dimension = Integer.parseInt(value.trim());
            int bounded = Math.max(MIN_DIMENSION, Math.min(MAX_DIMENSION, dimension));
            return bounded - bounded % 8;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private ObjectNode failed(ObjectNode result, String error, String details) {
        result.put("status", "failed");
        result.put("provider", "comfyui");
        result.put("error", error);
        if (details != null && !details.isBlank()) {
            result.put("details", details);
        }
        result.put("hint", "Verifique o ComfyUI em " + baseUrl + " e selecione um modelo visual disponível no header.");
        return result;
    }

    private String stripPrefix(String model) {
        if (model == null || model.isBlank()) {
            return "";
        }
        return model.startsWith(COMFY_MODEL_PREFIX)
                ? model.substring(COMFY_MODEL_PREFIX.length()).trim()
                : model.trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
