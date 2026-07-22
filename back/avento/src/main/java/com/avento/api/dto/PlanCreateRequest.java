package com.avento.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PlanCreateRequest(
        @NotBlank @Size(max = 4000) String goal,
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 2048) String> workspaceRoots,
        @NotNull Long chatId) {}
