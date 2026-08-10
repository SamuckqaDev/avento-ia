package com.avento.config;

import com.avento.dto.ApiErrorData;
import com.avento.dto.ApiFieldError;
import com.avento.dto.BaseResponse;
import com.avento.dto.api.ApiCodes;
import com.avento.dto.api.ApiErrorResponses;
import com.avento.model.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.NoSuchElementException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleApiException(
            ApiException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(request, exception.getStatus(), exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleResponseStatusException(
            ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return ApiErrorResponses.response(request, status, codeFor(status), exception.getReason());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleValidationException(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiFieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        return ApiErrorResponses.response(
                request, HttpStatus.BAD_REQUEST, ApiCodes.VALIDATION_ERROR, "Request validation failed.", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleConstraintViolationException(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<ApiFieldError> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ApiFieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return ApiErrorResponses.response(
                request, HttpStatus.BAD_REQUEST, ApiCodes.VALIDATION_ERROR, "Request validation failed.", errors);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<BaseResponse<ApiErrorData>> handleMalformedRequest(
            Exception exception, HttpServletRequest request) {
        return ApiErrorResponses.response(
                request, HttpStatus.BAD_REQUEST, ApiCodes.INVALID_REQUEST, safeMessage(exception));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(
                request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiCodes.UNSUPPORTED_MEDIA_TYPE, safeMessage(exception));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(
                request, HttpStatus.METHOD_NOT_ALLOWED, ApiCodes.METHOD_NOT_ALLOWED, safeMessage(exception));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleNotFound(
            RuntimeException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(request, HttpStatus.NOT_FOUND, ApiCodes.NOT_FOUND, safeMessage(exception));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleBadCredentials(
            BadCredentialsException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(
                request, HttpStatus.UNAUTHORIZED, ApiCodes.AUTH_INVALID_CREDENTIALS, safeMessage(exception));
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    public ResponseEntity<BaseResponse<ApiErrorData>> handleForbidden(
            RuntimeException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(request, HttpStatus.FORBIDDEN, ApiCodes.FORBIDDEN, safeMessage(exception));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleIllegalArgumentException(
            IllegalArgumentException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(
                request, HttpStatus.BAD_REQUEST, ApiCodes.INVALID_REQUEST, safeMessage(exception));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleIllegalStateException(
            IllegalStateException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(request, HttpStatus.CONFLICT, ApiCodes.CONFLICT, safeMessage(exception));
    }

    /**
     * Cliente que desiste de um stream: fim normal, nao erro.
     *
     * <p>Fechar a aba no meio de um SSE derruba o socket e a escrita seguinte estoura com "Broken
     * pipe". Isso caia no tratador geral e produzia DOIS problemas: um {@code ERROR} com pilha
     * inteira para um evento rotineiro, e — pior — a tentativa de responder um {@code BaseResponse}
     * JSON num canal que ja estava com {@code Content-Type: text/event-stream}. Sem conversor para
     * isso, o proprio tratador estourava, e a excecao original ficava soterrada sob a segunda.
     *
     * <p>Responder 204 sem corpo encerra sem tentar serializar nada: nao ha para quem escrever, que
     * e exatamente o que aconteceu.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleClientGoneAway(
            AsyncRequestNotUsableException exception, HttpServletRequest request) {
        log.debug("Cliente desconectou de {}: {}", request.getRequestURI(), exception.getMessage());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {
        log.error("Unhandled API error for {}", request.getRequestURI(), exception);
        return ApiErrorResponses.response(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiCodes.INTERNAL_ERROR,
                "An unexpected internal error occurred.");
    }

    private ApiFieldError fieldError(FieldError fieldError) {
        return new ApiFieldError(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "The request could not be processed."
                : exception.getMessage();
    }

    private String codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> ApiCodes.INVALID_REQUEST;
            case UNAUTHORIZED -> ApiCodes.UNAUTHORIZED;
            case FORBIDDEN -> ApiCodes.FORBIDDEN;
            case NOT_FOUND -> ApiCodes.NOT_FOUND;
            case CONFLICT -> ApiCodes.CONFLICT;
            case UNSUPPORTED_MEDIA_TYPE -> ApiCodes.UNSUPPORTED_MEDIA_TYPE;
            case UNPROCESSABLE_ENTITY -> ApiCodes.UNPROCESSABLE_ENTITY;
            case SERVICE_UNAVAILABLE -> ApiCodes.SERVICE_UNAVAILABLE;
            default -> status.is5xxServerError() ? ApiCodes.INTERNAL_ERROR : status.name();
        };
    }
}
