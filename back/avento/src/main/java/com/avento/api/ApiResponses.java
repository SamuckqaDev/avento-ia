package com.avento.api;

import com.avento.api.dto.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class ApiResponses {

    private ApiResponses() {}

    public static <T> ResponseEntity<BaseResponse<T>> ok(T data) {
        return of(HttpStatus.OK, ApiCodes.SUCCESS, data);
    }

    public static <T> ResponseEntity<BaseResponse<T>> created(T data) {
        return of(HttpStatus.CREATED, ApiCodes.CREATED, data);
    }

    public static <T> ResponseEntity<BaseResponse<T>> accepted(T data) {
        return of(HttpStatus.ACCEPTED, ApiCodes.ACCEPTED, data);
    }

    public static <T> ResponseEntity<BaseResponse<T>> of(HttpStatus status, String code, T data) {
        return ResponseEntity.status(status).body(new BaseResponse<>(status.value(), code, data));
    }
}
