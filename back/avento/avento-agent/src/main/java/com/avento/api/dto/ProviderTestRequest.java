package com.avento.api.dto;

public record ProviderTestRequest(
        String targetType, // "SYSTEM_LAN" or "PERSONAL_CLOUD"
        String serverUrl,
        String serverType,
        String apiKey,
        String modelName) {}
