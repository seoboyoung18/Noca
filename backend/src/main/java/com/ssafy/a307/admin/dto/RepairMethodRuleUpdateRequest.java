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
 * <b>적용 대상({@code damageType}·{@code partCode})은 바꿀 수 없다.</b> 그것을 바꾸면 같은
 * 규칙의 수정이 아니라 다른 규칙이다 — 기존 규칙을 끄고 새로 등록해야 이력이 정확해진다.
 */
public record RepairMethodRuleUpdateRequest(
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

        boolean maxInclusive,

        @NotBlank(message = "수리 방식은 필수입니다.")
        @Size(max = 20, message = "수리 방식은 20자 이하여야 합니다.")
        String repairMethod,

        @NotNull(message = "우선순위는 필수입니다.")
        @Min(value = 0, message = "우선순위는 0 이상이어야 합니다.")
        @Max(value = 32767, message = "우선순위는 32767 이하여야 합니다.")
        Integer priority,

        @NotNull(message = "version 은 필수입니다.")
        @Min(value = 0, message = "version 은 0 이상이어야 합니다.")
        Long version) {

    public RepairMethodRuleUpdateRequest {
        repairMethod = repairMethod == null ? null : repairMethod.strip();
    }
}
