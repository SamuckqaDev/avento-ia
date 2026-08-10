package com.avento.model.exception;

import com.avento.dto.api.ApiCodes;
import org.springframework.http.HttpStatus;

public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, ApiCodes.INVALID_REQUEST, message);
    }
}
