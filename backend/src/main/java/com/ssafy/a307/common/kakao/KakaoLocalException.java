package com.ssafy.a307.common.kakao;

import com.ssafy.a307.common.exception.ErrorCode;

/**
 * 카카오 로컬 API 호출이 실패했다.
 *
 * <p><b>어떤 {@link ErrorCode} 로 나갈지와 재시도 가능 여부를 예외가 들고 다닌다.</b>
 * 소비처가 상태 코드를 다시 해석하게 두면 판단이 두 곳으로 갈리고, 그중 하나가
 * 쿼터 초과에 재시도를 걸면 <b>이미 바닥난 쿼터를 더 태운다.</b> 그래서 분류는 전송 계층에서
 * 한 번만 하고 결과만 넘긴다. {@link com.ssafy.a307.common.llm.LlmChatException} 과 같은 형태다.
 *
 * <p><b>메시지에 담지 않는 것</b> — REST API 키, {@code Authorization} 헤더, 요청 URL 전문,
 * 카카오 원본 오류 메시지. 이 메시지는 사용자에게 그대로 나갈 수 있다. 카카오 원본 문구를
 * 흘리면 우리 통합 구조가 밖으로 드러나고, 문구가 바뀌면 FE 분기가 조용히 깨진다.
 */
public class KakaoLocalException extends RuntimeException {

    private final ErrorCode errorCode;
    private final boolean retryable;

    public KakaoLocalException(ErrorCode errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public KakaoLocalException(ErrorCode errorCode, boolean retryable,
                               String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 같은 요청을 다시 보내면 결과가 달라질 수 있는가. */
    public boolean isRetryable() {
        return retryable;
    }
}
