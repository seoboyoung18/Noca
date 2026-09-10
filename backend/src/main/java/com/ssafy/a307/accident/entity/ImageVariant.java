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
     * 번호판·얼굴 블러본. <b>이 값을 만드는 코드는 없고, 만들 예정도 없다.</b>
     * <p>
     * {@code S15P21A307-226~228 · 386} 이 담당이었으나 2026-09-10 에 MVP 범위 밖으로 정리됐다.
     * 검출을 LLM 으로 하기로 정해 "모델이 블러본을 주고 서버는 저장만 한다" 는 전제가 깨졌고,
     * 유사 사례 데이터셋에는 가릴 대상 자체가 없다 — 이미 비식별된 한 종류만 제공된다.
     * <p>
     * <b>그래도 값을 지우지 않는다.</b> 정본 DDL 의 {@code ck_aia_var} 가 네 값을 허용하므로
     * enum 에서 빼면 스키마와 어긋난다. 저장 경로({@code store(variant)})는
     * {@code S15P21A307-427} 로 열려 있어, 블러를 다시 하게 되면 만드는 쪽만 붙이면 된다.
     */
    BLURRED;

    /** S3 키의 오브젝트 이름. DB 에는 대문자 상수명을 그대로 저장하고 키에서만 소문자를 쓴다. */
    public String objectName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
