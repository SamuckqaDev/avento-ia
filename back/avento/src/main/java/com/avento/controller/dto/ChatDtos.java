package com.avento.controller.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public final class ChatDtos {

    private ChatDtos() {}

    public record ChatCreateRequest(String title, String projectPath) {}

    public record ChatUpdateRequest(String title, String projectPath) {}

    public record ChatResponse(Long id, String title, LocalDateTime updatedAt, String projectPath) {}

    public record MessageCreateRequest(
            @NotBlank String role, @NotBlank String content, String documentContext, String documentNames) {}

    public record MessageResponse(
            Long id,
            Long chatId,
            String role,
            String content,
            String documentContext,
            String documentNames,
            LocalDateTime timestamp) {}
}
