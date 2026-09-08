package com.ssafy.a307.accident.dto;

import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.entity.ImageQualityStatus;

import java.time.Instant;
import java.util.List;

/** 목록·상태 조회의 이미지 한 건. */
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
                image.getAssets().stream().map(AccidentImageAssetResponse::from).toList());
    }
}
