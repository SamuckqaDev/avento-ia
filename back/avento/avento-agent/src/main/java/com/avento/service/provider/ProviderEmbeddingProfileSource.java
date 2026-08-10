package com.avento.service.provider;

import com.avento.model.ProviderSettings;
import com.avento.repository.ProviderSettingsRepository;
import com.avento.service.rag.EmbeddingProfile;
import com.avento.service.rag.EmbeddingProfileSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the embedding model chosen in the provider settings and hands it to the RAG module.
 *
 * <p>Deliberately not per user. The vector index is one shared structure keyed by the model that
 * built it, not by who asked, and Avento runs as a local single-user product. If more than one
 * account ever has settings, this returns empty rather than picking one: letting whichever row is
 * read first decide would silently rebuild the index under the other account's feet, which is a far
 * worse failure than falling back to the model configured in the YAML.
 *
 * <p>Models are cached by address and name — each instance holds an HTTP client, and the search path
 * asks for one on every tool call.
 */
@Component
public class ProviderEmbeddingProfileSource implements EmbeddingProfileSource {

    private final ProviderSettingsRepository repository;
    private final String defaultOllamaUrl;
    private final Map<String, EmbeddingModel> cache = new ConcurrentHashMap<>();

    public ProviderEmbeddingProfileSource(
            ObjectProvider<ProviderSettingsRepository> repositoryProvider, ModelProviderService modelProviderService) {
        this.repository = repositoryProvider.getIfAvailable();
        this.defaultOllamaUrl = modelProviderService.defaultOllamaBaseUrl();
    }

    @Override
    public Optional<EmbeddingProfile> active() {
        if (repository == null) {
            return Optional.empty();
        }
        List<ProviderSettings> all = repository.findAll();
        if (all.size() != 1) {
            return Optional.empty();
        }
        ProviderSettings settings = all.get(0);
        String model = settings.getEmbeddingModel();
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        String baseUrl = settings.getBaseUrl() == null || settings.getBaseUrl().isBlank()
                ? defaultOllamaUrl
                : settings.getBaseUrl();
        return Optional.of(new EmbeddingProfile(model, embeddingModel(baseUrl, model)));
    }

    private EmbeddingModel embeddingModel(String baseUrl, String modelName) {
        return cache.computeIfAbsent(
                baseUrl + "|" + modelName,
                ignored -> OllamaEmbeddingModel.builder()
                        .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
                        .options(OllamaEmbeddingOptions.builder()
                                .model(modelName)
                                .build())
                        .build());
    }
}
