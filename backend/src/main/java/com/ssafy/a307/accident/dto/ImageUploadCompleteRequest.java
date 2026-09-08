package com.ssafy.a307.accident.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * {@code POST /api/accidents/{accidentId}/images} 요청 — S3 PUT 을 마친 파일을 통보한다.
 * <p>
 * 실패한 파일만 다시 통보해도 된다(요구사항 22행의 개별 재시도). 이미 완료된 imageId 를 다시
 * 보내면 재처리하지 않고 {@code ALREADY_COMPLETED} 로 응답한다 — asset 이 이미 있으므로
 * 재처리는 {@code uk_aia} 를 위반한다.
 */
public record ImageUploadCompleteRequest(
        @NotEmpty(message = "완료를 통보할 이미지가 필요합니다.")
        @Valid
        List<ImageUploadCompleteItem> images
) {
}
