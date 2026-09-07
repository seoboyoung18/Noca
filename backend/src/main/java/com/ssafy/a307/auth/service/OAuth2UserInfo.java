package com.ssafy.a307.auth.service;

import com.ssafy.a307.member.entity.Provider;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.Map;

/**
 * 소셜 제공자마다 다른 응답에서 우리가 쓰는 세 값만 뽑아낸 것.
 * <p>
 * 카카오만 먼저 붙이지만 파싱은 처음부터 분기 구조로 둔다. 구글을 나중에 붙일 때
 * {@link #of} 에 갈래를 하나 더하는 것으로 끝나게 하기 위해서다.
 *
 * @param providerUserId 반드시 문자열이다. 카카오는 숫자 id 를, 구글은 문자열 sub 를 준다.
 * @param nickname       <b>null 일 수 있다.</b> 가입 화면 초기값으로만 쓰이고 최종 닉네임은
 *                       사용자가 입력하므로, 없다고 로그인을 막지 않는다.
 *                       {@code NicknamePolicy} 가 자동 생성값으로 대체한다.
 */
public record OAuth2UserInfo(Provider provider, String providerUserId, String nickname) {

    private static final String ERROR_CODE = "invalid_user_info_response";

    public static OAuth2UserInfo of(String registrationId, Map<String, Object> attributes) {
        Provider provider = Provider.fromRegistrationId(registrationId);
        return switch (provider) {
            case KAKAO -> ofKakao(attributes);
            case GOOGLE -> ofGoogle(attributes);
        };
    }

    /**
     * 카카오 v2/user/me 응답에서 뽑는다. 구조는 id, kakao_account.profile.nickname 이다.
     * <p>
     * id 는 JSON 숫자라 Integer 또는 Long 으로 넘어온다. 어느 쪽으로 올지 보장이 없으므로
     * 캐스팅하지 않고 {@link String#valueOf} 로 받는다. Long 으로 캐스팅하면 값이 작을 때
     * Integer 가 와서 ClassCastException 이 난다.
     */
    @SuppressWarnings("unchecked")
    private static OAuth2UserInfo ofKakao(Map<String, Object> attributes) {
        Object id = require(attributes.get("id"), "id");

        // 닉네임 경로는 통째로 없을 수 있다. 동의 항목에서 닉네임이 빠지면
        // kakao_account 나 profile 자체가 오지 않는다. 중간 어디가 비어도 null 로 흘린다.
        Object nickname = null;
        if (attributes.get("kakao_account") instanceof Map<?, ?> account
                && account.get("profile") instanceof Map<?, ?> profile) {
            nickname = ((Map<String, Object>) profile).get("nickname");
        }

        return new OAuth2UserInfo(Provider.KAKAO, String.valueOf(id), asStringOrNull(nickname));
    }

    /** 구글 OIDC 응답. sub 가 이미 문자열이라 변환할 것이 없다. */
    private static OAuth2UserInfo ofGoogle(Map<String, Object> attributes) {
        Object sub = require(attributes.get("sub"), "sub");

        return new OAuth2UserInfo(Provider.GOOGLE, String.valueOf(sub),
                asStringOrNull(attributes.get("name")));
    }

    private static String asStringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 없는 값을 null 인 채로 흘려보내면 NOT NULL 위반으로 INSERT 단계에서야 터진다.
     * 파싱 지점에서 막아 원인이 소셜 응답임을 분명히 한다.
     */
    private static Object require(Object value, String attributeName) {
        if (value == null) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(ERROR_CODE, attributeName + " 값이 응답에 없습니다.", null));
        }
        return value;
    }
}
