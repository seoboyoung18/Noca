package com.ssafy.a307.auth.handler;

import com.ssafy.a307.auth.LoginFailureReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실패 시 로그인 화면 복귀와 사유 전달. 스프링 컨텍스트 없이 핸들러만 직접 호출한다.
 */
@DisplayName("소셜 인증 실패 핸들러")
class OAuth2FailureHandlerTest {

    private static final String FRONTEND = "http://localhost:5173";

    private OAuth2FailureHandler handler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        handler = new OAuth2FailureHandler(FRONTEND);
        request = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("동의를 거부하면 access_denied 를 달고 로그인 화면으로 보낸다")
    void redirectsWithAccessDenied() throws Exception {
        handler.onAuthenticationFailure(request, response, oauth2Error("access_denied"));

        assertThat(response.getRedirectedUrl())
                .isEqualTo(FRONTEND + "/login?error=access_denied");
    }

    @Test
    @DisplayName("탈퇴 회원 차단은 withdrawn 으로 나간다")
    void redirectsWithWithdrawn() throws Exception {
        handler.onAuthenticationFailure(request, response, oauth2Error("withdrawn_member"));

        assertThat(response.getRedirectedUrl()).endsWith("/login?error=withdrawn");
    }

    @Test
    @DisplayName("모르는 실패는 server_error 로 나간다")
    void redirectsWithServerError() throws Exception {
        handler.onAuthenticationFailure(request, response,
                new InsufficientAuthenticationException("알 수 없음"));

        assertThat(response.getRedirectedUrl()).endsWith("/login?error=server_error");
    }

    /**
     * 사유 상세가 URL 에 실리면 어느 계정이 왜 막혔는지가 브라우저 이력과 리퍼러에 남는다.
     * 쿼리에는 분류 코드 하나만 있어야 한다.
     */
    @Test
    @DisplayName("예외 메시지가 URL 로 새어 나가지 않는다")
    void doesNotLeakExceptionDetailToUrl() throws Exception {
        AuthenticationException detailed = new OAuth2AuthenticationException(
                new OAuth2Error("withdrawn_member", "탈퇴한 회원입니다. memberId=42", null));

        handler.onAuthenticationFailure(request, response, detailed);

        assertThat(response.getRedirectedUrl())
                .doesNotContain("memberId")
                .doesNotContain("탈퇴")
                .isEqualTo(FRONTEND + "/login?error=withdrawn");
    }

    @Test
    @DisplayName("콜백 경로가 아니어도 리다이렉트는 정상 동작한다")
    void handlesNonCallbackPath() throws Exception {
        MockHttpServletRequest authorizationRequest =
                new MockHttpServletRequest("GET", "/oauth2/authorization/kakao");

        handler.onAuthenticationFailure(authorizationRequest, response, oauth2Error("access_denied"));

        assertThat(response.getRedirectedUrl()).endsWith("/login?error=access_denied");
    }

    @Test
    @DisplayName("모든 분류가 로그인 화면 경로로 복귀시킨다")
    void alwaysReturnsToLoginPage() throws Exception {
        for (LoginFailureReason reason : LoginFailureReason.values()) {
            MockHttpServletResponse each = new MockHttpServletResponse();

            handler.onAuthenticationFailure(request, each, oauth2Error(rawCodeFor(reason)));

            assertThat(each.getRedirectedUrl())
                    .startsWith(FRONTEND + "/login?error=")
                    .endsWith(reason.code());
        }
    }

    private String rawCodeFor(LoginFailureReason reason) {
        return switch (reason) {
            case ACCESS_DENIED -> "access_denied";
            case WITHDRAWN -> "withdrawn_member";
            case INVALID_RESPONSE -> "invalid_user_info_response";
            case SERVER_ERROR -> "무엇인지 모를 코드";
        };
    }

    private OAuth2AuthenticationException oauth2Error(String errorCode) {
        return new OAuth2AuthenticationException(new OAuth2Error(errorCode, "테스트", null));
    }
}
