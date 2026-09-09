package com.ssafy.a307.estimatevalidation.service;

/**
 * 파일 검증 한 건이 실패했음을 워커에 알리는 예외.
 *
 * <p><b>메시지를 사용자에게 그대로 쓰지 않는다.</b> 사용자에게 나가는 문구는
 * {@link EstimateValidationFailure#userMessage()} 이고, 이 예외의 메시지와 원인은 로그 전용이다.
 * 둘을 나눈 이유는 원인 문자열에 저장소 키·모델 응답·API 키가 섞여 들어오기 때문이다.
 */
public class EstimateProcessingException extends RuntimeException {

    private final EstimateValidationFailure failure;

    public EstimateProcessingException(EstimateValidationFailure failure, String logMessage) {
        super(logMessage);
        this.failure = failure;
    }

    public EstimateProcessingException(EstimateValidationFailure failure, String logMessage, Throwable cause) {
        super(logMessage, cause);
        this.failure = failure;
    }

    public EstimateValidationFailure failure() {
        return failure;
    }
}
