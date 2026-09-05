package com.ssafy.a307.vehicle.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;

/**
 * 연식만 수정할 수 있다. 차급이 바뀌면 기존 견적의 {@code ref_condition} 과 어긋나므로
 * modelId 변경은 허용하지 않는다. 들어오면 조용히 무시하지 않고 400 으로 거절한다.
 */
public record VehicleUpdateRequest(

        @NotNull(message = "필수입니다.")
        @Min(value = 1980, message = "1980 이상이어야 합니다.")
        @Max(value = 2100, message = "2100 이하여야 합니다.")
        Integer modelYear,

        @Null(message = "차량 모델은 변경할 수 없습니다.")
        Long modelId
) {
}
