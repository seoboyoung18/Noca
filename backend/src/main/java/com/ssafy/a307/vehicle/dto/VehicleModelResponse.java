package com.ssafy.a307.vehicle.dto;

import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleModel;
import com.ssafy.a307.vehicle.entity.VehicleType;

/** {@code isActive} 는 넣지 않는다 — true 인 것만 내려주므로 의미가 없다. */
public record VehicleModelResponse(
        Long modelId,
        String manufacturer,
        String modelName,
        VehicleType vehicleType,
        CarClass carClass
) {

    public static VehicleModelResponse from(VehicleModel model) {
        return new VehicleModelResponse(
                model.getModelId(),
                model.getManufacturer(),
                model.getModelName(),
                model.getVehicleType(),
                model.getCarClass()
        );
    }
}
