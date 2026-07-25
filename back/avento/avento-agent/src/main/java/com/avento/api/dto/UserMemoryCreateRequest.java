package com.avento.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserMemoryCreateRequest(
        @NotBlank @Size(max = 500) String content,
        @Size(max = 80) String category) {}
