package com.avento.service.dto;

public record LocalModelInfo(
        String name,
        long sizeBytes,
        String sizeLabel,
        String parameterSize,
        String family,
        boolean recommended,
        boolean heavy,
        boolean vision,
        boolean preferredForVision) {}
