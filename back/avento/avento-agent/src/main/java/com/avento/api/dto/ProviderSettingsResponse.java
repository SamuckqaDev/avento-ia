package com.avento.api.dto;

public record ProviderSettingsResponse(
        String systemServerUrl,
        String systemServerType,
        String systemDefaultModel,
        boolean usePersonalCloud,
        String personalCloudProvider,
        String personalCloudApiKeyMasked,
        String personalCloudModel
) {}
