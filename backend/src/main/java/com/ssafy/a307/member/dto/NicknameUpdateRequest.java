package com.ssafy.a307.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 닉네임 수정 요청.
 *
 * @param nickname 2~12자. 여기 검증은 앞뒤 공백을 지우기 전 값에 걸리는 1차 방어선이고,
 *                 최종 판정은 공백을 지운 뒤 {@code NicknamePolicy} 가 한다.
 *                 <b>중복 검사는 하지 않는다</b> — 소셜 전용이라 닉네임이 식별자가 아니다.
 */
public record NicknameUpdateRequest(

        @NotBlank(message = "닉네임을 입력해 주세요.")
        @Size(min = 2, max = 12, message = "닉네임은 2~12자여야 합니다.")
        String nickname) {
}
