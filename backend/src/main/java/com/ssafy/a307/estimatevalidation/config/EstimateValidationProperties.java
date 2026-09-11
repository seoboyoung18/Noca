package com.ssafy.a307.estimatevalidation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 견적서 검증의 <b>인프라 설정</b>.
 *
 * <h2>판정 임계값이 여기서 빠졌다 — 정정</h2>
 * 예전에는 이 record 가 {@code GradePolicy} 를 구현해 등급 임계값 다섯 개를 함께 들고 있었다.
 * 그 값들은 이제 {@code estimate_validation_rule} 테이블에서 관리한다
 * ({@code EstimateValidationRuleProvider}). 옮긴 이유는 두 가지다.
 * <ul>
 *   <li><b>재배포 없이 바꿔야 한다.</b> 임계값 조정은 운영 중 판단이지 코드 변경이 아니다</li>
 *   <li><b>과거 판정의 근거가 남아야 한다.</b> 프로퍼티는 이력이 없어 "그때 임계값이 얼마였나" 를
 *       답할 수 없다. 규칙 테이블은 버전이 쌓이고 {@code estimate_validation.rule_version} 이
 *       어느 버전으로 판정했는지 가리킨다</li>
 * </ul>
 *
 * <p><b>{@code presignedUrlMinutes} 는 남았다.</b> 파일 URL 유효시간은 판정 규칙이 아니라
 * 저장소 인프라 설정이고, 관리자가 등급 임계값을 만지다가 함께 바꾸면 안 되는 값이다.
 */
@Validated
@ConfigurationProperties(prefix = "app.estimate-validation")
public record EstimateValidationProperties(

        /*
         * 견적서 파일 다운로드용 presigned URL 유효시간(분).
         * app.accident-image.presigned-url-minutes 와 같은 형태·같은 범위다.
         */
        @Min(1) @Max(1440) int presignedUrlMinutes
) {
}
