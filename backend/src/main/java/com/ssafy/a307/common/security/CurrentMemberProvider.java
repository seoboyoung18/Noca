package com.ssafy.a307.common.security;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 로그인 회원의 {@code memberId} 를 얻는 유일한 지점.
 * <p>
 * 세션에 담긴 {@link UserPrincipal} 에서 값을 꺼낸다. 요청 본문·경로·헤더로는 회원을 받지 않는다 —
 * 그렇게 하면 남의 ID 를 적어 보내는 것만으로 소유자 검사가 무력해진다.
 * Service·Repository·DTO 는 memberId 를 파라미터로만 받으므로 손대지 않는다.
 *
 * <p><b>DB 를 조회하지 않는다.</b> 세션의 memberId 를 그대로 믿는다. 매 요청마다 회원 상태(탈퇴·정지)를
 * 다시 확인할지는 인증 도메인의 정책이고, 현재 그렇게 하는 곳은 {@code GET /api/auth/me} 하나뿐이다
 * ({@code AuthController} 가 {@code Member::isActive} 를 검사한다). 그 정책을 보호 API 전체로
 * 넓히는 것은 이 클래스가 단독으로 정할 일이 아니라 별도 협의 대상이다.
 *
 * <p><b>아래 세 갈래는 방어 코드이지 정상 흐름이 아니다.</b> {@code SecurityConfig} 가
 * {@code anyRequest().hasAnyRole("USER","ADMIN")} 으로 걸러 두었으므로 비인증 요청은 401 로,
 * 가입 대기({@code ROLE_SIGNUP_PENDING}) 세션은 403 {@code SIGNUP_REQUIRED} 로 <b>필터 단계에서</b>
 * 끊긴다. 여기까지 도달했다면 인가 설정이 무너졌다는 뜻이므로, 미확인 예외로 500 을 내지 않고
 * {@link ErrorCode#UNAUTHORIZED} 로 명확히 거절한다.
 *
 * @see com.ssafy.a307.config.SecurityConfig
 */
@Component
public class CurrentMemberProvider {

    /**
     * @return 로그인 회원의 PK
     * @throws BusinessException 인증되지 않았거나 가입이 완료되지 않은 주체 ({@code UNAUTHORIZED})
     */
    public Long currentMemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw unauthenticated();
        }
        if (!(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw unauthenticated();
        }
        // memberId 가 없는 주체 = 소셜 인증만 끝난 가입 대기 상태. 아직 회원이 아니다.
        if (principal.isSignupPending()) {
            throw unauthenticated();
        }
        return principal.getMemberId();
    }

    /** 세 갈래가 같은 응답이다 — 어느 단계에서 걸렸는지 알려 줄 이유가 없다. */
    private BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다.");
    }
}
