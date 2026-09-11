package com.ssafy.a307.admin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * <b>심각도 상·하한에 0~1 이나 0~100 같은 범위를 강제하지 않는다.</b>
 * {@code damaged_part.severity_score} 는 {@code NUMERIC(6,2)} 이고 그 값을 쓰는 코드가 아직
 * 이 저장소에 없어서, 의미 있는 범위를 단정할 근거가 없다. 컬럼이 담을 수 있는 한계
 * ({@code 0 ~ 9999.99})만 막고 나머지는 관리자가 정한다.
 *
 * @param damageType   {@code Scratched}·{@code Separated}·{@code Crushed}·{@code Breakage} 중
 *                     <b>활성 상태인 것</b>. 값 목록은 {@code GET /api/admin/repair-codes} 가 준다
 * @param partCode     생략하면 손상 유형 전체에 적용되는 기본 규칙. 값을 주면 그 부품 전용 예외 규칙
 * @param repairMethod {@code exchange}·{@code sheet_metal}·{@code coating}·{@code repair} 중 활성인 것
 */
public record RepairMethodRuleCreateRequest(
        @NotBlank(message = "손상 유형은 필수입니다.")
        @Size(max = 20, message = "손상 유형은 20자 이하여야 합니다.")
        String damageType,

        @Size(max = 50, message = "부품 코드는 50자 이하여야 합니다.")
        String partCode,

        @NotNull(message = "심각도 하한은 필수입니다.")
        @DecimalMin(value = "0.00", message = "심각도 하한은 0 이상이어야 합니다.")
        @DecimalMax(value = "9999.99", message = "심각도 하한은 9999.99 이하여야 합니다.")
        @Digits(integer = 4, fraction = 2,
                message = "심각도 하한은 정수부 4자리·소수부 2자리까지입니다.")
        BigDecimal severityMin,

        @NotNull(message = "심각도 상한은 필수입니다.")
        @DecimalMin(value = "0.00", message = "심각도 상한은 0 이상이어야 합니다.")
        @DecimalMax(value = "9999.99", message = "심각도 상한은 9999.99 이하여야 합니다.")
        @Digits(integer = 4, fraction = 2,
                message = "심각도 상한은 정수부 4자리·소수부 2자리까지입니다.")
        BigDecimal severityMax,

        /** 마지막 구간만 {@code true} 로 닫아 최대값이 어느 규칙에도 안 잡히는 구멍을 막는다. */
        boolean maxInclusive,

        @NotBlank(message = "수리 방식은 필수입니다.")
        @Size(max = 20, message = "수리 방식은 20자 이하여야 합니다.")
        String repairMethod,

        @NotNull(message = "우선순위는 필수입니다.")
        @Min(value = 0, message = "우선순위는 0 이상이어야 합니다.")
        @Max(value = 32767, message = "우선순위는 32767 이하여야 합니다.")
        Integer priority) {

    public RepairMethodRuleCreateRequest {
        damageType = damageType == null ? null : damageType.strip();
        repairMethod = repairMethod == null ? null : repairMethod.strip();
        partCode = partCode == null || partCode.isBlank() ? null : partCode.strip();
    }
}
