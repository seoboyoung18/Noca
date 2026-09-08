package com.ssafy.a307.accident.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

/**
 * 등록 차량 선택과 미등록 차량 즉시 입력 중 정확히 하나만 받는다.
 * memberId 는 받지 않는다 — 로그인 회원에서만 얻는다.
 */
public record AccidentCreateRequest(
        Long vehicleId,
        @Valid DirectVehicleInput directVehicle
) {

    /** 기존 Java 호출부와 {@code {"vehicleId": ...}} 계약의 하위 호환성을 유지한다. */
    public AccidentCreateRequest(Long vehicleId) {
        this(vehicleId, null);
    }

    @AssertTrue(message = "vehicleId와 directVehicle 중 정확히 하나가 필요합니다.")
    public boolean isVehicleInputValid() {
        return (vehicleId == null) != (directVehicle == null);
    }
}
