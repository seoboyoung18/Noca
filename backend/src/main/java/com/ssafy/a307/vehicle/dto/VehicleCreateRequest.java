package com.ssafy.a307.vehicle.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 등록 요청은 modelId + modelYear 두 개뿐이다.
 * 제조사·유형·차급은 모델이 결정하므로 서버로 보내지 않는다.
 * memberId 도 받지 않는다 — 로그인 회원에서만 얻는다.
 */
public record VehicleCreateRequest(

        @NotNull(message = "필수입니다.")
        Long modelId,

        @NotNull(message = "필수입니다.")
        @Min(value = 1980, message = "1980 이상이어야 합니다.")
        @Max(value = 2100, message = "2100 이하여야 합니다.")
        Integer modelYear
) {
}
