package com.avento.api;

import com.avento.api.dto.ApiErrorData;
import com.avento.api.dto.ApiFieldError;
import com.avento.api.dto.BaseResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class ApiErrorResponses {

    private ApiErrorResponses() {}

    public static ResponseEntity<BaseResponse<ApiErrorData>> response(
            HttpServletRequest request, HttpStatus status, String code, String message) {
        return response(request, status, code, message, List.of());
    }

    public static ResponseEntity<BaseResponse<ApiErrorData>> response(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message,
            List<ApiFieldError> fieldErrors) {
        return ResponseEntity.status(status).body(body(request, status, code, message, fieldErrors));
    }

    public static BaseResponse<ApiErrorData> body(
            HttpServletRequest request, HttpStatus status, String code, String message) {
        return body(request, status, code, message, List.of());
    }

    public static BaseResponse<ApiErrorData> body(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message,
            List<ApiFieldError> fieldErrors) {
        ApiErrorData data = ApiErrorData.builder()
                .message(message == null || message.isBlank() ? status.getReasonPhrase() : message)
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .traceId(RequestTraceFilter.traceId(request))
                .errors(fieldErrors == null ? List.of() : List.copyOf(fieldErrors))
                .build();
        return new BaseResponse<>(status.value(), code, data);
    }
}
