package com.ssafy.a307.auth;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그아웃 — 서버 세션 무효화와 세션 재사용 차단.
 * <p>
 * 로그아웃은 컨트롤러가 아니라 {@code LogoutFilter} 가 처리하므로, 필터 체인을 켠 채로만
 * 검증할 수 있다. {@code addFilters = false} 를 쓰면 검증 대상 자체가 사라진다.
 *
 * <p><b>Redis 는 띄우지 않는다.</b> 세션 저장소 자동설정을 빼서 세션은 서블릿 컨테이너 쪽
 * {@code MockHttpSession} 이 된다. 여기서 고정하는 것은 저장소와 무관한 계약이다 —
 * "세션이 무효화되고, 그 세션으로는 다시 들어올 수 없다". 무효화된 세션의 Redis 키가 실제로
 * 지워지는지는 Spring Session 의 {@code SessionRepositoryFilter} 가 보장하는 부분이라
 * 실환경 확인으로 남긴다(Docs/Api 인증 인수인계 문서의 확인 절차).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("로그아웃")
class LogoutFlowTest {

    private static final String LOGOUT = "/api/auth/logout";
    private static final String KAKAO_ID = "3899999999";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("로그인 세션으로 호출하면 204 이고 세션 쿠키를 만료시킨다")
    void logoutReturns204AndExpiresSessionCookie() throws Exception {
        mockMvc.perform(post(LOGOUT).session(memberSession()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("SESSION", 0));
    }

    /**
     * 이 테스트가 하위 Task 의 "재사용 차단"이다. 세션 ID 를 그대로 다시 제시해도
     * 인증이 복구되지 않아야 한다 — 로그아웃 뒤의 쿠키는 아무 힘이 없다.
     */
    @Test
    @DisplayName("로그아웃한 세션으로는 보호 API 에 다시 들어오지 못한다")
    void loggedOutSessionCannotBeReused() throws Exception {
        MockHttpSession session = memberSession();

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(post(LOGOUT).session(session))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/vehicles/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 약관 동의 화면에서 이탈하는 경로다. 가입 대기 세션은 회원이 아니라서
     * {@code ROLE_USER} 인가를 통과하지 못하는데, 그렇다고 로그아웃까지 막히면
     * 소셜 인증만 끝난 세션이 만료(10분)될 때까지 남는다.
     */
    @Test
    @DisplayName("가입 대기 세션도 로그아웃된다")
    void pendingSignupSessionCanLogout() throws Exception {
        MockHttpSession session = pendingSession();

        mockMvc.perform(post(LOGOUT).session(session))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();

        mockMvc.perform(get("/api/auth/signup").session(session))
                .andExpect(status().isUnauthorized());
    }

    /** 로그아웃은 멱등이다. 이미 끊긴 상태에서 눌러도 에러가 아니어야 프론트가 분기를 안 만든다. */
    @Test
    @DisplayName("세션 없이 호출해도 204 다")
    void logoutWithoutSessionIsIdempotent() throws Exception {
        mockMvc.perform(post(LOGOUT))
                .andExpect(status().isNoContent());
    }

    /**
     * csrf 를 끈 상태에서 {@code logoutUrl()} 만 주면 LogoutFilter 가 GET 까지 받아,
     * 이미지 태그나 프리페치 한 번으로 세션이 끊긴다. POST 전용 매처를 고정하는 테스트다.
     *
     * <p>상태 코드는 단언하지 않는다. GET 은 로그아웃 매처에 걸리지 않아 매핑 없는 경로로
     * 흘러가는데, {@code GlobalExceptionHandler} 의 {@code Exception} 포괄 핸들러가
     * {@code NoResourceFoundException} 까지 삼켜 404 가 아니라 500 이 나온다. 그건 이 경로만의
     * 문제가 아니라 매핑 없는 모든 경로의 공통 동작이라 여기서 고정할 계약이 아니다.
     * 여기서 지켜야 할 것은 하나 — <b>로그아웃되지 않는다</b>.
     */
    @Test
    @DisplayName("GET 으로는 로그아웃되지 않는다")
    void getDoesNotLogout() throws Exception {
        MockHttpSession session = memberSession();

        mockMvc.perform(get(LOGOUT).session(session))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(204));

        assertThat(session.isInvalid()).isFalse();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk());
    }

    /** 소셜 인증만 끝난 세션. {@code OAuth2SuccessHandler} 가 만드는 것과 같은 모양이다. */
    private MockHttpSession pendingSession() {
        UserPrincipal principal =
                UserPrincipal.ofPendingSignup(Provider.KAKAO, KAKAO_ID, "카카오닉네임");
        Authentication authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), Provider.KAKAO.registrationId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    /** 가입까지 마친 로그인 세션. 가입 API 가 같은 세션을 ROLE_USER 로 올려 준다. */
    private MockHttpSession memberSession() throws Exception {
        MockHttpSession session = pendingSession();
        mockMvc.perform(post("/api/auth/signup")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"보영","agreedTerms":["SERVICE","PRIVACY"]}
                                """))
                .andExpect(status().isCreated());
        return session;
    }
}
