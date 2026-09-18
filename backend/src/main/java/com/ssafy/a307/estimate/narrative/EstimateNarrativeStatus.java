package com.ssafy.a307.estimate.narrative;

/**
 * 요약 생성 상태 (S15P21A307-537). {@code ck_en_status} 가 이 네 값만 허용한다.
 *
 * <p>{@code RepairChecklistStatus} 와 같은 네 값이다 — 이 저장소의 큐는 전부 같은 모양이라
 * 상태 이름이 기능마다 달라지면 읽는 사람이 매번 다시 확인해야 한다.
 */
public enum EstimateNarrativeStatus {

    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
