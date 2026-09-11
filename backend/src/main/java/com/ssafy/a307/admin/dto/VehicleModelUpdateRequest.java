package com.ssafy.a307.admin.dto;

import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * <b>{@code modelId} 는 받지 않는다.</b> 이미 등록된 사용자 차량과 사고 스냅샷이 그 ID 를
 * 가리키고 있어 바꿀 수 없다.
 *
 * @param version 조회할 때 받은 값. 그 사이 남이 바꿨으면 409 {@code VERSION_CONFLICT} 다
 */
public record VehicleModelUpdateRequest(
        @NotBlank(message = "제조사는 필수입니다.")
        @Size(max = 50, message = "제조사는 50자 이하여야 합니다.")
        String manufacturer,

        @NotBlank(message = "차량명은 필수입니다.")
        @Size(max = 100, message = "차량명은 100자 이하여야 합니다.")
        String modelName,

        @NotNull(message = "차종은 필수입니다.")
        VehicleType vehicleType,

        @NotNull(message = "차급은 필수입니다.")
        CarClass carClass,

        @NotNull(message = "version 은 필수입니다.")
        @Min(value = 0, message = "version 은 0 이상이어야 합니다.")
        Long version) {

    public VehicleModelUpdateRequest {
        manufacturer = manufacturer == null ? null : manufacturer.strip();
        modelName = modelName == null ? null : modelName.strip();
    }
}
