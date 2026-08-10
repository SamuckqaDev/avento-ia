package com.avento.dto;

import java.util.List;

public record UsageSummary(
        String range,
        long total,
        long promptTotal,
        long completionTotal,
        long requestCount,
        List<ModelUsage> byModel,
        List<DayTotal> byDay,
        List<ChatUsage> byChat) {}
