package com.ssafy.a307.analysis.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 분석 요청(백엔드 → AI 서버) 설정 (S15P21A307-156).
 *
 * <p><b>워커를 끈 환경에서도 이 빈은 등록된다.</b> 접수 API 가 "AI 에 보낼 수 있는가" 를 이 값으로
 * 판단하기 때문이다({@link #configured()}). 그래서 {@code RepairChecklistWorkerProperties} 와 달리
 * 클래스를 조건부로 두지 않았다.
 *
 * <p><b>주소에 기본값을 두지 않는다.</b> 비어 있으면 접수가 503 이다 — 엉뚱한 곳으로 사진 URL 을
 * 보내는 것보다 낫다. 공유 비밀은 결과 수신과 같은 {@code app.internal-api.token} 을 쓴다.
 * AI 서버 쪽 변수 이름은 {@code AI_INTERNAL_TOKEN} 이지만 <b>값은 같아야 한다.</b>
 *
 * @param enabled           워커 스위치. 꺼 두면 접수분이 {@code QUEUED} 에 머문다
 * @param aiBaseUrl         AI 서버 기준 주소. {@code /analyze} 를 뒤에 붙인다
 * @param callbackBaseUrl   AI 가 결과를 돌려보낼 백엔드 기준 주소. AI 서버에서 닿는 주소여야 한다
 * @param batchSize         한 주기에 집는 최대 건수
 * @param processingTimeout AI 에 보낸 뒤 이 시간이 지나도 결과가 없으면 {@code ABANDONED} 로 끝낸다
 * @param connectTimeout    AI 서버 연결 제한 시간
 * @param readTimeout       AI 서버 응답 제한 시간. {@code /analyze} 는 접수만 하고 202 를 주므로 짧다
 */
@Validated
@ConfigurationProperties(prefix = "app.analysis-request")
public record AnalysisRequestProperties(
        boolean enabled,
        String aiBaseUrl,
        String callbackBaseUrl,
        @Min(1) @Max(100) int batchSize,
        Duration processingTimeout,
        Duration connectTimeout,
        Duration readTimeout
) {

    public AnalysisRequestProperties {
        requirePositive(processingTimeout, "processing-timeout");
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
    }

    /** AI 서버 주소와 콜백 주소가 모두 있는가. 하나라도 비면 접수하지 않는다. */
    public boolean configured() {
        return hasText(aiBaseUrl) && hasText(callbackBaseUrl);
    }

    public String analyzeUrl() {
        return stripTrailingSlash(aiBaseUrl) + "/analyze";
    }

    /** 계약 ⑥ 경로. {@code AnalysisCallbackController} 가 받는 곳이다. */
    public String callbackUrl(Long jobId) {
        return stripTrailingSlash(callbackBaseUrl) + "/internal/analysis-jobs/" + jobId + "/result";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        String stripped = url.strip();
        return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.analysis-request." + name + " must be positive");
        }
    }
}
