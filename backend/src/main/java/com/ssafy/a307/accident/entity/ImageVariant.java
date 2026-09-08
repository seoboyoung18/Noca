package com.ssafy.a307.accident.entity;

import java.util.Locale;

/**
 * {@code accident_image_asset.variant} — 한 이미지의 변형본 종류.
 * <p>
 * 정본 DDL 의 {@code ck_aia_var} 가 허용하는 네 값과 <b>정확히 같아야 한다.</b>
 * 값을 늘리려면 DDL · H2 스키마 · 이 enum 을 함께 고친다.
 * <p>
 * <b>{@code OVERLAY} 를 추가하지 않는다.</b> 오버레이는 {@code (job × image)} 마다 생기므로
 * {@code uk_aia UNIQUE (image_id, variant)} 를 위반한다. 정본 DDL 주석이 재분석 시
 * 실측으로 확인했다고 적고 있으며, 그래서 {@code analysis_image_result.s3_key_overlay} 로 이관되었다.
 */
public enum ImageVariant {

    /** 사용자가 올린 원본. 손대지 않고 그대로 보관한다 — EXIF 도 남는다. */
    ORIGINAL,

    /** 분석용 리사이즈본. 긴 변 기준 축소, EXIF 제거. */
    RESIZED,

    /** 목록·미리보기용 썸네일. 긴 변 기준 축소, EXIF 제거. */
    THUMBNAIL,

    /**
     * 번호판·얼굴 블러본. <b>이 작업(Story 137·141)에서 만들지 않는다</b> —
     * {@code S15P21A307-226~228 · 386} 의 범위다. 값만 미리 열어 둔다.
     */
    BLURRED;

    /** S3 키의 오브젝트 이름. DB 에는 대문자 상수명을 그대로 저장하고 키에서만 소문자를 쓴다. */
    public String objectName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
