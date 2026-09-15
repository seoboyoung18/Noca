package com.ssafy.a307.analysis.request;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러를 <b>워커를 켰을 때만</b> 켠다 — {@code RepairChecklistSchedulingConfig} 와 같은 판단이다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.analysis-request", name = "enabled", havingValue = "true")
public class AnalysisRequestSchedulingConfig {
}
