package com.ssafy.a307.admin.dto;

import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 새 모델은 활성으로 만들어진다 — 등록하자마자 쓰라고 만드는 것이다. {@code active} 를 받지 않는다. */
public record VehicleModelCreateRequest(
        @NotBlank(message = "제조사는 필수입니다.")
        @Size(max = 50, message = "제조사는 50자 이하여야 합니다.")
        String manufacturer,

        @NotBlank(message = "차량명은 필수입니다.")
        @Size(max = 100, message = "차량명은 100자 이하여야 합니다.")
        String modelName,

        @NotNull(message = "차종은 필수입니다.")
        VehicleType vehicleType,

        @NotNull(message = "차급은 필수입니다.")
        CarClass carClass) {

    public VehicleModelCreateRequest {
        manufacturer = manufacturer == null ? null : manufacturer.strip();
        modelName = modelName == null ? null : modelName.strip();
    }
}
