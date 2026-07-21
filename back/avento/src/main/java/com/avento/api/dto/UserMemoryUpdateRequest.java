package com.avento.api.dto;

import jakarta.validation.constraints.Size;

public record UserMemoryUpdateRequest(
        @Size(max = 500) String content, @Size(max = 80) String category) {}
