package com.avento.api.dto;

/**
 * Atualizacao da configuracao de provedor. Campo nulo significa "nao mexi".
 *
 * <p>Os campos {@code system*}/{@code personalCloud*} seguem aceitos para compatibilidade.
 */
public record ProviderSettingsUpdateRequest(
        String providerKind,
        String baseUrl,
        String apiKey,
        String selectedModel,
        String visionModel,
        String imageModel,
        String plannerModel,
        String embeddingModel,
        String systemServerUrl,
        String systemServerType,
        String systemDefaultModel,
        Boolean usePersonalCloud,
        String personalCloudProvider,
        String personalCloudApiKey,
        String personalCloudModel) {}
