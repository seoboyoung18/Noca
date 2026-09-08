package com.ssafy.a307.accident.dto;

import java.util.List;

/**
 * 발급 응답. {@code remainingSlots} 는 이번 발급까지 반영한 <b>남은 장수</b>다 —
 * 화면이 "몇 장 더 올릴 수 있는지" 를 서버 상한과 어긋나지 않게 보여줄 수 있다.
 */
public record ImageUploadUrlResponse(
        int issuedCount,
        int maxCountPerAccident,
        int remainingSlots,
        List<IssuedUploadUrl> files
) {
}
