package com.avento.api.dto;

public record ProviderSettingsUpdateRequest(
        String systemServerUrl,
        String systemServerType,
        String systemDefaultModel,
        Boolean usePersonalCloud,
        String personalCloudProvider,
        String personalCloudApiKey,
        String personalCloudModel
) {}
