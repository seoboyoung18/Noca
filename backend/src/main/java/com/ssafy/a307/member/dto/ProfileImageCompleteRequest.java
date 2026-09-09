package com.ssafy.a307.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 업로드 완료 통보.
 *
 * @param uploadKey 업로드 URL 발급 때 받은 {@code uploadKey} 를 그대로 돌려준다.
 *                  <b>서버는 이 값이 이 회원의 것인지 반드시 확인한다</b> —
 *                  클라이언트가 보내는 값이라 남의 키를 넣을 수 있기 때문이다.
 */
public record ProfileImageCompleteRequest(

        @NotBlank(message = "업로드 키는 필수입니다.")
        @Size(max = 500, message = "업로드 키가 너무 깁니다.")
        String uploadKey) {
}
