package com.ssafy.a307.common.security;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CurrentMemberProvider} 가 세션 주체에서 회원 ID 를 꺼내는지 본다.
 *
 * <p><b>이 테스트가 있는 이유</b> — 컨트롤러 테스트는 전부 이 빈을 {@code @MockitoBean} 으로
 * 바꿔 넣는다. 그래서 이 클래스가 예외를 던지는 스텁으로 되돌아가도 그쪽은 초록불이다.
 * 실제 구현을 지나는 테스트가 여기 하나뿐이므로 지우지 말 것.
 *
 * <p>{@code @SpringBootTest} 인 이유는 {@code memberId} 때문이다. {@link UserPrincipal#ofMember}
 * 가 PK 를 읽는데 그 값은 DB 가 만든다. 회원을 실제로 저장해야 가입 완료 상태를 재현할 수 있다.
 */
@SpringBootTest
@Transactional
@DisplayName("CurrentMemberProvider")
class CurrentMemberProviderTest {

    @Autowired
    private CurrentMemberProvider currentMemberProvider;
    @Autowired
    private MemberRepository memberRepository;

    /** 컨텍스트는 스레드에 남는다. 지우지 않으면 뒤에 도는 테스트가 남의 주체를 물려받는다. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("로그인 회원의 memberId 를 돌려준다")
    void returnsMemberIdOfAuthenticatedMember() {
        Member member = memberRepository.save(
                Member.register(Provider.KAKAO, "kakao-current-member", "재원"));
        authenticate(UserPrincipal.ofMember(member));

        assertThat(currentMemberProvider.currentMemberId()).isEqualTo(member.getMemberId());
    }

    @Test
    @DisplayName("인증 정보가 없으면 401 이다 — 500 으로 새지 않는다")
    void rejectsMissingAuthentication() {
        SecurityContextHolder.clearContext();

        assertUnauthorized();
    }

    @Test
    @DisplayName("인증됐지만 주체가 UserPrincipal 이 아니면 401 이다")
    void rejectsForeignPrincipalType() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "not-a-user-principal", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        assertUnauthorized();
    }

    @Test
    @DisplayName("가입 대기 세션은 401 이다 — 회원이 아직 없다")
    void rejectsSignupPendingPrincipal() {
        authenticate(UserPrincipal.ofPendingSignup(Provider.KAKAO, "kakao-pending", "카카오닉네임"));

        assertUnauthorized();
    }

    private void authenticate(UserPrincipal principal) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private void assertUnauthorized() {
        assertThatThrownBy(() -> currentMemberProvider.currentMemberId())
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }
}
