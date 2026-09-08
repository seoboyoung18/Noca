package com.ssafy.a307.accident.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 사고 접수 중 차량 마스터를 정확히 찾기 위한 즉시 입력 값. */
public record DirectVehicleInput(
        @NotBlank(message = "필수입니다.")
        @Size(max = 50, message = "50자 이하여야 합니다.")
        String manufacturer,

        @NotBlank(message = "필수입니다.")
        @Size(max = 100, message = "100자 이하여야 합니다.")
        String modelName,

        @NotNull(message = "필수입니다.")
        @Min(value = 1980, message = "1980 이상이어야 합니다.")
        @Max(value = 2100, message = "2100 이하여야 합니다.")
        Integer modelYear
) {

    public DirectVehicleInput {
        manufacturer = manufacturer == null ? null : manufacturer.strip();
        modelName = modelName == null ? null : modelName.strip();
    }
}
