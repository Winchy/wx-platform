package com.jistack.platform.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data) {

    public static final int OK = 0;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(OK, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(OK, "ok", null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
