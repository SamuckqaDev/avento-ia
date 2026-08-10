package com.avento.model.exception;

import com.avento.dto.api.ApiCodes;
import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code == null || code.isBlank() ? ApiCodes.NOT_FOUND : code, message);
    }
}
