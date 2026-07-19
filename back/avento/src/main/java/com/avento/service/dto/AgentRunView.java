package com.avento.service.dto;

import java.time.LocalDateTime;

public record AgentRunView(
        String runId,
        Long chatId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt) {}
