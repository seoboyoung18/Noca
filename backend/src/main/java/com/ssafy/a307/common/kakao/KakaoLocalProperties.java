package com.ssafy.a307.common.kakao;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Locale;

/**
 * 카카오 로컬(Local) REST API 호출 설정.
 *
 * <p>{@link com.ssafy.a307.common.llm.GmsProperties} 의 규칙을 그대로 따른다 — record 로 불변,
 * {@code @Validated} 로 기동 시점 실패, <b>기본값은 코드가 아니라 {@code application.properties}
 * 가 가진다</b>.
 *
 * <p><b>REST API 키를 이 record 에 담지 않는다.</b> record 의 기본 {@code toString} 은 모든
 * 구성요소를 찍는다. 프로퍼티 바인딩 실패 메시지·디버그 로그·예외 스택 어느 하나에 이 객체가
 * 들어가는 순간 <b>키가 로그 파일에 그대로 남는다.</b> 그래서 키는
 * {@link KakaoRestApiKey} 라는 다른 타입으로 분리했고, 그쪽은 {@code toString} 을 막았다.
 *
 * <p><b>JavaScript 키는 이 계층에 존재하지 않는다.</b> 지도 렌더링용 JS 키와 도메인 등록은 FE
 * 몫이고, 서버가 그것을 보관하거나 응답으로 내보내면 키가 새는 통로가 하나 더 생긴다.
 *
 * <p><b>환경변수 이름</b> (두 형태가 모두 통하고, 섞은 이름은 오류 없이 무시된다)
 * <pre>
 * app.kakao.local.rest-api-key    KAKAO_REST_API_KEY 로 주입
 * app.kakao.local.base-url        APP_KAKAO_LOCAL_BASEURL      · APP_KAKAO_LOCAL_BASE_URL
 * app.kakao.local.max-attempts    APP_KAKAO_LOCAL_MAXATTEMPTS  · APP_KAKAO_LOCAL_MAX_ATTEMPTS
 * </pre>
 *
 * @param baseUrl        카카오 로컬 API 기준 URL({@code https://dapi.kakao.com}).
 *                       엔드포인트 경로가 이 뒤에 붙는다
 * @param connectTimeout 연결 타임아웃. 기본값에 의존하지 않는다
 * @param readTimeout    응답 타임아웃. 무한 대기가 되면 요청 스레드가 통째로 멈춘다
 * @param maxAttempts    첫 시도를 포함한 호출 횟수 상한. <b>상한을 3으로 묶은 이유는 쿼터다</b> —
 *                       카카오 로컬은 일일 허용 회수가 있고, 실패한 재시도도 회수를 태운다.
 *                       재시도 대상 자체가 {@code -1}·{@code -603}·타임아웃뿐이다
 */
@Validated
@ConfigurationProperties(prefix = "app.kakao.local")
public record KakaoLocalProperties(

        @NotBlank String baseUrl,

        @NotNull Duration connectTimeout,

        @NotNull Duration readTimeout,

        @Min(1) @Max(3) int maxAttempts
) {

    /**
     * <b>평문 HTTP 를 거절한다.</b> 이 통로로 REST API 키가 {@code Authorization} 헤더에 실려
     * 나간다. 프로퍼티를 잘못 적어 {@code http://} 로 나가는 일이 <b>기동 시점에</b> 걸려야 한다.
     */
    @AssertTrue(message = "app.kakao.local.base-url must use https")
    public boolean isBaseUrlSecure() {
        return baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    @AssertTrue(message = "app.kakao.local connect-timeout/read-timeout must be positive")
    public boolean areDurationsPositive() {
        return isPositive(connectTimeout) && isPositive(readTimeout);
    }

    /** 뒤에 붙는 경로가 {@code /} 로 시작하므로 기준 URL 의 꼬리 슬래시를 떼어 둔다. */
    public String normalizedBaseUrl() {
        String trimmed = baseUrl.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
