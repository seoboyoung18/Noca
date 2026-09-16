package com.ssafy.a307.guide.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * {@code GET /api/guides/shooting} 응답.
 *
 * <p>{@code recommendedCount} 는 <b>1</b> 이다 — 2026-09-16 에 촬영 컷을 10장에서
 * 파손 부위 1장으로 줄였다(팀 결정). 업로드 <b>상한</b>이 아니라 <b>권장</b> 장수이며,
 * 상한은 {@code app.accident-image.max-count-per-accident} 가 따로 가진다.
 *
 * <p><b>{@code acceptedAngleCodes} 는 {@code shots} 와 분리돼 있다.</b> 가이드가 1컷으로
 * 줄어도 업로드가 받아 주는 각도 어휘는 9종을 유지해야 하기 때문이다 —
 * {@code repair_case_image.angle_tag} 와 값 집합이 어긋나면 나중에 사용자 사진과 학습
 * 사례를 각도로 비교할 수 없고, 이미 {@code FRONT} 로 태그된 업로드가 400 이 된다.
 * 비어 있으면 {@code shots} 의 코드로 대체된다({@code ShootingAngleCodes}).
 *
 * <p>유형별 필터 파라미터를 두지 않고 전체를 한 번에 내려준다.
 * {@code GET /api/vehicle-models} 에서 필터를 붙이지 않기로 한 것과 같은 근거다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShootingGuideResponse(
        int recommendedCount,
        List<String> acceptedAngleCodes,
        List<OverlaySetMapping> overlaySets,
        List<ShootingShot> shots
) {
}
