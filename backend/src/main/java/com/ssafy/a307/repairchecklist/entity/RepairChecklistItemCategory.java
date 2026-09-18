package com.ssafy.a307.repairchecklist.entity;

/**
 * 항목이 무엇에 대한 것인가 (S15P21A307-544).
 *
 * <p><b>{@link RepairChecklistItemSource} 와 축이 다르다.</b> {@code source} 는 "누가 만들었나"
 * ({@code AI} · {@code COMMON} · {@code USER})이고, 이 값은 "무엇에 대한 항목인가" 다. 그래서
 * 사용자가 직접 넣은 항목도 {@link #PART} 나 {@link #HIDDEN} 일 수 있다 — 두 축을 하나로 합치면
 * "사용자가 추가한 부위별 항목" 을 표현할 자리가 없어진다.
 *
 * <p>화면은 이 값으로 세 탭(공통 / 부품별 / 함께 점검)을 가른다.
 */
public enum RepairChecklistItemCategory {

    /** 모든 사고에 공통인 절차. 문안은 {@code repair_checklist_common_item} 이 정본이다. */
    COMMON,

    /** 사진에서 확인된 부위에 대한 항목. {@code partCode} 가 있으면 화면이 부위별로 묶는다. */
    PART,

    /**
     * 사진에 보이지 않지만 함께 점검을 권하는 항목.
     *
     * <p>"전면이 부서졌으면 범퍼 뒤 냉각 부품도 보라" 처럼 <b>분석 결과에 없는 부위</b>를
     * 가리킬 수 있다. 그래서 {@code reason} 이 붙는다 — 화면이 "왜 이것도 보라는가" 를
     * 함께 보여 주지 않으면 지어낸 항목처럼 읽힌다.
     */
    HIDDEN
}
