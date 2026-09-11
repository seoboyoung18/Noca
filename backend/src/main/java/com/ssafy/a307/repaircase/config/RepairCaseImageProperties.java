package com.ssafy.a307.repaircase.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 사례 이미지 조회 설정.
 *
 * <p><b>기본값을 두지 않는다.</b> 프로퍼티가 빠지면 조용히 도는 대신 기동에서 실패해야 한다 —
 * {@code AccidentImageProperties}·{@code EstimateValidationProperties} 와 같은 정책이다.
 *
 * <p>업로드용 값이 없는 것은 <b>서비스가 사례 이미지를 올리지 않기</b> 때문이다. 파이프라인이
 * 미리 적재해 둔 정적 데이터라 조회 URL 만 필요하다.
 *
 * @param downloadUrlMinutes 조회용 presigned GET URL 유효시간(분). 사례 목록은 화면이 열려
 *                           있는 동안 계속 보이므로 업로드 URL 보다 짧을 이유가 없다.
 *                           상한 1440(하루)은 오타 방어용이다
 */
@Validated
@ConfigurationProperties(prefix = "app.repair-case-image")
public record RepairCaseImageProperties(

        @Min(1) @Max(1440) int downloadUrlMinutes) {

    public Duration downloadUrlValidity() {
        return Duration.ofMinutes(downloadUrlMinutes);
    }
}
