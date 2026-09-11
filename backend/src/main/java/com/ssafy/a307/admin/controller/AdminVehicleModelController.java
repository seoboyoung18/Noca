package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.admin.dto.VehicleModelAdminResponse;
import com.ssafy.a307.admin.dto.VehicleModelCreateRequest;
import com.ssafy.a307.admin.dto.VehicleModelUpdateRequest;
import com.ssafy.a307.admin.service.AdminVehicleModelService;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.vehicle.entity.VehicleType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 차량 모델 마스터 관리. 물리 삭제 API 는 없다 — 참조가 끊기면 과거 차량과 사고 이력의 차종을 말할 수 없다.
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 공통 오류 봉투의 403 이다.
 *
 * <p><b>관리자 ID 를 요청으로 받지 않는다.</b> 어떤 DTO 에도 actor 필드가 없고, 감사 로그의
 * 행위자는 {@code CurrentMemberProvider} 가 세션에서 꺼낸다.
 */
@RestController
@RequestMapping("/api/admin/vehicle-models")
@RequiredArgsConstructor
public class AdminVehicleModelController {

    private final AdminVehicleModelService service;

    /**
     * {@code active} 를 생략하면 비활성 모델까지 전부 나온다 — 공개 목록과 다른 점이다.
     *
     * @param vehicleType {@code SEDAN}·{@code SUV}·{@code VAN}·{@code TRUCK}. 다른 값은 400
     * @param carClass    응답과 같은 표기 {@code CityCar}·{@code Compact}·{@code Mid-size}·{@code Full-size}.
     *                    다른 값은 400
     */
    @GetMapping
    public ApiResponse<AdminPageResponse<VehicleModelAdminResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String manufacturer,
            @RequestParam(required = false) VehicleType vehicleType,
            @RequestParam(required = false) String carClass,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {

        return ApiResponse.of(service.search(
                keyword, manufacturer, vehicleType, carClass, active, page, size, sort));
    }

    @GetMapping("/{modelId}")
    public ApiResponse<VehicleModelAdminResponse> findOne(@PathVariable Long modelId) {
        return ApiResponse.of(service.findOne(modelId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<VehicleModelAdminResponse>> create(
            @Valid @RequestBody VehicleModelCreateRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.create(request)));
    }

    @PatchMapping("/{modelId}")
    public ApiResponse<VehicleModelAdminResponse> update(
            @PathVariable Long modelId, @Valid @RequestBody VehicleModelUpdateRequest request) {

        return ApiResponse.of(service.update(modelId, request));
    }

    /** 비활성화와 재활성화가 같은 엔드포인트다. 감사 로그에서만 갈린다. */
    @PatchMapping("/{modelId}/status")
    public ApiResponse<VehicleModelAdminResponse> changeStatus(
            @PathVariable Long modelId, @Valid @RequestBody StatusUpdateRequest request) {

        return ApiResponse.of(service.changeStatus(modelId, request));
    }
}
