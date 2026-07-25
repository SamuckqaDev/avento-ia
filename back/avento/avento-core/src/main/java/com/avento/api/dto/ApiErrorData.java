package com.avento.api.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiErrorData {

    private final String message;
    private final String path;
    private final Instant timestamp;
    private final String traceId;

    @Builder.Default
    private final List<ApiFieldError> errors = List.of();
}
