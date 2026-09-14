package com.ssafy.a307.analysis.callback;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 실패 callback 의 오류 본문. 정본은 {@code Docs/Api/AI 서버 오류·재시도 처리 명세.md} 다.
 *
 * <p>{@code code} 는 {@code IMAGE_FETCH_FAILED}·{@code INVALID_MODEL_OUTPUT}·
 * {@code MODEL_ERROR}·{@code INTERNAL} 중 하나다. <b>열거형으로 고정하지 않는다</b> —
 * AI 가 코드를 하나 더 만들었을 때 역직렬화가 깨지면 실패 사실조차 기록하지 못한다.
 * 그러면 작업이 PROCESSING 으로 영원히 남는다. 문자열로 받아 그대로 보존한다.
 *
 * <p>{@code retryable} 은 <b>AI 서버가 재시도할지</b>를 알려 주는 값이고 백엔드의 재시도
 * (S15P21A307-161)와는 다른 층이다. 백엔드는 이 값을 저장하지 않는다 — 지금 그것을 쓰는
 * 경로가 없고, 근거 없이 컬럼을 만들면 무엇이 무엇인지 알 수 없게 된다.
 *
 * @param code    오류 코드. {@code analysis_job.failure_reason} 에 그대로 들어간다(50자 제한)
 * @param message 사람이 읽을 설명. 저장하지 않는다 — 컬럼이 없고, 코드가 분류를 담당한다
 */
public record CallbackError(

        @NotBlank(message = "error.code 는 필수입니다.")
        @Size(max = 50, message = "error.code 는 50자를 넘을 수 없습니다.")
        String code,

        String message,

        Boolean retryable) {
}
