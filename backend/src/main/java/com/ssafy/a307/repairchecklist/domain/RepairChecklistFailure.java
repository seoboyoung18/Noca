package com.ssafy.a307.repairchecklist.domain;

/**
 * 체크리스트 생성 실패 사유. <b>이 이름이 그대로 {@code repair_checklist.failure_reason} 에 들어간다.</b>
 *
 * <p><b>한글 문장을 넣지 않는다.</b> 실패 사유는 <b>분류</b>이지 문구가 아니다. 화면 문안은 FE 가
 * 정하고, 서버가 문장을 박으면 표현을 바꿀 때마다 배포해야 한다 —
 * {@code AnalysisProgressResponse.ExcludedImage#reason} 과 같은 판단이다.
 *
 * <p>값이 {@code repair_checklist.failure_reason VARCHAR(200)} 안에 들어가야 한다. 이름이 길어지면
 * 잘려 나가 분류가 흐려지므로 짧게 유지한다.
 */
public enum RepairChecklistFailure {

    /** LLM 어댑터가 없다. {@code GMS_KEY} 가 빠진 환경에서 접수분이 남아 있을 때만 나온다. */
    LLM_UNAVAILABLE,

    /** 호출 자체가 실패했다 — 타임아웃·인증·크레딧·5xx. */
    LLM_CALL_FAILED,

    /**
     * 응답을 쓸 수 없다. JSON 이 깨졌거나, 파싱은 됐는데 쓸 만한 항목이 하나도 없는 경우다.
     *
     * <p>{@code S15P21A307-452}(AI 쪽 스키마 검증·재시도)가 미배정이라 백엔드가 스스로 막는
     * 자리가 여기다. 구조화 출력으로 형식을 고정하고, 그래도 깨져 오면 이 사유로 끝낸다.
     */
    INVALID_RESPONSE,

    /** 처리 도중 프로세스가 사라져 {@code PROCESSING} 으로 남아 있던 건. */
    ABANDONED,

    /** 그 밖의 예상치 못한 오류. */
    INTERNAL
}
