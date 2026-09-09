package com.ssafy.a307.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 프로필 이미지 업로드 URL 발급 요청.
 *
 * @param contentType 올릴 파일의 형식. presigned URL 서명에 들어가므로
 *                    브라우저가 PUT 할 때 같은 값을 보내야 한다
 * @param size        올릴 파일의 크기. 여기 값은 클라이언트 선언이라 1차 거르기용이고,
 *                    업로드 완료 통보 때 실제 객체 크기로 다시 확인한다
 */
public record ProfileImageUploadUrlRequest(

        @NotBlank(message = "Content-Type은 필수입니다.")
        String contentType,

        @NotNull(message = "파일 크기는 필수입니다.")
        @Positive(message = "파일 크기는 0보다 커야 합니다.")
        Long size) {
}
