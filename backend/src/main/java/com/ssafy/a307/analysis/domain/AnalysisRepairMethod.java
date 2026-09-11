package com.ssafy.a307.analysis.domain;

import java.util.Locale;

/**
 * 수리 방식 — 파이프라인 표기(UPPER_SNAKE)와 서비스 DDL 표기를 잇는 <b>유일한 변환 지점</b>.
 *
 * <pre>
 *   파이프라인   EXCHANGE      ← 계약의 work_candidates[].code
 *   서비스 DDL   'exchange'    ← ck_dp_method
 * </pre>
 *
 * <p>{@link AnalysisDamageType} 과 같은 이유로 변환을 여기 한 곳에만 둔다. DDL 값은
 * {@code pipeline/standardization/catalog.py} 의 {@code WORKS} 가 가진 {@code name_en} 과 같다.
 *
 * <p><b>견적 어휘 13종과 다른 어휘다.</b> {@code catalog.ESTIMATE_WORKS} 에는 탈착·오버홀·견인
 * 같은 값이 더 있지만, 분석이 내놓는 후보는 아래 넷뿐이고 {@code ck_dp_method} 도 넷만 받는다.
 * <b>두 어휘를 섞으면 안 된다</b> — 견적 쪽 {@code WorkType} 은
 * {@code com.ssafy.a307.estimatevalidation.domain.WorkType} 이고 용도가 다르다.
 */
public enum AnalysisRepairMethod {

    COATING("coating"),
    REPAIR("repair"),
    SHEET_METAL("sheet_metal"),
    EXCHANGE("exchange");

    /** {@code damaged_part.repair_method} 에 그대로 들어가는 값. {@code ck_dp_method} 가 이것만 받는다. */
    private final String columnValue;

    AnalysisRepairMethod(String columnValue) {
        this.columnValue = columnValue;
    }

    public String columnValue() {
        return columnValue;
    }

    /** 계약의 {@code work_candidates[].code}(UPPER_SNAKE)를 해석한다. 모르는 코드는 거절한다. */
    public static AnalysisRepairMethod from(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("work_candidates[].code 는 필수입니다.");
        }
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        for (AnalysisRepairMethod value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("알 수 없는 수리 방식입니다: " + code);
    }
}
