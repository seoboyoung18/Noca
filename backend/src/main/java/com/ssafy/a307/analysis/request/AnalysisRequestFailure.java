package com.ssafy.a307.analysis.request;

/**
 * 분석 요청 단계에서 작업을 {@code FAILED} 로 끝낸 사유 (S15P21A307-156).
 * {@code analysis_job.failure_reason} 에 {@link #name()} 이 그대로 들어간다.
 *
 * <p>AI 서버가 오류 코드를 준 경우({@code IMAGE_FETCH_FAILED} 등)는 여기 없다 — 그 코드를
 * 그대로 적는다. 결과 수신의 실패 callback 과 같은 어휘가 한 컬럼에 모이도록 하기 위해서다.
 */
public enum AnalysisRequestFailure {

    /** 보낼 사진이 없다. 접수 뒤에 사진이 지워진 경우다. */
    NO_IMAGE,

    /** 사진 저장소 어댑터가 없어 조회 URL 을 만들 수 없다. */
    STORAGE_UNAVAILABLE,

    /** AI 서버에 연결하지 못했거나 응답이 제한 시간을 넘겼다. */
    AI_UNREACHABLE,

    /** AI 에 보냈지만 제한 시간 안에 결과가 오지 않았다. */
    ABANDONED,

    /** 예상하지 못한 오류. 로그에 원인이 남는다. */
    INTERNAL
}
