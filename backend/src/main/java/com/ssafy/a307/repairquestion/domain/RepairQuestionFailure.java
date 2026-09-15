package com.ssafy.a307.repairquestion.domain;

/**
 * 질문 목록 생성 실패 사유. <b>이 이름이 그대로 {@code repair_question.failure_reason} 에 들어간다.</b>
 *
 * <p><b>한글 문장을 넣지 않는다.</b> 실패 사유는 <b>분류</b>이지 문구가 아니다. 화면 문안은 FE 가
 * 정하고, 서버가 문장을 박으면 표현을 바꿀 때마다 배포해야 한다 —
 * {@code RepairChecklistFailure} 와 같은 판단이고 값도 같은 다섯이다.
 *
 * <p>값이 {@code repair_question.failure_reason VARCHAR(200)} 안에 들어가야 한다. 이름이 길어지면
 * 잘려 나가 분류가 흐려지므로 짧게 유지한다.
 */
public enum RepairQuestionFailure {

    /**
     * LLM 어댑터가 없다. {@code GMS_KEY} 가 빠진 환경에서 <b>접수분이 남아 있을 때만</b> 나온다 —
     * 새 요청은 {@code RepairQuestionRequestService} 가 503 으로 먼저 막는다.
     */
    LLM_UNAVAILABLE,

    /** 호출 자체가 실패했다 — 타임아웃 · 인증 · 크레딧 · 5xx. */
    LLM_CALL_FAILED,

    /**
     * 응답을 쓸 수 없다. JSON 이 깨졌거나, 파싱은 됐는데 쓸 만한 질문이 하나도 없는 경우다.
     *
     * <p>비난 표현이 걸러진 끝에 0건이 된 경우도 여기다({@code RepairQuestionGenerator}).
     * 표현 경계를 넘은 목록을 반쯤 내보내느니 실패로 남기는 편이 낫다.
     */
    INVALID_RESPONSE,

    /** 처리 도중 프로세스가 사라져 {@code PROCESSING} 으로 남아 있던 건. */
    ABANDONED,

    /** 그 밖의 예상치 못한 오류. */
    INTERNAL
}
