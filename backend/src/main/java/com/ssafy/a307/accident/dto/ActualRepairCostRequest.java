package com.ssafy.a307.accident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ActualRepairCostRequest(
        @NotNull(message = "실제 수리비는 필수입니다.")
        @Positive(message = "실제 수리비는 0보다 커야 합니다.")
        Integer actualRepairCost,

        @NotNull(message = "수리 완료일은 필수입니다.")
        @PastOrPresent(message = "수리 완료일은 미래일 수 없습니다.")
        LocalDate repairCompletedDate,

        @NotBlank(message = "정비소명은 필수입니다.")
        @Size(max = 100, message = "정비소명은 100자 이하여야 합니다.")
        String repairShopName
) {
}
