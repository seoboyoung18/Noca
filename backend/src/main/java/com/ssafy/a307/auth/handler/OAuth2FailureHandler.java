package com.ssafy.a307.auth.handler;

import com.ssafy.a307.auth.LoginFailureReason;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * 소셜 인증 실패. 사유를 분류해 로그인 화면으로 돌려보내고, 실패 이력을 로그에 남긴다.
 * <p>
 * 사용자가 동의를 거부했거나, 소셜 응답이 우리가 기대한 모양이 아니거나, 토큰 교환이 실패한 경우다.
 *
 * <p><b>URL 에는 분류 코드만 싣는다.</b> 상세 사유를 쿼리에 담으면 어느 계정이 왜 막혔는지가
 * 브라우저 이력과 리퍼러에 남는다. 상세는 서버 로그에만 남긴다.
 */
@Component
public class OAuth2FailureHandler implements AuthenticationFailureHandler {

    /**
     * 실패 이력 전용 로거. 이름을 따로 둔 이유는 로그백에서 이 로거만 별도 파일·수집기로
     * 보낼 수 있게 하기 위해서다. 인증 로그를 DB 에 쌓지 않기로 했으므로(감사 로그 테이블은
     * 관리자 행위 전용) 이 로거가 곧 적재 지점이다.
     */
    private static final Logger AUTH_FAILURE_LOG = LoggerFactory.getLogger("AUTH_FAILURE");

    private static final String CALLBACK_PATH_PREFIX = "/login/oauth2/code/";
    private static final String FORWARDED_FOR = "X-Forwarded-For";

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
        LoginFailureReason reason = LoginFailureReason.from(exception);
        String provider = extractProvider(request);

        // 스택트레이스는 원인을 알 수 없는 경우에만 남긴다. 사용자가 동의를 거부한 것은
        // 정상 흐름이라 예외 전문을 남기면 로그만 시끄러워진다.
        if (reason == LoginFailureReason.SERVER_ERROR) {
            AUTH_FAILURE_LOG.warn("소셜 로그인 실패 provider={} reason={} cause={} ip={}",
                    provider, reason.code(), LoginFailureReason.rawCodeOf(exception),
                    clientIp(request), exception);
        } else {
            AUTH_FAILURE_LOG.info("소셜 로그인 실패 provider={} reason={} cause={} ip={}",
                    provider, reason.code(), LoginFailureReason.rawCodeOf(exception),
                    clientIp(request));
        }

        redirectStrategy.sendRedirect(request, response, loginUrlWith(reason));
    }

    private String loginUrlWith(LoginFailureReason reason) {
        return UriComponentsBuilder.fromUriString(frontendBaseUrl)
                .path("/login")
                .queryParam("error", reason.code())
                .build()
                .toUriString();
    }

    /**
     * 실패는 콜백 경로({@code /login/oauth2/code/kakao})에서 일어나므로 URI 에서 읽는다.
     * 인가 요청 단계에서 실패하면 콜백 경로가 아닐 수 있어 그때는 unknown 이다.
     */
    private String extractProvider(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int index = uri.indexOf(CALLBACK_PATH_PREFIX);
        if (index < 0) {
            return "unknown";
        }
        String provider = uri.substring(index + CALLBACK_PATH_PREFIX.length());
        return StringUtils.hasText(provider) ? provider : "unknown";
    }

    /** 프록시 뒤에 있으면 remoteAddr 이 프록시 주소라 X-Forwarded-For 를 먼저 본다. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (!StringUtils.hasText(forwarded)) {
            return request.getRemoteAddr();
        }
        int comma = forwarded.indexOf(',');
        return comma < 0 ? forwarded.trim() : forwarded.substring(0, comma).trim();
    }
}
