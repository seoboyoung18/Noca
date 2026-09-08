package com.ssafy.a307.accident.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 완료 통보의 파일 한 건.
 *
 * @param size 발급 때 신고한 크기. <b>선택</b>이다 — 보내면 저장소의 실제 크기와 대조해
 *             다르면 그 이미지를 실패로 처리하고 오브젝트를 지운다(3단 재검증의 크기 일치).
 *             생략하면 상한과 매직바이트만 본다. 신고 크기를 서버가 보관하지 않는 이유는
 *             정본 {@code accident_image} 에 담을 컬럼이 없기 때문이다(answer25 D2)
 */
public record ImageUploadCompleteItem(
        @NotNull(message = "imageId는 필수입니다.")
        @Positive(message = "imageId는 0보다 커야 합니다.")
        Long imageId,

        @Positive(message = "파일 크기는 0보다 커야 합니다.")
        Long size
) {

    public ImageUploadCompleteItem(Long imageId) {
        this(imageId, null);
    }
}
