package com.avento.service;

import com.avento.api.dto.UsageSummary;
import com.avento.model.TokenUsage;
import com.avento.repository.TokenUsageRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final TokenUsageRepository tokenUsageRepository;

    public void record(UUID userId, Long chatId, String runId, String model, int promptTokens, int completionTokens) {
        if (userId == null) {
            return;
        }
        try {
            TokenUsage usage = new TokenUsage();
            usage.setUserId(userId);
            usage.setChatId(chatId);
            usage.setRunId(runId);
            usage.setModel(model != null ? model : "unknown");
            usage.setPromptTokens(promptTokens);
            usage.setCompletionTokens(completionTokens);
            usage.setTotalTokens(promptTokens + completionTokens);
            usage.setCreatedAt(LocalDateTime.now());
            tokenUsageRepository.save(usage);
        } catch (Exception e) {
            log.warn("Failed to record token usage for runId {}", runId, e);
        }
    }

    public UsageSummary summary(UUID userId, String range) {
        if (userId == null) {
            return new UsageSummary(0, java.util.List.of(), java.util.List.of());
        }

        LocalDateTime since;
        if ("30d".equals(range)) {
            since = LocalDate.now().minusDays(30).atStartOfDay();
        } else if ("7d".equals(range)) {
            since = LocalDate.now().minusDays(7).atStartOfDay();
        } else {
            // today
            since = LocalDate.now().atStartOfDay();
        }

        long total = tokenUsageRepository.sumTotalSince(userId, since);
        return new UsageSummary(
                total,
                tokenUsageRepository.sumByModelSince(userId, since),
                tokenUsageRepository.sumByDaySince(userId, since));
    }
}
