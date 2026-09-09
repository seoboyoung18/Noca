package com.ssafy.a307.accident.dto;

import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.entity.ImageQualityStatus;

import java.util.List;

/**
 * 완료 통보의 이미지별 결과.
 *
 * <p>{@code assets} 에는 {@code ORIGINAL} 이 들어가지 않는다 — 원본에는 EXIF 가 남는다.
 * 노출 정책과 근거는 {@link AccidentImageAssetResponse#exposed} 에 있다.
 *
 * @param failureCode    실패 사유 분류({@code AccidentImageValidationException.Reason} 이름).
 *                       성공이면 null
 * @param failureMessage 위반한 제약과 실제 값이 담긴 사람용 메시지. 성공이면 null
 */
public record AccidentImageResultResponse(
        Long imageId,
        String originalFilename,
        ImageProcessingStatus status,
        ImageQualityStatus qualityStatus,
        String qualityReason,
        String failureCode,
        String failureMessage,
        List<AccidentImageAssetResponse> assets
) {

    public static AccidentImageResultResponse completed(
            AccidentImage image, ImageProcessingStatus status) {
        return new AccidentImageResultResponse(
                image.getImageId(),
                image.getOriginalFilename(),
                status,
                image.getQualityStatus(),
                image.getQualityReason(),
                null,
                null,
                AccidentImageAssetResponse.exposed(image));
    }

    public static AccidentImageResultResponse failed(
            Long imageId, String originalFilename, String failureCode, String failureMessage) {
        return new AccidentImageResultResponse(
                imageId, originalFilename, ImageProcessingStatus.FAILED,
                null, null, failureCode, failureMessage, List.of());
    }
}
