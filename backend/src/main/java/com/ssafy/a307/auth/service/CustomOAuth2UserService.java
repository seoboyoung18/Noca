package com.ssafy.a307.auth.service;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * 소셜 인증이 끝난 직후 회원 여부를 판정한다.
 * <p>
 * 여기서 회원을 만들지 <b>않는다.</b> 약관 동의를 받기 전이라 {@code terms_agreement} 를
 * 함께 남길 수 없기 때문이다. 회원 생성은 {@code POST /api/auth/signup} 에서 한 번에 일어난다.
 *
 * <p>탈퇴 회원이 여기 걸리는 일은 없다 — 탈퇴 시점에 {@code provider_user_id} 를 익명화하므로
 * 조회가 비어서 돌아오고, 그대로 신규 가입 흐름을 탄다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final MemberService memberService;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        OAuth2UserInfo info = OAuth2UserInfo.of(registrationId, oAuth2User.getAttributes());

        return memberService.findBySocial(info.provider(), info.providerUserId())
                .map(this::toActivePrincipal)
                .orElseGet(() -> {
                    log.debug("가입 대기 상태로 진입 provider={}", info.provider());
                    return UserPrincipal.ofPendingSignup(
                            info.provider(), info.providerUserId(), info.nickname());
                });
    }

    /**
     * 익명화가 정상 동작하면 탈퇴 회원은 조회되지 않는다. 그래도 한 번 더 막는다 —
     * 익명화가 누락된 행이 있으면 탈퇴한 사람이 그대로 로그인되기 때문이다.
     */
    private UserPrincipal toActivePrincipal(Member member) {
        if (!member.isActive()) {
            log.warn("익명화되지 않은 탈퇴 회원의 로그인 시도 memberId={}", member.getMemberId());
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("withdrawn_member", "탈퇴한 회원입니다.", null));
        }
        return UserPrincipal.ofMember(member);
    }
}
