package com.avento.api.exception;

import com.avento.api.ApiCodes;
import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code == null || code.isBlank() ? ApiCodes.NOT_FOUND : code, message);
    }
}
