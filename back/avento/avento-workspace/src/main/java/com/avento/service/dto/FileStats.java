package com.avento.service.dto;

import java.util.List;
import java.util.Map;

public record FileStats(
        int totalFiles,
        long totalBytes,
        Map<String, Integer> extensions,
        List<String> ignoredDirectories,
        boolean truncated) {}
