package com.ssafy.a307.auth;

import java.time.Duration;

/**
 * 세션 수명 정책.
 * <p>
 * 전역 설정 {@code server.servlet.session.timeout} 하나로는 두 값을 표현할 수 없어
 * 세션 단위로 지정한다. Spring Session Redis 는 세션마다 {@code maxInactiveInterval} 을 보고
 * Redis 키의 TTL 을 잡으므로, 여기서 정한 값이 실제 만료로 이어진다.
 */
public final class AuthSessionPolicy {

    /**
     * 가입 대기 세션. 소셜 인증만 끝나고 아직 회원이 아닌 상태라 짧게 잡는다.
     * 이 시간이 지나면 세션이 사라져 소셜 로그인부터 다시 해야 한다.
     */
    public static final Duration PENDING_SIGNUP = Duration.ofMinutes(10);

    /** 가입까지 마친 로그인 세션. */
    public static final Duration AUTHENTICATED = Duration.ofMinutes(30);

    private AuthSessionPolicy() {
    }

    /** {@code HttpSession#setMaxInactiveInterval} 이 초 단위 int 를 받는다. */
    public static int seconds(Duration duration) {
        return Math.toIntExact(duration.toSeconds());
    }
}
