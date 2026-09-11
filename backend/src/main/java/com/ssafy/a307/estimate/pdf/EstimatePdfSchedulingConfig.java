package com.ssafy.a307.estimate.pdf;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러를 <b>견적 PDF 워커를 켰을 때만</b> 켠다. {@code ValidationPdfSchedulingConfig} 와 같은 이유 —
 * 워커를 끈 테스트에서 {@code @Scheduled} 가 임의로 돌면 다른 테스트가 흔들린다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.estimate-pdf", name = "enabled", havingValue = "true")
public class EstimatePdfSchedulingConfig {
}
