package com.avento.service.settings;

import com.avento.dto.UsageSummary;
import com.avento.model.TokenUsage;
import com.avento.model.TokenUsageRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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
        String normalizedRange = normalizeRange(range);
        if (userId == null) {
            return new UsageSummary(normalizedRange, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }

        LocalDateTime since = sinceFor(normalizedRange);
        long modelCallCount = tokenUsageRepository.countSince(userId, since);
        return new UsageSummary(
                normalizedRange,
                tokenUsageRepository.sumTotalSince(userId, since),
                tokenUsageRepository.sumPromptSince(userId, since),
                tokenUsageRepository.sumCompletionSince(userId, since),
                tokenUsageRepository.countDistinctRunsSince(userId, since),
                modelCallCount,
                // Campo mantido para clientes antigos. Ele sempre foi contagem de chamadas ao modelo.
                modelCallCount,
                tokenUsageRepository.usageByModelSince(userId, since),
                tokenUsageRepository.sumByDaySince(userId, since),
                tokenUsageRepository.usageByChatSince(userId, since, PageRequest.of(0, 5)));
    }

    private String normalizeRange(String range) {
        return "today".equals(range) || "30d".equals(range) ? range : "7d";
    }

    private LocalDateTime sinceFor(String normalizedRange) {
        return switch (normalizedRange) {
            case "today" -> LocalDate.now().atStartOfDay();
            case "30d" -> LocalDate.now().minusDays(29).atStartOfDay();
            default -> LocalDate.now().minusDays(6).atStartOfDay();
        };
    }
}
