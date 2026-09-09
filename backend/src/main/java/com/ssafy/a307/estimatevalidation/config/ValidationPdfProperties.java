package com.ssafy.a307.estimatevalidation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 검증 결과 PDF 생성 워커 설정.
 *
 * <p>{@code EstimateValidationProperties}·{@code ImageQualityProperties} 의 관례를 따른다 —
 * record 로 불변, {@code @Validated} 로 기동 시점 실패, 기본값은 코드가 아니라
 * {@code application.properties} 가 가진다.
 *
 * <p><b>{@code enabled=true} 일 때만 이 빈이 등록된다.</b> 워커를 켜지 않은 환경에서 없는
 * 프로퍼티 때문에 기동이 실패하면 안 되므로 클래스 자체를 조건부로 두었다.
 *
 * @param batchSize         한 주기에 집는 최대 건수. <b>작게 잡는다</b> — 한 건마다 LLM 호출이
 *                          들어가고 그 계층의 read timeout 이 60초, 재시도가 2회다.
 *                          5건이면 최악의 경우 한 주기가 10분이 된다
 * @param processingTimeout 이 시간이 지나도 {@code PROCESSING} 인 건은 고아로 본다.
 *                          LLM 호출을 감안해 넉넉히 잡는다
 */
@Validated
@ConditionalOnProperty(prefix = "app.validation-pdf", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "app.validation-pdf")
public record ValidationPdfProperties(
        boolean enabled,
        @Min(1) @Max(20) int batchSize,
        Duration processingTimeout
) {

    public ValidationPdfProperties {
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("app.validation-pdf.processing-timeout must be positive");
        }
    }
}
