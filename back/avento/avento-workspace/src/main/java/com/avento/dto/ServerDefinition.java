package com.avento.dto;

public record ServerDefinition(
        String id,
        String name,
        String description,
        String profile,
        boolean local,
        boolean requiresNetwork,
        boolean requiresConfiguration) {}
