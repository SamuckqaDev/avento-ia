package com.avento.dto;

import jakarta.validation.constraints.NotBlank;

public record ProjectAnalysisRequest(@NotBlank String path) {}
