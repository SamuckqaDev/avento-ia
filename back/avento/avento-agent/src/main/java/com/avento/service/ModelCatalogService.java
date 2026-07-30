package com.avento.service;

import com.avento.service.dto.LocalModelInfo;
import com.avento.service.provider.ModelProviderService;
import com.avento.service.support.ModelNames;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Os modelos oferecidos aos seletores da interface.
 *
 * <p>A regra que dita tudo aqui: a lista tem de vir de quem vai atender a conversa. Mostrar os
 * modelos locais enquanto o chat vai para o Gemini faz o usuário escolher um nome que não tem
 * efeito nenhum e concluir, com razão, que a escolha de provedor foi ignorada. Por isso cada
 * listagem tenta primeiro o provedor remoto e só cai no Ollama quando o fluxo é local.
 *
 * <p>Vivia dentro do AgentService, que não tem nada a ver com montar lista para tela.
 */
@Service
public class ModelCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(ModelCatalogService.class);

    private final WebClient webClient;
    private final String defaultChatModel;
    private final String defaultVisionModel;
    private final String defaultImageModel;

    // Opcional pelo mesmo motivo do AgentService: os testes constroem pelo construtor, sem Spring.
    @Autowired(required = false)
    private ModelProviderService modelProviderService;

    public ModelCatalogService(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${avento.agent.default-model:granite4.1:8b}") String defaultChatModel,
            @Value("${avento.agent.vision-model:qwen2.5vl:7b}") String defaultVisionModel,
            @Value("${avento.image.default-model:comfyui:RealVisXL_V5.0_fp16.safetensors}") String defaultImageModel) {
        this.webClient = WebClient.builder().baseUrl(ollamaBaseUrl).build();
        this.defaultChatModel = defaultChatModel;
        this.defaultVisionModel = defaultVisionModel;
        this.defaultImageModel = defaultImageModel;
    }

    public void setModelProviderService(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    public Mono<List<String>> getModels() {
        return getModelDetails(null)
                .map(models -> models.stream().map(LocalModelInfo::name).toList());
    }

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
                .onErrorResume(error -> openAiCompatibleModels(ModelNames::isChatModel, false))
                .map(this::sortModels);
    }

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
                .onErrorResume(error -> openAiCompatibleModels(ModelNames::isImageModel, true))
                .map(this::sortModels);
    }

    /** Se um provedor remoto está ativo e pronto. Usado por quem monta listas por provedor. */
    public boolean usesRemoteProvider(UUID userId) {
        return modelProviderService != null && modelProviderService.remoteProviderReady(userId);
    }

    /**
     * Os dois caminhos OpenAI-compatible só diferem no filtro e em quais flags fazem sentido — um
     * modelo de imagem nunca é "vision" nem entra na recomendação de chat.
     */
    private Mono<List<LocalModelInfo>> openAiCompatibleModels(
            java.util.function.Predicate<String> accepts, boolean imageCatalog) {
        return webClient
                .get()
                .uri("/v1/models")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    List<LocalModelInfo> models = new ArrayList<>();
                    if (!json.path("data").isArray()) {
                        return models;
                    }
                    for (JsonNode model : json.path("data")) {
                        String name = model.path("id").asText("");
                        if (name.isBlank() || !accepts.test(name)) {
                            continue;
                        }
                        String parameterSize = ModelNames.inferParameterSize(name);
                        String family = ModelNames.inferFamily(name);
                        models.add(new LocalModelInfo(
                                name,
                                0L,
                                "",
                                parameterSize,
                                family,
                                imageCatalog
                                        ? name.equals(defaultImageModel)
                                        : ModelNames.isRecommendedModel(name, defaultChatModel),
                                ModelNames.isHeavyModel(name, 0L, parameterSize),
                                !imageCatalog && ModelNames.isVisionModel(name, family),
                                !imageCatalog && ModelNames.isPreferredVisionModel(name, defaultVisionModel)));
                    }
                    return models;
                })
                .onErrorReturn(new ArrayList<>());
    }

    // Package-private para o teste chamar direto, em vez de alcancar por reflexao.
    List<LocalModelInfo> parseOllamaTags(JsonNode json) {
        List<LocalModelInfo> models = new ArrayList<>();
        if (!json.path("models").isArray()) {
            return models;
        }

        for (JsonNode model : json.path("models")) {
            String name = modelName(model);
            if (name.isBlank() || !ModelNames.isChatModel(name)) {
                continue;
            }

            long sizeBytes = model.path("size").asLong(0L);
            String parameterSize = parameterSize(model, name);
            String family = family(model, name);

            models.add(new LocalModelInfo(
                    name,
                    sizeBytes,
                    formatSize(sizeBytes),
                    parameterSize,
                    family,
                    ModelNames.isRecommendedModel(name, defaultChatModel),
                    ModelNames.isHeavyModel(name, sizeBytes, parameterSize),
                    ModelNames.isVisionModel(name, family),
                    ModelNames.isPreferredVisionModel(name, defaultVisionModel)));
        }
        return models;
    }

    // Package-private para o teste chamar direto, em vez de alcancar por reflexao.
    List<LocalModelInfo> parseOllamaImageTags(JsonNode json) {
        List<LocalModelInfo> models = new ArrayList<>();
        if (!json.path("models").isArray()) {
            return models;
        }
        for (JsonNode model : json.path("models")) {
            String name = modelName(model);
            if (name.isBlank() || !ModelNames.isImageModel(name)) {
                continue;
            }
            long sizeBytes = model.path("size").asLong(0L);
            String parameterSize = parameterSize(model, name);
            models.add(new LocalModelInfo(
                    name,
                    sizeBytes,
                    formatSize(sizeBytes),
                    parameterSize,
                    family(model, name),
                    name.equals(defaultImageModel),
                    ModelNames.isHeavyModel(name, sizeBytes, parameterSize),
                    false,
                    false));
        }
        return models;
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

    /** Modelos do provedor de nuvem ativo, ou lista vazia quando o fluxo é local. */
    // Package-private para o teste chamar direto, em vez de alcancar por reflexao.
    List<LocalModelInfo> cloudModelsFor(UUID userId) {
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

    /** Recomendado primeiro, pesado por último, resto alfabético. */
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

    private static String modelName(JsonNode model) {
        return model.path("name").asText(model.path("model").asText(""));
    }

    private static String parameterSize(JsonNode model, String name) {
        return ModelNames.firstNonBlank(
                model.path("details").path("parameter_size").asText(""), ModelNames.inferParameterSize(name));
    }

    private static String family(JsonNode model, String name) {
        return ModelNames.firstNonBlank(model.path("details").path("family").asText(""), ModelNames.inferFamily(name));
    }

    private static String formatSize(long sizeBytes) {
        if (sizeBytes <= 0L) {
            return "";
        }
        double gib = sizeBytes / 1024.0 / 1024.0 / 1024.0;
        return String.format(Locale.ROOT, "%.1f GB", gib);
    }
}
