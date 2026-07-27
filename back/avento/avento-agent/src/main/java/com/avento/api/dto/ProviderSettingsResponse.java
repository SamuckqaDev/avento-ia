package com.avento.api.dto;

/**
 * Configuracao de provedor devolvida para a tela.
 *
 * <p>Toda escolha de modelo vive aqui, nao em arquivo de configuracao: quem usa o produto nao
 * deveria editar YAML para trocar de modelo de visao ou de imagem.
 *
 * <p>Os campos {@code system*}/{@code personalCloud*} sao herdados e descrevem a MESMA configuracao.
 */
public record ProviderSettingsResponse(
        String providerKind,
        String baseUrl,
        String selectedModel,
        String apiKeyMasked,
        String visionModel,
        String imageModel,
        String plannerModel,
        String embeddingModel,
        String systemServerUrl,
        String systemServerType,
        String systemDefaultModel,
        boolean usePersonalCloud,
        String personalCloudProvider,
        String personalCloudApiKeyMasked,
        String personalCloudModel) {}
