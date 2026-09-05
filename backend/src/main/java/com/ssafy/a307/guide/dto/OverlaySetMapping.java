package com.ssafy.a307.guide.dto;

import com.ssafy.a307.vehicle.entity.VehicleType;

/**
 * 차량 유형 → FE 가 쓸 오버레이 실루엣 세트.
 *
 * <p>실루엣은 승용(SEDAN)·SUV <b>2종만</b> 디자인된다. {@code ck_vm_type} 은 네 종류를
 * 허용하므로 {@code VAN}·{@code TRUCK} 은 갈 곳이 없다 → {@code SUV} 로 폴백한다
 * ({@code prompt12.md} 4장 (가)).
 *
 * <p>폴백 규칙을 FE 상수가 아니라 응답에 담는 이유는, 실루엣이 나중에 추가되면
 * <b>FE 배포 없이</b> 매핑만 바꿔 반영하기 위해서다.
 */
public record OverlaySetMapping(
        VehicleType vehicleType,
        String overlaySet
) {
}
