package com.ssafy.a307.estimate.pdf;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 견적 PDF 워커 설정. {@code ValidationPdfProperties} 와 같은 관례 — 켰을 때만 빈이 되고,
 * 기본값은 {@code application.properties} 가 가진다.
 *
 * @param batchSize         한 주기에 집는 최대 건수. 검증 PDF 와 달리 LLM 호출이 없어 더 크게 잡을 수 있다
 * @param processingTimeout 이 시간이 지나도 PROCESSING 이면 고아로 본다
 */
@Validated
@ConditionalOnProperty(prefix = "app.estimate-pdf", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "app.estimate-pdf")
public record EstimatePdfProperties(
        boolean enabled,
        @Min(1) @Max(50) int batchSize,
        Duration processingTimeout) {

    public EstimatePdfProperties {
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("app.estimate-pdf.processing-timeout must be positive");
        }
    }
}
