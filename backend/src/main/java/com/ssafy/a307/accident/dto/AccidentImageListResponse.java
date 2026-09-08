package com.ssafy.a307.accident.dto;

import java.util.List;

/**
 * {@code GET /api/accidents/{accidentId}/images} 응답 — Task 143 의 상태 조회면이다.
 * <p>
 * 화면 새로고침이나 세션 복귀 후에도 "무엇이 아직 안 올라갔는지" 를 알 수 있어야 개별 재시도가
 * 성립한다. {@code pending} 이 그 목록의 크기다.
 */
public record AccidentImageListResponse(
        int total,
        int completed,
        int pending,
        int maxCountPerAccident,
        int remainingSlots,
        List<AccidentImageResponse> images
) {

    public static AccidentImageListResponse of(
            List<AccidentImageResponse> images, int maxCountPerAccident, int remainingSlots) {
        int completed = (int) images.stream()
                .filter(i -> i.uploadState() == ImageUploadState.COMPLETED)
                .count();
        return new AccidentImageListResponse(
                images.size(), completed, images.size() - completed,
                maxCountPerAccident, remainingSlots, images);
    }
}
