package com.ssafy.a307.auth.dto;

import com.ssafy.a307.member.entity.Provider;

import java.util.Set;

/**
 * 가입 화면이 그려질 때 필요한 값.
 * <p>
 * 소셜 닉네임은 입력란을 미리 채우는 용도다. 12자를 넘을 수 있으므로 프론트가 그대로 제출하면
 * 검증에 걸린다. 잘라서 보여줄지 비워 둘지는 화면 쪽 판단에 맡긴다.
 */
public record SignupContextResponse(Provider provider,
                                    String socialNickname,
                                    Set<String> requiredTerms) {
}
