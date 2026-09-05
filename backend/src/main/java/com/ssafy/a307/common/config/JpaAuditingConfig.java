package com.ssafy.a307.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * DDL 에 트리거가 없어 {@code updated_at} 을 DB 가 갱신하지 않는다.
 * 애플리케이션이 {@code @LastModifiedDate} 로 채운다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
