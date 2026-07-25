package com.avento.api.dto;

import com.avento.model.UserMemory;
import java.time.LocalDateTime;

public record UserMemoryResponse(
        Long id,
        String content,
        String category,
        String status,
        String origin,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static UserMemoryResponse from(UserMemory memory) {
        return new UserMemoryResponse(
                memory.getId(),
                memory.getContent(),
                memory.getCategory(),
                memory.getStatus(),
                memory.getOrigin(),
                memory.getCreatedAt(),
                memory.getUpdatedAt());
    }
}
