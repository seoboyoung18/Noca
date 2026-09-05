package com.ssafy.a307.vehicle.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.vehicle.dto.VehicleModelListResponse;
import com.ssafy.a307.vehicle.service.VehicleModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicle-models")
@RequiredArgsConstructor
public class VehicleModelController {

    private final VehicleModelService vehicleModelService;

    /** 필터 파라미터·페이지네이션 없음. 활성 모델 전체를 내려준다. */
    @GetMapping
    public ApiResponse<VehicleModelListResponse> findAll() {
        return ApiResponse.of(new VehicleModelListResponse(vehicleModelService.findAllActive()));
    }
}
