package com.ssafy.a307.estimate.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 산정 근거 한 줄 문구. 명세서 51행이 요구한 근거 문구 템플릿이다(S15P21A307-284).
 *
 * <pre>
 *   2023~2025년 동일 모델 프론트 범퍼 교환 사례 37건의 중앙값 기준
 *   └ 연도 범위  └ 조건 단계 └ 부위      └ 방식 └ 건수      └ 대표값
 * </pre>
 *
 * <p><b>서버가 조립한다.</b> 프론트가 여섯 조각으로 한국어를 만들면 어휘가 바뀔 때 두 곳을
 * 고쳐야 하고, 리포트·PDF·공유 화면이 서로 다른 문장을 쓰게 된다. 수리 방식 표시 문구를
 * 서버가 내려주는 것과 같은 판단이다({@link RepairMethodDisplay}).
 *
 * <p><b>"동일 차종"이라고 쓰지 않는다.</b> 명세서 51행의 예시 문구는 그렇게 적혀 있지만, 이
 * 프로젝트에서 차종은 {@code vehicle_type}(SEDAN·SUV·VAN·TRUCK)이고 폴백 1단계가 보는 것은
 * {@code model_id} 다. 예시를 그대로 옮기면 화면의 "차종"과 DB 의 "차종"이 서로 다른 것을
 * 가리키게 된다.
 *
 * <p><b>지역은 넣지 않는다.</b> 예시 문구의 {@code (수도권)} 은 폐기다 —
 * {@code repair_case} 에 지역 컬럼이 없어 적을 근거가 없다.
 *
 * <p><b>모르는 것은 적지 않는다.</b> 근거가 부분적으로만 있으면 있는 조각만으로 문장을 만든다.
 * 비어 있는 자리를 그럴듯한 기본값으로 채우면, 사용자는 확인되지 않은 사실을 근거로 읽는다.
 */
public final class BasisNarrative {

    private BasisNarrative() {
    }

    /**
     * @param condition               항목의 산정 근거 스냅샷
     * @param partNameKo              부위 한글명 ({@code part_code.name_ko})
     * @param repairMethodDisplayName 수리 방식 표시 문구 ("교환"·"판금"…)
     * @param refCaseCount            참조한 유사 사례 건수 ({@code estimate_item.ref_case_count})
     * @return 근거 문구. <b>참조한 사례가 없으면 {@code null}</b> — 근거가 없는데 문장을 만들면
     *         "0건의 중앙값 기준" 같은 말이 된다. 근거가 부족하다는 사실 자체를 화면이
     *         표시해야 한다(명세서 51행)
     */
    public static String of(RefCondition condition, String partNameKo,
                            String repairMethodDisplayName, Integer refCaseCount) {

        if (refCaseCount == null || refCaseCount <= 0) {
            return null;
        }

        RefCondition basis = condition == null ? RefCondition.EMPTY : condition;
        List<String> parts = new ArrayList<>();

        String years = yearRange(basis.refYearFrom(), basis.refYearTo());
        if (years != null) {
            parts.add(years);
        }
        String scope = scope(basis.fallbackStage());
        if (scope != null) {
            parts.add(scope);
        }
        if (partNameKo != null && !partNameKo.isBlank()) {
            parts.add(partNameKo);
        }
        if (repairMethodDisplayName != null && !repairMethodDisplayName.isBlank()) {
            parts.add(repairMethodDisplayName);
        }

        parts.add("사례 %d건의".formatted(refCaseCount));
        parts.add(representativeValue(basis.costDistribution()));

        return String.join(" ", parts);
    }

    /**
     * 참조한 사례가 몇 년 것인지. {@code repair_case.repair_year} 는 견적서 입고일자의 연도다.
     * <p>
     * 한쪽만 있으면 범위가 아니라 한 해로 읽히거나 열린 구간이 되어 오해를 만든다. 둘 다
     * 있을 때만 적는다.
     */
    private static String yearRange(Integer from, Integer to) {
        if (from == null || to == null) {
            return null;
        }
        return from.equals(to) ? "%d년".formatted(from) : "%d~%d년".formatted(from, to);
    }

    /** 조건을 어디까지 넓혔는지. 이 한 마디가 근거의 강도를 가른다. */
    private static String scope(FallbackStage stage) {
        if (stage == null) {
            return null;
        }
        return switch (stage) {
            case MODEL -> "동일 모델";
            case CAR_CLASS -> "동일 차급";
            case ALL -> "전체";
        };
    }

    /**
     * 대표값이 무엇인지. 중앙값을 못 구했으면 "중앙값"이라고 쓰지 않는다 —
     * 근거 문구가 사실과 달라지는 것이 문구가 밋밋해지는 것보다 나쁘다.
     */
    private static String representativeValue(RefCondition.CostDistribution distribution) {
        return distribution != null && distribution.median() != null ? "중앙값 기준" : "기준";
    }
}
