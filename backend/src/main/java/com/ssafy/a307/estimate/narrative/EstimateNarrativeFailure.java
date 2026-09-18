package com.ssafy.a307.estimate.narrative;

/**
 * 요약 생성 실패 사유 (S15P21A307-537). <b>한글 문장이 아니다</b> — 화면 문구는 FE 가 정한다.
 *
 * <p>{@code RepairChecklistFailure} 와 같은 이름을 쓴다. 같은 일(LLM 호출)의 실패라 사유를 다르게
 * 부르면 로그를 모아 볼 때 같은 원인이 두 이름으로 갈린다.
 */
public enum EstimateNarrativeFailure {

    /** {@code GMS_KEY} 가 없어 어댑터 빈이 없다. 키를 넣으면 다음 견적부터 생성된다. */
    LLM_UNAVAILABLE,

    /** 전송·응답 처리 실패. 본문은 남기지 않는다 — 견적에는 차주 정보가 섞일 수 있다. */
    LLM_CALL_FAILED,

    /** 응답을 읽었지만 쓸 수 있는 문장이 하나도 없다. */
    INVALID_RESPONSE,

    /**
     * 처리 중이던 워커가 멈췄다 (재기동 · 프로세스 종료).
     *
     * <p><b>큐로 되돌리지 않는다.</b> 이 테이블에는 시도 횟수를 셀 열이 없어, 되돌리면
     * 영영 실패하는 건이 매 주기 GMS 크레딧을 태운다 ({@code RepairChecklistFailure.ABANDONED}
     * 가 같은 제약으로 같은 선택을 했다).
     */
    ABANDONED,

    /** 견적이 사라지는 등 생성을 이어갈 수 없는 상태. */
    INTERNAL
}
