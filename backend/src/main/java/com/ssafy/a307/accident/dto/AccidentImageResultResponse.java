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
        String angleCode,
        ImageProcessingStatus status,
        ImageQualityStatus qualityStatus,
        String qualityReason,
        String failureCode,
        String failureMessage,
        List<AccidentImageAssetResponse> assets
) {

    public static AccidentImageResultResponse completed(
            AccidentImage image, ImageProcessingStatus status,
            List<AccidentImageAssetResponse> assets) {
        return new AccidentImageResultResponse(
                image.getImageId(),
                image.getOriginalFilename(),
                image.getAngleCode(),
                status,
                image.getQualityStatus(),
                image.getQualityReason(),
                null,
                null,
                assets);
    }

    /**
     * 실패한 한 장. <b>{@code angleCode} 를 채우지 않는다</b> — 실패 경로는 오케스트레이터가
     * 파일명만 들고 부르고, 각도를 읽으려고 엔티티를 다시 조회할 이유가 없다. 화면은 실패 항목에
     * 각도 배지를 그리지 않는다.
     */
    public static AccidentImageResultResponse failed(
            Long imageId, String originalFilename, String failureCode, String failureMessage) {
        return new AccidentImageResultResponse(
                imageId, originalFilename, null, ImageProcessingStatus.FAILED,
                null, null, failureCode, failureMessage, List.of());
    }
}
