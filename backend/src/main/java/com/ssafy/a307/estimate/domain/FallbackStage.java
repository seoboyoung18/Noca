package com.ssafy.a307.estimate.domain;

/**
 * 유사 사례를 찾을 때 조건을 어디까지 넓혔는지.
 *
 * <p>검색은 {@code repair_case.model_id} 로 동일 차량명부터 시작해 사례가 모자라면 동일 차급,
 * 그래도 모자라면 전체로 물러난다. <b>어느 단계까지 물러났는지를 화면에 표시하는 것이 필수다</b>
 * — "동일 차종 37건 기준"과 "전체 사례 37건 기준"은 사용자가 견적을 믿을지 판단하는 근거가
 * 전혀 다르다.
 *
 * <p><b>단계는 신뢰도 등급과 짝을 이룬다.</b> {@link com.ssafy.a307.estimate.entity.ConfidenceGrade}
 * 가 이미 같은 구분을 등급 쪽에서 설명하고 있다 — 완화 없음이 HIGH, 한 단계가 MEDIUM,
 * 전체까지가 LOW 다. 둘을 한 자리에 합치지 않은 것은 등급이 사례 건수도 함께 보기 때문이다.
 *
 * <p><b>사고 스냅샷이 항상 있으므로 1단계는 언제나 시도된다.</b> {@code accident.snapshot_model_id}
 * 가 {@code NOT NULL} 이라 "차량 정보가 없어 처음부터 넓게 찾았다"는 경우는 없다.
 */
public enum FallbackStage {

    /** 동일 차량명. 조건을 넓히지 않았다. */
    MODEL,

    /** 동일 차급. 같은 차량명 사례가 모자라 한 단계 넓혔다. */
    CAR_CLASS,

    /** 전체. 차량 조건을 모두 풀었다 — 가장 약한 근거다. */
    ALL
}
