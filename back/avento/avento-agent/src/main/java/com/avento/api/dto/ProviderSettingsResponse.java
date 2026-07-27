package com.avento.api.dto;

/**
 * Configuracao de provedor devolvida para a tela.
 *
 * <p>{@code providerKind} e o campo que dirige o comportamento; os demais o acompanham. Os nomes
 * {@code system*} e {@code personalCloud*} continuam por compatibilidade com a tela atual, mas
 * descrevem a mesma configuracao unica, nao dois provedores paralelos.
 */
public record ProviderSettingsResponse(
        String providerKind,
        String baseUrl,
        String selectedModel,
        String apiKeyMasked,
        String systemServerUrl,
        String systemServerType,
        String systemDefaultModel,
        boolean usePersonalCloud,
        String personalCloudProvider,
        String personalCloudApiKeyMasked,
        String personalCloudModel) {}
