package com.avento.service.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record VideoJobView(
        UUID id,
        Long chatId,
        String status,
        String stage,
        int progress,
        Long estimatedRemainingSeconds,
        long elapsedSeconds,
        int width,
        int height,
        int frames,
        int fps,
        String prompt,
        String sourceImageUrl,
        String mediaUrl,
        String error,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {}
