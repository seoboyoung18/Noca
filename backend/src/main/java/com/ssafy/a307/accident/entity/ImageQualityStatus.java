package com.ssafy.a307.accident.entity;

/**
 * {@code accident_image.quality_status} — 업로드 이미지 품질 판정 결과.
 * <p>
 * 정본 DDL 의 {@code ck_ai_quality} 가 {@code PASS} · {@code WARN} 둘만 허용한다.
 * <b>실패 상태가 없는 것이 설계다</b> — 품질이 낮아도 업로드는 성공하며, 재촬영 권유는
 * 화면(FE {@code S15P21A307-136})이 {@code WARN} 을 보고 판단한다.
 */
public enum ImageQualityStatus {

    /** 판정 통과, 또는 판정을 수행하지 않음({@code app.image-quality.enabled=false}). */
    PASS,

    /** 해상도·블러 기준 미달. 사유는 {@code quality_reason} 에 담는다. */
    WARN
}
