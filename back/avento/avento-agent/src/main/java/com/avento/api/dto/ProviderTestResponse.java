package com.avento.api.dto;

public record ProviderTestResponse(
        boolean success,
        String message,
        long latencyMs
) {}
