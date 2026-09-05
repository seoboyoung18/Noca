package com.ssafy.a307.common.response;

import com.ssafy.a307.common.exception.ErrorCode;

/**
 * 실패 응답 공통 래퍼. 항상 {@code { "error": { "code": "...", "message": "..." } }} 형태로 내려간다.
 * 에러를 HTTP 200 으로 반환하지 않는다.
 */
public record ErrorResponse(Error error) {

    public record Error(String code, String message) {
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(new Error(errorCode.name(), message));
    }
}
