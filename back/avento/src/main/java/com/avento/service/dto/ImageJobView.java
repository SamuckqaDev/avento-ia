package com.avento.service.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ImageJobView(
        UUID id,
        Long chatId,
        String status,
        String stage,
        int progress,
        Long estimatedRemainingSeconds,
        long elapsedSeconds,
        String prompt,
        String model,
        String size,
        String mediaUrl,
        String error,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {}
