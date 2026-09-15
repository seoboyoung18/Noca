package com.ssafy.a307.repairquestion.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러를 <b>워커를 켰을 때만</b> 켠다.
 *
 * <p>{@code @EnableScheduling} 을 애플리케이션 클래스에 붙이면 워커를 끈 환경에서도 스케줄러
 * 스레드 풀이 생기고, 나중에 누가 {@code @Scheduled} 를 하나 더 붙이면 의도치 않게 돈다.
 * 특히 테스트에서 그렇다 — 워커가 임의로 돌면 다른 테스트가 흔들린다
 * ({@code RepairChecklistSchedulingConfig} 와 같은 판단이고, 조건도 워커와 같은 프로퍼티 하나로 묶었다).
 *
 * <p>{@code @EnableScheduling} 이 여러 설정 클래스에 붙어도 스케줄러는 하나만 만들어진다 —
 * 체크리스트 쪽과 함께 켜져도 중복되지 않는다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.repair-question", name = "enabled", havingValue = "true")
public class RepairQuestionSchedulingConfig {
}
