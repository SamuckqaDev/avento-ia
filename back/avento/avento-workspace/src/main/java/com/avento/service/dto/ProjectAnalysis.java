package com.avento.service.dto;

import java.util.List;

public record ProjectAnalysis(
        String rootPath,
        String projectName,
        String generatedAt,
        List<String> technologies,
        List<ProjectScript> scripts,
        List<String> entrypoints,
        FileStats fileStats,
        List<ProjectFinding> findings,
        List<String> recommendations) {}
