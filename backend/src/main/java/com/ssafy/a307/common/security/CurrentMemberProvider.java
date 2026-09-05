package com.ssafy.a307.common.security;

import org.springframework.stereotype.Component;

/**
 * 로그인 회원의 {@code memberId} 를 얻는 유일한 지점.
 * <p>
 * 세션 로그인(/api/auth/**)이 아직 없다. 인증이 붙으면 이 클래스의 본문만 교체한다.
 * Service·Repository·DTO 는 memberId 를 파라미터로만 받으므로 손대지 않는다.
 */
@Component
public class CurrentMemberProvider {

    public Long currentMemberId() {
        throw new UnsupportedOperationException("인증 연동 전 — 세션 로그인 구현 후 교체 (S15P21A307-auth)");
    }
}
