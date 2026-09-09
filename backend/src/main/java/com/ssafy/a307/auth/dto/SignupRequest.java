package com.ssafy.a307.auth.dto;

import com.ssafy.a307.member.entity.TermsType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * 약관 동의 후 가입 요청.
 *
 * @param nickname    2~12자. 상한은 {@code member.nickname} 이 VARCHAR(12) 라서고,
 *                    소셜에서 받은 닉네임이 더 길 수 있어 가입 화면에서 직접 받는다.
 *                    실제 판정은 {@code NicknamePolicy} 가 하며 여기 값은 앞뒤 공백을
 *                    지우기 전이라 이 검증은 1차 방어선이다.
 * @param agreedTerms 동의한 약관 종류. 버전은 서버가 붙이므로 보내지 않는다.
 *                    필수 항목이 빠졌는지는 서비스 계층에서 검사한다.
 */
public record SignupRequest(

        @NotBlank(message = "닉네임을 입력해 주세요.")
        @Size(min = 2, max = 12, message = "닉네임은 2~12자여야 합니다.")
        String nickname,

        @NotEmpty(message = "약관 동의가 필요합니다.")
        Set<TermsType> agreedTerms) {
}
