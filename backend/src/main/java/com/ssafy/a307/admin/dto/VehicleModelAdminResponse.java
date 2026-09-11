package com.ssafy.a307.admin.dto;

import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleModel;
import com.ssafy.a307.vehicle.entity.VehicleType;

/**
 * @param carClass {@code "CityCar"}·{@code "Compact"}·{@code "Mid-size"}·{@code "Full-size"} —
 *                 enum 이름이 아니라 코드값이 나간다(하이픈 포함)
 * @param version  다음 수정 요청에 그대로 실어야 한다. 빠지면 409 다
 */
public record VehicleModelAdminResponse(
        Long modelId,
        String manufacturer,
        String modelName,
        VehicleType vehicleType,
        CarClass carClass,
        boolean active,
        long version) {

    public static VehicleModelAdminResponse from(VehicleModel model) {
        return new VehicleModelAdminResponse(
                model.getModelId(), model.getManufacturer(), model.getModelName(),
                model.getVehicleType(), model.getCarClass(), model.isActive(), model.getVersion());
    }
}
