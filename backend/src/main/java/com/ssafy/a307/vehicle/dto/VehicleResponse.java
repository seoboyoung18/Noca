package com.ssafy.a307.vehicle.dto;

import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.Vehicle;
import com.ssafy.a307.vehicle.entity.VehicleModel;
import com.ssafy.a307.vehicle.entity.VehicleType;

/**
 * 등록·수정·목록이 모두 이 형태를 쓴다. FE 가 같은 타입을 재사용할 수 있게 맞춘다.
 */
public record VehicleResponse(
        Long vehicleId,
        Long modelId,
        String manufacturer,
        String modelName,
        VehicleType vehicleType,
        CarClass carClass,
        Integer modelYear
) {

    public static VehicleResponse from(Vehicle vehicle) {
        VehicleModel model = vehicle.getModel();
        return new VehicleResponse(
                vehicle.getVehicleId(),
                model.getModelId(),
                model.getManufacturer(),
                model.getModelName(),
                model.getVehicleType(),
                model.getCarClass(),
                vehicle.getModelYear().intValue()
        );
    }
}
