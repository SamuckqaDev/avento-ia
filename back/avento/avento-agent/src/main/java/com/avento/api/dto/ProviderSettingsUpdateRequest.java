package com.avento.api.dto;

/**
 * Atualizacao da configuracao de provedor.
 *
 * <p>{@code providerKind}, {@code baseUrl}, {@code apiKey} e {@code selectedModel} sao o caminho
 * novo. Os campos {@code system*}/{@code personalCloud*} seguem aceitos para a tela atual continuar
 * funcionando enquanto migra. Campo nulo significa "nao mexi".
 */
public record ProviderSettingsUpdateRequest(
        String providerKind,
        String baseUrl,
        String apiKey,
        String selectedModel,
        String systemServerUrl,
        String systemServerType,
        String systemDefaultModel,
        Boolean usePersonalCloud,
        String personalCloudProvider,
        String personalCloudApiKey,
        String personalCloudModel) {}
