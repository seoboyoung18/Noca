package com.ssafy.a307.accident.dto;

import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.entity.AccidentImageAsset;
import com.ssafy.a307.accident.entity.ImageVariant;

import java.util.List;

/**
 * asset 한 행. {@code s3Key} 를 그대로 노출하는 이유는 DB 에 경로·메타만 저장한다는
 * 요구사항 21행 비고를 화면에서도 그대로 쓰기 위해서다 — 조회 URL 은 별도 presigned 다운로드
 * API 로 발급한다(이 작업 범위 밖).
 * <p>
 * {@code width}·{@code height}·{@code fileSize} 는 null 일 수 있다. 정본이 nullable 이고,
 * SMALLINT 에 담을 수 없는 큰 변은 저장하지 않기 때문이다.
 */
public record AccidentImageAssetResponse(
        ImageVariant variant,
        String s3Key,
        Integer width,
        Integer height,
        Integer fileSize
) {

    public static AccidentImageAssetResponse from(AccidentImageAsset asset) {
        return new AccidentImageAssetResponse(
                asset.getVariant(),
                asset.getS3Key(),
                asset.getWidth() == null ? null : asset.getWidth().intValue(),
                asset.getHeight() == null ? null : asset.getHeight().intValue(),
                asset.getFileSize());
    }

    /**
     * 화면으로 내보낼 asset 만 고른다. <b>{@link ImageVariant#ORIGINAL} 은 뺀다.</b>
     *
     * <p>원본은 사용자가 올린 바이트 그대로라 <b>EXIF 가 남아 있다</b>
     * ({@code ImageVariant.ORIGINAL} Javadoc). 촬영 위치(GPS)·기기 정보가 그대로 들어 있고,
     * 사진에는 번호판과 얼굴이 찍힌다. API 명세서 27행도 "화면에 내보내는 이미지는
     * {@code variant='BLURRED'} 만" 이라고 정해 두었다. 그 {@code BLURRED} 는 아직 만들어지지
     * 않으므로({@code S15P21A307-226~228}), 그때까지는 EXIF 를 제거한 {@code RESIZED}·
     * {@code THUMBNAIL} 만 내보낸다 — {@code AccidentImagePreprocessor} 가 전처리본에서
     * EXIF 를 통째로 지운다.
     *
     * <p><b>저장은 그대로 한다.</b> {@code accident_image_asset} 의 {@code ORIGINAL} 행은 계속
     * 만들어진다. 분석 파이프라인이 원본을 읽어야 하기 때문이고, 여기서 거르는 것은
     * <b>응답에 실을지</b>뿐이다.
     *
     * <p>배열 자체는 유지한다. 원소만 빠지므로 프론트엔드의 응답 타입은 바뀌지 않는다.
     */
    public static List<AccidentImageAssetResponse> exposed(AccidentImage image) {
        return image.getAssets().stream()
                .filter(asset -> asset.getVariant() != ImageVariant.ORIGINAL)
                .map(AccidentImageAssetResponse::from)
                .toList();
    }
}
