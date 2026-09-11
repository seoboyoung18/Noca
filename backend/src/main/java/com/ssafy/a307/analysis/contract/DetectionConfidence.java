package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 계약의 {@code detection.confidence}. <b>키는 항상 있고 값이 {@code null} 일 수 있다.</b>
 * 키 없음과 값 {@code null} 은 다른 상태이며, 키가 없으면 계약 위반이라 역직렬화 단계에서 걸린다.
 *
 * <p>DDL 의 {@code damaged_part.confidence} 는 컬럼 하나이고 NOT NULL 이라 둘을 하나로 줄여야 한다.
 * {@link #effective()} 가 <b>둘 중 작은 값</b>을 돌려주고, 둘 다 비면 {@link Optional#empty()} 다.
 * 근거는 {@link com.ssafy.a307.analysis.entity.DamagedPart} Javadoc 에 있다.
 */
public record DetectionConfidence(

        @DecimalMin(value = "0", message = "confidence.part 는 0 이상이어야 합니다.")
        @DecimalMax(value = "1", message = "confidence.part 는 1 이하여야 합니다.")
        @JsonProperty("part")
        BigDecimal part,

        @DecimalMin(value = "0", message = "confidence.damage 는 0 이상이어야 합니다.")
        @DecimalMax(value = "1", message = "confidence.damage 는 1 이하여야 합니다.")
        @JsonProperty("damage")
        BigDecimal damage
) {

    /**
     * 저장할 하나의 값. <b>약한 쪽을 따른다.</b>
     *
     * @return 둘 다 {@code null} 이면 {@link Optional#empty()} — 호출자가 적재를 보류해야 한다
     */
    public Optional<BigDecimal> effective() {
        if (part == null && damage == null) {
            return Optional.empty();
        }
        if (part == null) {
            return Optional.of(damage);
        }
        if (damage == null) {
            return Optional.of(part);
        }
        return Optional.of(part.min(damage));
    }
}
