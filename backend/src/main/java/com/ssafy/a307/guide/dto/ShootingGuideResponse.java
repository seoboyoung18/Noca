package com.ssafy.a307.guide.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * {@code GET /api/guides/shooting} 응답.
 *
 * <p>{@code recommendedCount} 는 8방향 + 파손 부위 근접 2장 = <b>10</b> 이다.
 * 업로드 <b>상한</b>이 아니라 <b>권장</b> 장수다 — 상한은 요구사항 21행(20장)과
 * API 명세서 34행(10장)이 어긋나 있어 확정되지 않았다 (answer12.md 5장).
 *
 * <p>유형별 필터 파라미터를 두지 않고 전체를 한 번에 내려준다.
 * {@code GET /api/vehicle-models} 에서 필터를 붙이지 않기로 한 것과 같은 근거다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShootingGuideResponse(
        int recommendedCount,
        List<OverlaySetMapping> overlaySets,
        List<ShootingShot> shots
) {
}
