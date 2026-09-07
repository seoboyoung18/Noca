package com.ssafy.a307.member.entity;

/**
 * 소셜 로그인 제공자. {@code member.provider} 의 CHECK 제약과 값이 1:1 로 대응한다.
 * <p>
 * enum 이름이 그대로 DB 문자열이 되므로 {@code @Enumerated(STRING)} 로만 매핑한다.
 * ORDINAL 로 바꾸면 CHECK 제약에 걸려 INSERT 자체가 실패한다.
 */
public enum Provider {

    KAKAO,
    GOOGLE;

    /**
     * Spring Security 의 {@code registrationId} 는 소문자다(kakao, google).
     * OAuth2 응답을 파싱할 때와 로그인 세션을 복원할 때 양방향으로 쓴다.
     */
    public String registrationId() {
        return name().toLowerCase();
    }

    public static Provider fromRegistrationId(String registrationId) {
        return Provider.valueOf(registrationId.toUpperCase());
    }
}
