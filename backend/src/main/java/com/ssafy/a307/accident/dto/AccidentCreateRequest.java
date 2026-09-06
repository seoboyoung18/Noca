package com.ssafy.a307.accident.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 사고 접수 요청은 {@code vehicleId} 하나뿐이다.
 * <p>
 * 미등록 차량은 FE 가 {@code POST /api/vehicles} 로 먼저 만들고 그 id 를 보낸다.
 * memberId 는 받지 않는다 — 로그인 회원에서만 얻는다.
 */
public record AccidentCreateRequest(

        @NotNull(message = "필수입니다.")
        Long vehicleId
) {
}
