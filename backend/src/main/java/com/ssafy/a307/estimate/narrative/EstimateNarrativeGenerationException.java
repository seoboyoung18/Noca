package com.ssafy.a307.estimate.narrative;

import lombok.Getter;

/**
 * 요약 한 건의 생성 실패 (S15P21A307-537). 워커가 받아 {@code failure_reason} 에 적는다.
 *
 * <p>{@code BusinessException} 이 아니다 — 사용자 요청에 대한 응답이 아니라 <b>워커 안에서</b>
 * 끝나는 실패다. HTTP 상태로 옮길 자리가 없다.
 */
@Getter
public class EstimateNarrativeGenerationException extends RuntimeException {

    private final EstimateNarrativeFailure failure;

    public EstimateNarrativeGenerationException(EstimateNarrativeFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public EstimateNarrativeGenerationException(EstimateNarrativeFailure failure, String message,
                                                Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }
}
