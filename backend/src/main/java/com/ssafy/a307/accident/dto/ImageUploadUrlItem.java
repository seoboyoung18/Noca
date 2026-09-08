package com.ssafy.a307.accident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 발급 요청의 파일 한 건. <b>여기 담긴 값은 전부 클라이언트 신고값이다</b> —
 * presigned 직접 업로드에서 서버는 원본 바이트를 받지 않는다. 그래서 이 값만으로 통과시키지 않고
 * 완료 통보 시점에 실제 오브젝트를 다시 검증한다(제약 3단 방어).
 *
 * @param angleCode 촬영 각도. <b>저장되지 않는다</b>(answer25 D1) — 정본 {@code accident_image} 에
 *                  컬럼이 없다. 값은 촬영 가이드의 9종으로 검증하고 응답에 그대로 되돌려주므로,
 *                  화면은 imageId ↔ 각도 대응을 클라이언트에서 유지해야 한다
 */
public record ImageUploadUrlItem(
        @NotBlank(message = "원본 파일명은 필수입니다.")
        @Size(max = 255, message = "원본 파일명은 255자 이하여야 합니다.")
        String originalFilename,

        @NotBlank(message = "Content-Type은 필수입니다.")
        String contentType,

        @NotNull(message = "파일 크기는 필수입니다.")
        @Positive(message = "파일 크기는 0보다 커야 합니다.")
        Long size,

        String angleCode
) {
}
