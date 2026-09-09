package com.ssafy.a307.accident.dto;

import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.entity.ImageQualityStatus;

import java.time.Instant;
import java.util.List;

/**
 * 목록·상태 조회의 이미지 한 건.
 *
 * <p>{@code assets} 에는 {@code ORIGINAL} 이 들어가지 않는다 — 원본에는 EXIF 가 남는다.
 * 노출 정책과 근거는 {@link AccidentImageAssetResponse#exposed} 에 있다.
 */
public record AccidentImageResponse(
        Long imageId,
        String originalFilename,
        ImageUploadState uploadState,
        ImageQualityStatus qualityStatus,
        String qualityReason,
        Instant createdAt,
        List<AccidentImageAssetResponse> assets
) {

    public static AccidentImageResponse from(AccidentImage image) {
        return new AccidentImageResponse(
                image.getImageId(),
                image.getOriginalFilename(),
                image.isUploadCompleted() ? ImageUploadState.COMPLETED : ImageUploadState.PENDING,
                image.getQualityStatus(),
                image.getQualityReason(),
                image.getCreatedAt(),
                AccidentImageAssetResponse.exposed(image));
    }
}
