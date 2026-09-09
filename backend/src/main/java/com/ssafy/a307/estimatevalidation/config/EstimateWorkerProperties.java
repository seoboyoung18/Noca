package com.ssafy.a307.estimatevalidation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 견적서 파일 검증 워커 설정.
 *
 * <p>{@code EstimateValidationProperties} 의 관례를 따른다 — record 로 불변,
 * {@code @Validated} 로 기동 시점 실패, 기본값은 코드가 아니라 properties 가 가진다.
 *
 * <p><b>{@code enabled=true} 일 때만 이 빈이 등록된다.</b> 워커가 켜지지 않은 환경에서
 * 없는 프로퍼티 때문에 기동이 실패하면 안 되므로 클래스 자체를 조건부로 두었다
 * ({@code @ConfigurationPropertiesScan} 이 등록하는 다른 프로퍼티들과 다른 점이다).
 *
 * @param batchSize        한 주기에 집는 최대 건수. 크게 잡으면 한 주기가 길어져 다음 주기가 밀린다
 * @param processingTimeout 이 시간이 지나도 {@code PROCESSING} 인 건은 고아로 본다.
 *                          정상 처리는 몇 초에 끝나므로 넉넉히 잡는다
 */
@Validated
@ConditionalOnProperty(prefix = "app.estimate-worker", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "app.estimate-worker")
public record EstimateWorkerProperties(
        boolean enabled,
        @Min(1) @Max(100) int batchSize,
        Duration processingTimeout
) {

    public EstimateWorkerProperties {
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("app.estimate-worker.processing-timeout must be positive");
        }
    }
}
