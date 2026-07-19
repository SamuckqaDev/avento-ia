package com.avento.api.exception;

import com.avento.api.ApiCodes;
import org.springframework.http.HttpStatus;

public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, ApiCodes.INVALID_REQUEST, message);
    }
}
