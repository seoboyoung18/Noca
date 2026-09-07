package com.ssafy.a307.auth.handler;

import com.ssafy.a307.auth.AuthSessionPolicy;
import com.ssafy.a307.auth.principal.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * 소셜 인증 성공 직후. 회원 여부에 따라 프론트의 서로 다른 화면으로 보낸다.
 * <p>
 * 응답 본문을 주지 않고 리다이렉트로 끝낸다. 이 시점의 클라이언트는 카카오가 돌려보낸
 * 브라우저 네비게이션이라 JSON 을 받아도 처리할 주체가 없기 때문이다.
 */
@Slf4j
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    private final String frontendBaseUrl;

    /**
     * 기본값이 붙어 있는 이유 — 테스트 클래스패스의 {@code application.properties} 가 main 쪽을
     * 가려서, 그쪽에 이 키가 없으면 스프링 컨텍스트가 통째로 뜨지 않는다. 실제 값은 항상
     * main 의 {@code app.frontend-base-url} 에서 온다(그쪽도 환경변수로 덮을 수 있다).
     */
    public OAuth2SuccessHandler(
            @Value("${app.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

        HttpSession session = request.getSession();
        String target;

        if (principal.isSignupPending()) {
            session.setMaxInactiveInterval(AuthSessionPolicy.seconds(AuthSessionPolicy.PENDING_SIGNUP));
            target = buildUrl("/signup");
            log.debug("가입 대기 세션 생성 provider={}", principal.getProvider());
        } else {
            session.setMaxInactiveInterval(AuthSessionPolicy.seconds(AuthSessionPolicy.AUTHENTICATED));
            target = buildUrl("/");
            log.debug("로그인 완료 memberId={}", principal.getMemberId());
        }

        redirectStrategy.sendRedirect(request, response, target);
    }

    private String buildUrl(String path) {
        return UriComponentsBuilder.fromUriString(frontendBaseUrl).path(path).build().toUriString();
    }
}
