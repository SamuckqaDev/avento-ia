package com.avento.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskUpdateRequest(
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 4000) String details,
        Boolean needsApproval,
        @Min(1) @Max(20) Integer orderIndex,
        Boolean skipped) {}
