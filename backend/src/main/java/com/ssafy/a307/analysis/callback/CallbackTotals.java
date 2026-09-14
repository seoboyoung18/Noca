package com.ssafy.a307.analysis.callback;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 견적 총액 범위. 계약의 {@code totals}.
 *
 * <p>금액은 <b>원 단위 정수이며 부가세를 포함하지 않는다</b>(계약 "값 규칙"). 부가세를 뺀 것은
 * 원천 사례 견적서가 부가세 전 금액이라, 붙이면 그 근거와 어긋나기 때문이다.
 *
 * <p>{@code estimable: false} 면 이 객체 자체가 오지 않는다.
 */
public record CallbackTotals(

        @NotNull(message = "totals.min 은 필수입니다.")
        @PositiveOrZero(message = "금액은 음수일 수 없습니다.")
        Integer min,

        @NotNull(message = "totals.median 은 필수입니다.")
        @PositiveOrZero(message = "금액은 음수일 수 없습니다.")
        Integer median,

        @NotNull(message = "totals.max 은 필수입니다.")
        @PositiveOrZero(message = "금액은 음수일 수 없습니다.")
        Integer max) {
}
