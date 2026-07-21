package com.avento.api.dto;

import com.avento.service.dto.ChatUsage;
import com.avento.service.dto.DayTotal;
import com.avento.service.dto.ModelUsage;
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
