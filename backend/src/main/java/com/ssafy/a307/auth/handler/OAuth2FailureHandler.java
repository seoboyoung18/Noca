package com.ssafy.a307.auth.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * 소셜 인증 실패. 사용자가 동의를 거부했거나, 소셜 응답이 우리가 기대한 모양이 아니거나,
 * 탈퇴 회원이 걸러진 경우다.
 * <p>
 * 실패 사유를 쿼리스트링에 그대로 싣지 않는다. 어느 계정이 왜 막혔는지가 URL 과 브라우저 이력에
 * 남기 때문이다. 상세는 서버 로그에만 남기고 프론트에는 재시도 가능 여부만 알린다.
 */
@Slf4j
@Component
public class OAuth2FailureHandler implements AuthenticationFailureHandler {

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    private final String frontendBaseUrl;

    /** 기본값을 두는 이유는 {@link OAuth2SuccessHandler} 쪽 설명과 같다. */
    public OAuth2FailureHandler(
            @Value("${app.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("소셜 인증 실패", exception);

        String target = UriComponentsBuilder.fromUriString(frontendBaseUrl)
                .path("/login")
                .queryParam("error", "oauth")
                .build()
                .toUriString();

        redirectStrategy.sendRedirect(request, response, target);
    }
}
