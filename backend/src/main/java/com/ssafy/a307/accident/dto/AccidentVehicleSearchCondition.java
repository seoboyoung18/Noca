package com.ssafy.a307.accident.dto;

import com.ssafy.a307.vehicle.entity.CarClass;

/** 유사 사례 검색 서비스가 사고 스냅샷에서 받아야 하는 차량 조건. */
public record AccidentVehicleSearchCondition(
        Long accidentId,
        Long modelId,
        String manufacturer,
        String modelName,
        CarClass carClass,
        Integer modelYear
) {
}
