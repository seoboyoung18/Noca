package com.ssafy.a307.accident.dto;

import com.ssafy.a307.accident.entity.AccidentImageAsset;
import com.ssafy.a307.accident.entity.ImageVariant;

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
}
