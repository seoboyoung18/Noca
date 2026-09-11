package com.ssafy.a307.admin.dto;

import com.ssafy.a307.estimatevalidation.entity.EstimateValidationRule;
import com.ssafy.a307.estimatevalidation.entity.RepairMethodRule;

import java.math.BigDecimal;
import java.util.List;

/**
 * 수정 가능한 필드의 <b>단위·경계 의미</b>. 화면이 입력 폼을 만들 때 쓰는 메타데이터다.
 *
 * <h2>왜 서버가 내려 주는가</h2>
 * FE 가 {@code max=9999.99} 같은 숫자를 직접 적어 두면 서버의 컬럼 정밀도가 바뀌었을 때
 * <b>화면만 조용히 낡는다.</b> 사용자는 통과할 것 같은 값을 넣고 400 을 받는다. 여기 있는
 * 값은 전부 실제 상수·컬럼 정의에서 온 것이라 서버가 바뀌면 함께 바뀐다.
 *
 * <h2>여기 없는 것</h2>
 * <b>심각도의 "의미 있는" 범위는 서버도 모른다.</b> {@code damaged_part.severity_score} 가
 * 0~1 인지 0~100 인지 확정된 운영 계약이 없어서, {@code severityMax} 는 컬럼이 담을 수 있는
 * 한계({@code 9999.99})일 뿐 "정상값의 상한" 이 아니다. 화면에 그렇게 안내하면 안 된다.
 *
 * @param severityScale        심각도 소수 자릿수. 이보다 잘게 입력하면 400 이다 — 반올림하지 않는다
 * @param severityMaxValue     심각도가 담길 수 있는 최대값({@code NUMERIC(6,2)}). 도메인 상한이 아니다
 * @param severityBoundaryNote 구간 경계 규칙을 사람이 읽을 문장으로
 * @param damageTypes          등록에 쓸 수 있는 활성 손상 유형. 비어 있으면 규칙을 만들 수 없다
 * @param repairMethods        등록에 쓸 수 있는 활성 수리 방식
 * @param referencePercentile  비용 비교 기준 분위수. <b>고정값이라 수정 요청에 담지 않는다</b>
 */
public record RuleFieldBoundsResponse(
        int severityScale,
        BigDecimal severityMaxValue,
        String severityBoundaryNote,
        List<String> damageTypes,
        List<String> repairMethods,
        int priorityMax,
        int referencePercentile,
        BigDecimal severeOverP75MultiplierExclusiveMin,
        BigDecimal severeOverP75MultiplierMax,
        BigDecimal totalDifferenceRatioMax,
        int needsReviewItemCountMin,
        int changeNoteMaxLength) {

    private static final String BOUNDARY_NOTE =
            "구간은 [하한, 상한) 입니다. 상한은 포함하지 않으며, 마지막 구간만 maxInclusive=true 로 "
                    + "닫아 최대값이 어느 규칙에도 잡히지 않는 구멍을 막습니다.";

    public static RuleFieldBoundsResponse of(List<String> damageTypes, List<String> repairMethods) {
        return new RuleFieldBoundsResponse(
                RepairMethodRule.SEVERITY_SCALE,
                RepairMethodRule.MAX_SEVERITY,
                BOUNDARY_NOTE,
                damageTypes,
                repairMethods,
                Short.MAX_VALUE,
                EstimateValidationRule.FIXED_REFERENCE_PERCENTILE,
                BigDecimal.ONE,
                EstimateValidationRule.MAX_MULTIPLIER,
                EstimateValidationRule.MAX_DIFFERENCE_RATIO,
                1,
                EstimateValidationRule.MAX_CHANGE_NOTE_LENGTH);
    }
}
