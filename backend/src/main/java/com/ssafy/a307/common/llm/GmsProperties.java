package com.ssafy.a307.common.llm;

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
 * SSAFY GMS 를 통한 LLM 호출 설정.
 *
 * <p>{@link com.ssafy.a307.common.config.ImageQualityProperties} 의 세 규칙을 그대로 따른다 —
 * record 로 불변, {@code @Validated} 로 기동 시점 실패, <b>기본값은 코드가 아니라
 * {@code application.properties} 가 가진다</b>({@code @DefaultValue} 를 두지 않는다).
 *
 * <p><b>API 키를 이 record 에 담지 않는다.</b> record 의 기본 {@code toString} 은 모든 구성요소를
 * 찍는다. 프로퍼티 바인딩 실패 메시지, 디버그 로그, 예외 스택 어느 하나에 이 객체가 들어가는
 * 순간 <b>키가 로그 파일에 그대로 남는다.</b> 한 번 남은 키는 되돌릴 수 없으므로 아예 다른 타입
 * ({@link GmsApiKey})으로 분리했고, 그쪽은 {@code toString} 을 직접 막았다.
 *
 * <p><b>타임아웃에 기본값이 없다.</b> 두 값 모두 필수다. 무한 대기가 되면 응답이 오지 않는 한
 * 워커 스레드가 통째로 멈추고, 큐가 밀리는 원인을 찾기 어려워진다.
 *
 * <p><b>환경변수 이름</b> (두 형태가 모두 통하고, 섞은 이름은 오류 없이 무시된다 —
 * {@code ImageQualityProperties} Javadoc 이 경고하는 함정이다)
 * <pre>
 * app.gms.base-url             APP_GMS_BASEURL      · APP_GMS_BASE_URL
 * app.gms.provider             APP_GMS_PROVIDER
 * app.gms.model                APP_GMS_MODEL
 * app.gms.max-attempts         APP_GMS_MAXATTEMPTS  · APP_GMS_MAX_ATTEMPTS
 * </pre>
 *
 * @param baseUrl          GMS 프록시 기준 URL. 벤더 호스트를 포함한 경로가 이 뒤에 붙는다
 * @param provider         띄울 벤더 구현 하나를 고른다
 * @param model            벤더별 모델 이름. 코드에 박지 않는다
 * @param connectTimeout   연결 타임아웃. 기본값에 의존하지 않는다
 * @param readTimeout      응답 타임아웃. 첨부가 큰 요청은 느리므로 넉넉히 잡되 무한은 안 된다
 * @param maxOutputTokens  출력 토큰 상한. <b>크레딧 보호 장치</b>다
 * @param maxAttempts      첫 시도를 포함한 호출 횟수 상한. 상한이 3인 것은 재시도가 크레딧을 태우기 때문이다
 * @param minRemainCredit  잔여 크레딧이 이 값 미만이면 호출하지 않고 거절한다
 * @param keyInfoCacheTtl  {@code key-info} 캐시 수명. 요청마다 부르면 그 자체가 호출이다
 */
@Validated
@ConfigurationProperties(prefix = "app.gms")
public record GmsProperties(

        @NotBlank String baseUrl,

        @NotNull GmsProvider provider,

        @NotBlank String model,

        @NotNull Duration connectTimeout,

        @NotNull Duration readTimeout,

        @Min(1) @Max(32768) int maxOutputTokens,

        @Min(1) @Max(3) int maxAttempts,

        @Min(0) long minRemainCredit,

        @NotNull Duration keyInfoCacheTtl
) {

    /**
     * <b>평문 HTTP 를 거절한다.</b> 이 통로로 견적서 원본과 API 키가 함께 나간다.
     * 프로퍼티를 잘못 적어 {@code http://} 로 나가는 일이 <b>기동 시점에</b> 걸려야 한다.
     */
    @AssertTrue(message = "app.gms.base-url must use https")
    public boolean isBaseUrlSecure() {
        return baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    @AssertTrue(message = "app.gms connect-timeout/read-timeout/key-info-cache-ttl must be positive")
    public boolean areDurationsPositive() {
        return isPositive(connectTimeout) && isPositive(readTimeout) && isPositive(keyInfoCacheTtl);
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
