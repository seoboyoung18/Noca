package com.ssafy.a307.analysis.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 부위 확정 재분석 요청 (S15P21A307-570).
 *
 * <p><b>한 부위만 받는다.</b> 부품을 찾지 못한 손상 전부에 이 부위를 붙인다. 검출마다 다른 부위를
 * 고르는 것은 1차 범위가 아니다 — 부품을 못 찾는 경우는 대개 한 부위를 가까이 찍은 사진이다.
 *
 * @param partCode 사용자가 고른 부위. 활성 AI 라벨 부위여야 하고, 서비스가 마스터와 대조한다
 */
public record ResolvePartRequest(
        @NotBlank(message = "partCode 는 필수입니다.")
        String partCode) {
}
