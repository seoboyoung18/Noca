package com.ssafy.a307.common.response;

/**
 * 성공 응답 공통 래퍼. 항상 {@code { "data": { ... } }} 형태로 내려간다.
 */
public record ApiResponse<T>(T data) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}
