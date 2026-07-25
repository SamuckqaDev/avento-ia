package com.avento.api.exception;

import com.avento.api.ApiCodes;
import org.springframework.http.HttpStatus;

public class ApiServiceException extends ApiException {

    public ApiServiceException(String message, Throwable cause) {
        this(HttpStatus.INTERNAL_SERVER_ERROR, ApiCodes.INTERNAL_ERROR, message, cause);
    }

    public ApiServiceException(HttpStatus status, String code, String message, Throwable cause) {
        super(status, code, message, cause);
    }
}
