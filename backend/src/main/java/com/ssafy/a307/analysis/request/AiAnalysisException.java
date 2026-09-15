package com.ssafy.a307.analysis.request;

/**
 * AI 서버가 분석 요청을 받지 않았다.
 *
 * @see AiAnalysisClient
 */
public class AiAnalysisException extends RuntimeException {

    private final String failureCode;

    /**
     * @param failureCode {@code analysis_job.failure_reason} 에 적을 값. AI 가 준 오류 코드이거나
     *                    {@link AnalysisRequestFailure} 이름이다
     */
    public AiAnalysisException(String failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
