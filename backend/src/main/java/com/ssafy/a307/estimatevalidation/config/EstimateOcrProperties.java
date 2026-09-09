package com.ssafy.a307.estimatevalidation.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 견적서 판독(LLM) 어댑터 설정. 호출은 <b>SSAFY GMS 프록시</b>를 지난다.
 *
 * <p><b>GMS 는 LLM 이 아니라 프록시다.</b> 벤더의 원래 엔드포인트 앞에
 * {@code https://gms.ssafy.io/gmsapi/} 를 붙이고 벤더의 키 자리에 GMS 키를 넣는 것이 전부다.
 * 요청 본문은 벤더 원본 그대로이며, 벤더별 경로와 인증 헤더는 {@code LlmVendor} 가 갖는다.
 *
 * <p><b>키는 환경변수 {@code GMS_KEY} 로만 읽는다.</b> {@code application.properties} 에는
 * {@code ${GMS_KEY:}} 참조만 두고 값을 적지 않는다. 이 record 의 {@code apiKey} 는 로그·예외
 * 메시지·{@code toString} 어디에도 나가면 안 되므로 아래에서 {@code toString} 을 직접 막았다.
 *
 * <p><b>크레딧이 유한하고 만료된다.</b> 팀 공용 키라 실패한 호출도 크레딧을 태운다. 그래서
 * {@code maxAttempts} 기본값이 2 이고 상한이 5 다 — 상한 없는 재시도를 만들 수 없게 했다.
 *
 * @param provider      {@code gemini}(기본) 또는 {@code openai}. <b>{@code claude} 는 없다</b> —
 *                      GMS 문서에 Anthropic 경로가 없어 확인되지 않은 경로를 만들지 않았다
 * @param baseUrl       GMS 프록시 기준 URL. 벤더 호스트를 포함한 경로가 이 뒤에 붙는다
 * @param model         모델 이름. 벤더마다 다르므로 코드에 두지 않는다
 * @param minConfidence 이 값 미만인 항목은 버린다. 모델이 스스로 매긴 값이라 보정되지 않았고,
 *                      화면에 내보내지 않으며 <b>버릴지 말지를 정하는 데만</b> 쓴다
 * @param maxAttempts   호출 횟수 상한(첫 시도 포함). 판독은 멱등하지만 호출마다 크레딧을 쓴다
 */
@Validated
@ConditionalOnExpression("'${app.estimate-ocr.provider:}' != ''")
@ConfigurationProperties(prefix = "app.estimate-ocr")
public record EstimateOcrProperties(
        @NotBlank String provider,
        @NotBlank String baseUrl,
        @NotBlank String model,
        String apiKey,
        Duration timeout,
        @Min(1) @Max(5) int maxAttempts,
        @DecimalMin("0.0") @DecimalMax("1.0") double minConfidence,
        @Min(256) @Max(32768) int maxOutputTokens
) {

    public EstimateOcrProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("app.estimate-ocr.timeout must be positive");
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * <b>키가 새지 않게 막는다.</b> record 의 기본 {@code toString} 은 모든 구성요소를 찍는데,
     * 프로퍼티 바인딩 실패 메시지나 디버그 로그에 이 객체가 그대로 들어가면 API 키가
     * 로그 파일에 남는다.
     */
    @Override
    public String toString() {
        return "EstimateOcrProperties[provider=%s, baseUrl=%s, model=%s, apiKey=%s, timeout=%s]"
                .formatted(provider, baseUrl, model, hasApiKey() ? "***" : "(none)", timeout);
    }
}
