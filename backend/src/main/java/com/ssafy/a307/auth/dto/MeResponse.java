package com.ssafy.a307.auth.dto;

import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.MemberRole;
import com.ssafy.a307.member.entity.Provider;

/**
 * 로그인한 회원 정보.
 * <p>
 * {@code providerUserId} 는 내려보내지 않는다. 소셜 계정을 식별하는 값이라 프론트가 알 이유가 없다.
 */
public record MeResponse(Long memberId,
                         String nickname,
                         String email,
                         Provider provider,
                         MemberRole role) {

    public static MeResponse from(Member member) {
        return new MeResponse(
                member.getMemberId(),
                member.getNickname(),
                member.getEmail(),
                member.getProvider(),
                member.getRole());
    }
}
