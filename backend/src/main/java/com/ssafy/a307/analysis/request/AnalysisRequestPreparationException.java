package com.ssafy.a307.analysis.request;

/** AI 에 보내기 전 단계(사진·URL 준비)에서 이 건을 보낼 수 없다. */
public class AnalysisRequestPreparationException extends RuntimeException {

    private final AnalysisRequestFailure failure;

    public AnalysisRequestPreparationException(AnalysisRequestFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public AnalysisRequestFailure failure() {
        return failure;
    }
}
