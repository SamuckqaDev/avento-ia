package com.avento.service.dto;

public record ServerDescriptor(
        String id,
        String name,
        String description,
        String profile,
        boolean local,
        boolean requiresNetwork,
        boolean requiresConfiguration,
        boolean available,
        boolean connected,
        String unavailableReason) {}
