package com.ssafy.a307.estimate.entity;

/**
 * 견적 전체의 신뢰도. {@code ck_est_grade} 가 이 세 값만 허용한다.
 * <p>
 * 항목별 신뢰도({@code estimate_item.is_low_confidence})와는 층이 다르다 —
 * 이쪽은 견적 하나를 통째로 본 등급이고, 낮으면 화면에 경고를 띄운다(S15P21A307-290).
 * <p>
 * 산정하지 못한 견적은 등급이 없다({@code null}). 매길 근거가 없기 때문이다.
 */
public enum ConfidenceGrade {

    /** 참조 사례가 충분하고 조건 완화 없이 산정됐다. */
    HIGH,

    /** 조건을 한 단계 완화해 산정했다(예: 동일 차량명 → 동일 차급). */
    MEDIUM,

    /** 참조 사례가 적거나 전체 범위까지 완화했다. 경고를 함께 보여 준다. */
    LOW
}
