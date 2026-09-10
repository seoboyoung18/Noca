package com.ssafy.a307.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * CORS 허용 오리진. 값이 코드에 박혀 있으면 <b>배포하는 순간 전 화면이 막힌다</b> —
 * FE 오리진이 바뀌는데 서버는 {@code http://localhost:5173} 만 허용하기 때문이다.
 *
 * <p>이 실패는 로컬에서 절대 드러나지 않고, 브라우저 콘솔에만 나오며 서버 로그에는 아무것도
 * 남지 않는다. 그래서 값을 밖으로 뺀다.
 *
 * <p>{@code AccidentImageProperties} 의 규칙을 그대로 따른다 — record 로 불변,
 * {@code @Validated} 로 기동 시점 실패, 기본값은 코드가 아니라 {@code application.properties} 가 가진다.
 * {@code @DefaultValue} 를 두지 않는 것은 의도적이다. 프로퍼티가 빠지면 조용히 빈 목록으로 도는
 * 대신 기동이 실패한다 — 빈 목록이면 모든 오리진이 차단되어 증상이 하드코딩과 똑같아진다.
 *
 * <p><b>{@code app.frontend-base-url} 과 나눈 이유.</b> 그쪽은 OAuth 성공·실패 리다이렉트가 쓰는
 * <b>URL 하나</b>이고, 여기는 <b>목록</b>이다. 로컬·배포·프리뷰를 동시에 열어야 하는 경우가 있어
 * 하나로 합칠 수 없다. 대신 기본값을 {@code app.frontend-base-url} 로 두어, 오리진이 하나뿐인
 * 흔한 경우에는 값을 한 군데만 관리하면 되게 했다.
 *
 * <pre>
 * app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:${app.frontend-base-url}}
 * </pre>
 *
 * <p><b>환경변수 이름</b> — 두 형태가 통한다.
 * <pre>
 * app.cors.allowed-origins   APP_CORS_ALLOWEDORIGINS
 *                            APP_CORS_ALLOWED_ORIGINS
 * </pre>
 * 배포에서는 위 프로퍼티가 참조하는 {@code CORS_ALLOWED_ORIGINS} 하나만 주면 된다.
 */
@Validated
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(

        /*
         * 허용할 오리진 목록. 쉼표로 구분한다.
         *   CORS_ALLOWED_ORIGINS=https://a307.example.com,http://localhost:5173
         *
         * 빈 목록을 허용하지 않는 이유는, 그 상태가 곧 "모든 요청 차단" 이라 하드코딩과 같은
         * 사고를 내면서 원인만 더 찾기 어려워지기 때문이다.
         */
        @NotEmpty(message = "app.cors.allowed-origins 는 최소 한 개가 필요합니다.")
        List<@NotBlank String> allowedOrigins
) {

    /**
     * {@code "*"} 를 막는다.
     * <p>
     * 세션 쿠키를 쓰므로 {@code setAllowCredentials(true)} 이고, 그 조합에서 {@code "*"} 는
     * Spring 이 <b>요청 처리 시점에</b> {@code IllegalArgumentException} 을 던진다. 기동은
     * 성공하고 첫 요청에서 500 이 나므로 원인을 찾기 어렵다. 여기서 미리 끊는다.
     */
    @AssertTrue(message = "app.cors.allowed-origins 에 \"*\" 를 쓸 수 없습니다. "
            + "세션 쿠키(allowCredentials=true)와 함께 쓰면 요청 시점에 실패합니다. "
            + "허용할 오리진을 정확히 적어 주세요.")
    public boolean isWildcardAbsent() {
        return allowedOrigins == null || allowedOrigins.stream().noneMatch(o -> o.contains("*"));
    }

    /**
     * 오리진이 {@code scheme://host[:port]} 형태인지 본다.
     * <p>
     * 브라우저가 보내는 {@code Origin} 헤더에는 경로도 끝 슬래시도 없다. 설정에 {@code /} 가
     * 하나 붙으면 <b>영원히 일치하지 않는데 아무 오류도 나지 않는다</b> — 그냥 전부 차단된다.
     * 실제로 겪으면 원인을 찾는 데 한참 걸리는 종류라 기동 시점에 잡는다.
     */
    @AssertTrue(message = "app.cors.allowed-origins 의 각 값은 scheme://host[:port] 여야 합니다. "
            + "끝 슬래시·경로·질의 문자열이 있으면 브라우저의 Origin 헤더와 일치하지 않아 "
            + "오류 없이 전부 차단됩니다.")
    public boolean isEachOriginSchemeHostOnly() {
        if (allowedOrigins == null) {
            return true;
        }
        return allowedOrigins.stream().allMatch(CorsProperties::isOriginForm);
    }

    private static boolean isOriginForm(String origin) {
        if (origin == null || origin.isBlank() || origin.endsWith("/")) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            return uri.getScheme() != null
                    && uri.getHost() != null
                    && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
