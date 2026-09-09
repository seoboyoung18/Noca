package com.ssafy.a307.common.llm;

/**
 * LLM 호출이 실패했다.
 *
 * <p><b>재시도 가능 여부를 예외가 들고 다닌다.</b> 소비처가 상태 코드를 다시 해석하게 두면
 * 판단이 두 곳으로 갈리고, 그중 하나가 400 에 재시도를 걸면 <b>같은 실패로 팀 크레딧을 태운다.</b>
 * 그래서 분류는 이 계층에서 한 번만 하고 결과만 넘긴다.
 *
 * <p><b>메시지에 담지 않는 것</b> — 키, {@code Authorization}/{@code x-goog-api-key} 헤더,
 * 요청 본문, 응답 본문, 첨부 바이트, 파일명. 사용자에게 그대로 나갈 수 있고, 응답 본문에는
 * 견적서 내용이 들어 있다.
 */
public class LlmChatException extends RuntimeException {

    private final Reason reason;
    private final boolean retryable;

    public LlmChatException(Reason reason, boolean retryable, String message) {
        super(message);
        this.reason = reason;
        this.retryable = retryable;
    }

    public LlmChatException(Reason reason, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    public Reason reason() {
        return reason;
    }

    /** 같은 요청을 다시 보내면 결과가 달라질 수 있는가. */
    public boolean isRetryable() {
        return retryable;
    }

    public enum Reason {

        /** 401·403. 키가 잘못됐거나 만료됐다. 사용자 잘못이 아니다. */
        AUTHENTICATION,

        /** 429. 프록시나 벤더의 레이트리밋·쿼터. */
        RATE_LIMITED,

        /** 잔여 크레딧이 임계값 미만이거나 키가 만료됐다. <b>호출 자체를 하지 않았다.</b> */
        CREDIT_EXHAUSTED,

        /** 400·413·415 등. 요청이 잘못됐다. 다시 보내도 같다. */
        BAD_REQUEST,

        /** 5xx. 벤더나 프록시 쪽 문제. */
        UPSTREAM_ERROR,

        /** 연결 실패·타임아웃. */
        TRANSPORT,

        /** 응답은 왔지만 본문에 모델 출력이 없다. */
        EMPTY_RESPONSE,

        /** 출력 토큰 상한에 걸려 잘렸다. 스키마를 만족하지 못한다. */
        TRUNCATED_RESPONSE,

        /** 본문이 있으나 JSON 으로 읽을 수 없다. 구조화 출력을 켜도 모델은 어긴다. */
        MALFORMED_RESPONSE
    }
}
