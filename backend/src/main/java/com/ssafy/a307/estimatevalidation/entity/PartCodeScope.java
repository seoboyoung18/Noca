package com.ssafy.a307.estimatevalidation.entity;

/**
 * 부품 코드가 어느 집합에 속하는지. 정본 {@code part_code.code_scope} 의 CHECK 와 같은 값이다.
 *
 * <p>둘을 가르는 이유는 <b>쓰는 곳이 다르기</b> 때문이다. AI 라벨 32종은 파손 검출 모델이
 * 출력하는 클래스라 모델을 다시 학습하지 않으면 늘릴 수 없다. 확장 코드는 정비소 견적서에
 * 나오는 항목을 받아 적기 위한 것이라 운영 중에 늘어난다.
 */
public enum PartCodeScope {

    /** 파손 검출 모델이 출력하는 핵심 라벨. 시드 기준 32종. */
    AI_LABEL,
    /** 견적서 매핑 전용 확장 코드. 시드 기준 24종이며 운영 중 늘어날 수 있다. */
    EXTENDED
}
