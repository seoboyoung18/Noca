package com.ssafy.a307.repairchecklist.domain;

/**
 * 체크리스트 한 건의 생성 실패. <b>워커가 받아 {@code FAILED} 로 기록한다.</b>
 *
 * <p>{@code BusinessException} 이 아니다 — 이 예외는 HTTP 응답이 되지 않는다. 생성은 요청과
 * 분리된 비동기 경로라, 실패는 상태로 알린다({@code S15P21A307-460} 은 접수 자체가 200 이다).
 */
public class RepairChecklistGenerationException extends RuntimeException {

    private final transient RepairChecklistFailure failure;

    public RepairChecklistGenerationException(RepairChecklistFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public RepairChecklistGenerationException(RepairChecklistFailure failure, String message,
                                              Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public RepairChecklistFailure failure() {
        return failure;
    }
}
