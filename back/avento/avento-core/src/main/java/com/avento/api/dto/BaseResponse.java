package com.avento.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BaseResponse<T> {

    private final int status;
    private final String code;
    private final T data;
}
