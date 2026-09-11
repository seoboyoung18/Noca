package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** 계약의 {@code $defs/point}. 픽셀 좌표이며 음수가 없다. */
public record PolygonPoint(

        @NotNull(message = "point.x 는 필수입니다.")
        @DecimalMin(value = "0", message = "point.x 는 0 이상이어야 합니다.")
        @JsonProperty("x")
        BigDecimal x,

        @NotNull(message = "point.y 는 필수입니다.")
        @DecimalMin(value = "0", message = "point.y 는 0 이상이어야 합니다.")
        @JsonProperty("y")
        BigDecimal y
) {
}
