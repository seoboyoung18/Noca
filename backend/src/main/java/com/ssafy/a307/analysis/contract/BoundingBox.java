package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 계약의 {@code geometry.bbox}. <b>객체 {@code {x,y,width,height}} 다 — 배열이 아니다.</b>
 * 배열 {@code [x,y,w,h]} 로 가정하면 역직렬화가 통째로 깨진다.
 *
 * <p>{@code width}·{@code height} 는 계약이 {@code exclusiveMinimum: 0} 이다 —
 * <b>0 도 안 된다.</b> 넓이 0 인 상자는 검출이 아니다.
 */
public record BoundingBox(

        @NotNull(message = "bbox.x 는 필수입니다.")
        @DecimalMin(value = "0", message = "bbox.x 는 0 이상이어야 합니다.")
        @JsonProperty("x")
        BigDecimal x,

        @NotNull(message = "bbox.y 는 필수입니다.")
        @DecimalMin(value = "0", message = "bbox.y 는 0 이상이어야 합니다.")
        @JsonProperty("y")
        BigDecimal y,

        @NotNull(message = "bbox.width 는 필수입니다.")
        @DecimalMin(value = "0", inclusive = false, message = "bbox.width 는 0보다 커야 합니다.")
        @JsonProperty("width")
        BigDecimal width,

        @NotNull(message = "bbox.height 는 필수입니다.")
        @DecimalMin(value = "0", inclusive = false, message = "bbox.height 는 0보다 커야 합니다.")
        @JsonProperty("height")
        BigDecimal height
) {
}
