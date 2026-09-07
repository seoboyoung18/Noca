package com.ssafy.a307.auth.dto;

import com.ssafy.a307.member.entity.TermsType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * 약관 동의 후 가입 요청.
 *
 * @param nickname    {@code member.nickname} 이 VARCHAR(12) 라 12자를 넘길 수 없다.
 *                    소셜에서 받은 닉네임이 더 길 수 있어 가입 화면에서 직접 받는다.
 * @param agreedTerms 동의한 약관 종류. 버전은 서버가 붙이므로 보내지 않는다.
 *                    필수 항목이 빠졌는지는 서비스 계층에서 검사한다.
 */
public record SignupRequest(

        @NotBlank(message = "닉네임을 입력해 주세요.")
        @Size(max = 12, message = "닉네임은 12자 이하여야 합니다.")
        String nickname,

        @NotEmpty(message = "약관 동의가 필요합니다.")
        Set<TermsType> agreedTerms) {
}
