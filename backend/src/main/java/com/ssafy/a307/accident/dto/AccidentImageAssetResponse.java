package com.ssafy.a307.accident.dto;

import com.ssafy.a307.accident.entity.AccidentImageAsset;
import com.ssafy.a307.accident.entity.ImageVariant;

import java.time.Instant;

/**
 * 화면에 내보내는 이미지 변형본 하나 — <b>브라우저가 바로 띄울 수 있는 형태</b>다.
 *
 * <p><b>{@code s3Key} 를 더 이상 노출하지 않는다 — 정정.</b> 이 record 는 원래 키만 돌려주고
 * "조회 URL 은 별도 API 로 발급한다(이 작업 범위 밖)" 고 적어 두었는데, 그 API 가 만들어지지
 * 않아 <b>업로드는 되는데 다시 볼 수 없는</b> 상태로 남아 있었다. {@code S15P21A307-137} 이
 * 조회용 presigned GET URL 을 여기에 직접 담게 하면서 키를 노출할 이유가 사라졌다 —
 * 브라우저는 키로 아무것도 하지 못하고, 내부 저장 구조만 밖으로 새 나간다.
 *
 * <p><b>{@link ImageVariant#ORIGINAL} 은 여기 오지 않는다.</b> 원본은 사용자가 올린 바이트
 * 그대로라 EXIF(GPS·기기 정보)가 남아 있고 staging 버킷에서 7일 뒤 사라진다
 * ({@code S15P21A307-388}). 고르는 쪽({@code AccidentImageDownloadUrls})과 서명하는 쪽
 * ({@code S3AccidentImageStorage#createPresignedDownloadUrl})이 각각 막아 방어선이 둘이다.
 *
 * <p>API 명세서 27행은 "화면에 내보내는 이미지는 {@code variant='BLURRED'} 만" 이라고 정해
 * 두었다. <b>그 {@code BLURRED} 는 만들지 않기로 확정됐다</b> — {@code S15P21A307-226~228} 이
 * 2026-09-10 에 MVP 범위 밖으로 정리됐다. 다만 명세서가 막으려던 것은 원본이 EXIF 를 달고
 * 화면으로 나가는 것이고, 그건 블러 없이 이미 충족된다 — {@code AccidentImagePreprocessor} 가
 * 전처리본에서 EXIF 를 통째로 지우고, 위 두 방어선이 원본을 응답에서 뺀다. 그래서 여기에는
 * {@code RESIZED}·{@code THUMBNAIL} 만 온다.
 *
 * @param variant   {@code RESIZED} 또는 {@code THUMBNAIL}
 * @param url       조회용 presigned GET URL. <b>저장소가 구성되지 않았거나 서명에 실패하면
 *                  {@code null}</b> — 그때도 나머지 메타는 그대로 온다
 * @param expiresAt {@code url} 의 만료 시각. {@code url} 이 {@code null} 이면 함께 {@code null} 이다.
 *                  화면을 오래 열어 두었다면 만료 전에 목록을 다시 부르면 새 URL 이 온다
 * @param width     가로 픽셀. 전처리로 얻지 못했거나 SMALLINT 를 넘으면 {@code null}
 * @param height    세로 픽셀. 같은 이유로 {@code null} 가능
 * @param fileSize  바이트. 같은 이유로 {@code null} 가능
 */
public record AccidentImageAssetResponse(
        ImageVariant variant,
        String url,
        Instant expiresAt,
        Integer width,
        Integer height,
        Integer fileSize
) {

    public static AccidentImageAssetResponse of(AccidentImageAsset asset, String url, Instant expiresAt) {
        return new AccidentImageAssetResponse(
                asset.getVariant(),
                url,
                expiresAt,
                asset.getWidth() == null ? null : asset.getWidth().intValue(),
                asset.getHeight() == null ? null : asset.getHeight().intValue(),
                asset.getFileSize());
    }

    /**
     * 저장소가 구성되지 않았거나 서명이 실패했을 때. 메타는 살리고 URL 자리만 비운다 —
     * 목록 조회가 통째로 죽는 것보다 이미지 한 칸이 비는 편이 낫다.
     */
    public static AccidentImageAssetResponse withoutUrl(AccidentImageAsset asset) {
        return of(asset, null, null);
    }
}
