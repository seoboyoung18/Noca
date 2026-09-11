package com.ssafy.a307.admin.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 새 규칙 버전을 만든다. <b>기존 버전을 고치는 것이 아니다</b> — 행은 불변이다.
 *
 * <h2>여기 있는 네 값은 전부 실제로 판정에 쓰인다</h2>
 * {@code GradeDecider} 가 소비하는 값만 받는다. 저장은 되지만 읽는 코드가 없는 설정을
 * 수정 가능한 것처럼 노출하면, 관리자가 값을 바꾸고 아무 일도 일어나지 않는 것을 보게 된다.
 *
 * <p><b>{@code referencePercentile} 은 그래서 여기 없다.</b> 검증 엔진은
 * {@code repair_cost_stat.cost_p75} 컬럼을 직접 읽고, {@code repair_cost_stat} 에는
 * {@code cost_p25}·{@code cost_median}·{@code cost_p75} 세 개의 <b>고정 분위수 컬럼</b>만 있어
 * 임의 분위수를 계산할 데이터 자체가 없다. 값은 {@code EstimateValidationRule.FIXED_REFERENCE_PERCENTILE}
 * 로 고정되고 응답에는 계속 보인다 — 화면이 "무엇을 기준으로 비교하는지" 는 알아야 하기 때문이다.
 *
 * <h2>정밀도를 반올림하지 않고 거절한다</h2>
 * {@code @Digits} 가 DB 컬럼({@code NUMERIC(5,2)}·{@code NUMERIC(5,4)})의 정수부·소수부를
 * 그대로 강제한다. 넘치는 값을 조용히 반올림하면 관리자가 입력한 임계값과 실제 판정 기준이
 * 달라진다 — 임계값에서 그것은 버그다.
 *
 * @param baseVersion 조회할 때 받은 현재 버전. 그 사이 다른 관리자가 새 버전을 만들었으면
 *                    409 {@code VERSION_CONFLICT} 다. {@code If-Match} 대신 본문으로 받는 이유는
 *                    프로젝트의 다른 관리 API 가 전부 {@code version} 필드를 쓰기 때문이다
 * @param changeNote  선택. <b>이 값만 바뀌면 새 버전을 만들지 않는다</b> — 판정이 달라지지 않는
 *                    변경에 버전 번호를 소모하면 {@code estimate_validation.rule_version} 이
 *                    가리키는 "기준이 바뀐 지점" 이 의미를 잃는다
 */
public record EstimateValidationRuleUpdateRequest(

        @NotNull(message = "P75 초과 배수는 필수입니다.")
        @DecimalMin(value = "1.0", inclusive = false, message = "P75 초과 배수는 1.0보다 커야 합니다.")
        @DecimalMax(value = "999.99", message = "P75 초과 배수는 999.99 이하여야 합니다.")
        @Digits(integer = 3, fraction = 2,
                message = "P75 초과 배수는 정수부 3자리·소수부 2자리까지입니다.")
        BigDecimal severeOverP75Multiplier,

        @NotNull(message = "주의 총액 차이 비율은 필수입니다.")
        @DecimalMin(value = "0.0", message = "주의 총액 차이 비율은 0 이상이어야 합니다.")
        @DecimalMax(value = "9.9999", message = "주의 총액 차이 비율은 9.9999 이하여야 합니다.")
        @Digits(integer = 1, fraction = 4,
                message = "주의 총액 차이 비율은 정수부 1자리·소수부 4자리까지입니다.")
        BigDecimal cautionTotalDifferenceRatio,

        @NotNull(message = "확인 필요 총액 차이 비율은 필수입니다.")
        @DecimalMin(value = "0.0", message = "확인 필요 총액 차이 비율은 0 이상이어야 합니다.")
        @DecimalMax(value = "9.9999", message = "확인 필요 총액 차이 비율은 9.9999 이하여야 합니다.")
        @Digits(integer = 1, fraction = 4,
                message = "확인 필요 총액 차이 비율은 정수부 1자리·소수부 4자리까지입니다.")
        BigDecimal needsReviewTotalDifferenceRatio,

        @NotNull(message = "확인 필요 항목 수는 필수입니다.")
        @Min(value = 1, message = "확인 필요 항목 수는 1 이상이어야 합니다.")
        @Max(value = 32767, message = "확인 필요 항목 수는 32767 이하여야 합니다.")
        Integer needsReviewItemCount,

        @Size(max = 200, message = "변경 사유는 200자 이하여야 합니다.")
        String changeNote,

        @NotNull(message = "baseVersion 은 필수입니다.")
        @Min(value = 1, message = "baseVersion 은 1 이상이어야 합니다.")
        Integer baseVersion) {

    /**
     * 교차 필드 관계. 이것이 깨지면 "주의" 구간이 "확인 필요" 보다 넓어져 등급이 뒤집힌다 —
     * {@code GradeDecider} 가 확인 필요를 먼저 보므로 주의 판정이 영원히 나오지 않는다.
     */
    @AssertTrue(message = "확인 필요 비율은 주의 비율 이상이어야 합니다.")
    public boolean isRatioOrderValid() {
        return cautionTotalDifferenceRatio == null || needsReviewTotalDifferenceRatio == null
                || needsReviewTotalDifferenceRatio.compareTo(cautionTotalDifferenceRatio) >= 0;
    }

    public EstimateValidationRuleUpdateRequest {
        changeNote = changeNote == null || changeNote.isBlank() ? null : changeNote.strip();
    }
}
