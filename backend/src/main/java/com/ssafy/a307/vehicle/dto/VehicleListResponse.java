package com.ssafy.a307.vehicle.dto;

import java.util.List;

/** 공통 규약이 {@code data} 에 객체를 요구하므로 배열을 그대로 넣지 않고 감싼다. */
public record VehicleListResponse(List<VehicleResponse> vehicles) {
}
