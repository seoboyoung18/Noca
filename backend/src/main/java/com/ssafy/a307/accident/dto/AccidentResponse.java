package com.ssafy.a307.accident.dto;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.vehicle.dto.VehicleResponse;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;

import java.time.Instant;

/**
 * 차량 필드 이름을 {@link VehicleResponse} 와 똑같이 맞춰 FE 가 같은 타입을 재사용하게 한다.
 * 소유자 검사로 이미 읽은 차량·모델을 그대로 쓰므로 추가 조회가 없다.
 */
public record AccidentResponse(
        Long accidentId,
        Long vehicleId,
        Long modelId,
        String manufacturer,
        String modelName,
        VehicleType vehicleType,
        CarClass carClass,
        Integer modelYear,
        Instant createdAt
) {

    public static AccidentResponse from(Accident accident) {
        // 차량 → DTO 변환은 VehicleResponse 가 이미 하고 있다. 같은 매핑을 두 벌 두지 않는다.
        VehicleResponse vehicle = VehicleResponse.from(accident.getVehicle());

        return new AccidentResponse(
                accident.getAccidentId(),
                vehicle.vehicleId(),
                vehicle.modelId(),
                vehicle.manufacturer(),
                vehicle.modelName(),
                vehicle.vehicleType(),
                vehicle.carClass(),
                vehicle.modelYear(),
                accident.getCreatedAt()
        );
    }
}
