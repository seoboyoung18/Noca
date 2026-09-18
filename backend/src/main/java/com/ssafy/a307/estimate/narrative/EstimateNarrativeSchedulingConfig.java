package com.ssafy.a307.estimate.narrative;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러를 <b>워커를 켰을 때만</b> 켠다 (S15P21A307-537).
 *
 * <p>{@code @EnableScheduling} 을 애플리케이션 클래스에 붙이면 워커를 끈 환경에서도 스케줄러
 * 스레드 풀이 생기고, 나중에 누가 {@code @Scheduled} 를 하나 더 붙이면 의도치 않게 돈다.
 * 특히 테스트에서 그렇다 — 워커가 임의로 돌면 다른 테스트가 흔들린다
 * ({@code RepairChecklistSchedulingConfig} 와 같은 판단이고, 여러 워커가 함께 켜져도 스케줄러는
 * 하나만 만들어진다).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.estimate-narrative", name = "enabled", havingValue = "true")
public class EstimateNarrativeSchedulingConfig {
}
