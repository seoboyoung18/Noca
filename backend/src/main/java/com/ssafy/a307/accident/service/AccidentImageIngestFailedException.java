package com.ssafy.a307.accident.service;

import lombok.Getter;

/**
 * 이미지 한 장의 처리가 실패했다는 내부 신호. 이 예외가 {@code REQUIRES_NEW} 트랜잭션 경계를
 * 넘어가면서 <b>그 이미지의 DB 쓰기만 롤백</b>되고, 바깥 루프는 다음 이미지로 넘어간다.
 * <p>
 * HTTP 로 나가지 않는다 — 오케스트레이터가 {@code results[].status = FAILED} 항목으로 옮긴다.
 * 부분 실패를 허용해야 하므로 요청 전체를 400 으로 뒤집지 않는다.
 */
@Getter
public class AccidentImageIngestFailedException extends RuntimeException {

    /** {@code AccidentImageValidationException.Reason} 이름 또는 {@code PROCESSING_ERROR}. */
    private final String failureCode;

    public AccidentImageIngestFailedException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }
}
