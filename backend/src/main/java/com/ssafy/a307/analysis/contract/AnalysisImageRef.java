package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 계약의 {@code image} 블록.
 *
 * <p><b>{@code id} 는 파이프라인 식별자다.</b> 계약이 {@code ["string","integer","null"]} 로 두고
 * 있어 숫자로 올 수도 있고 비어 있을 수도 있다. <b>{@code accident_image.image_id} 와 같다고
 * 가정하지 않는다</b> — 같은 숫자가 와도 우연이다. 적재가 어느 사고 이미지에 붙는지는
 * 호출자가 별도로 넘겨야 하며, 이 값을 FK 로 쓰면 남의 이미지에 결과가 붙을 수 있다.
 *
 * <p>그래서 타입을 {@code String} 으로 받는다. Jackson 이 정수도 문자열로 받아 주므로 계약의
 * 세 가지 타입을 모두 소화하면서, <b>숫자처럼 보여도 숫자로 쓰지 못하게</b> 만든다.
 */
public record AnalysisImageRef(

        @JsonProperty("id")
        String id,

        @NotNull(message = "image.width 는 필수입니다.")
        @Min(value = 1, message = "image.width 는 1 이상이어야 합니다.")
        @JsonProperty("width")
        Integer width,

        @NotNull(message = "image.height 는 필수입니다.")
        @Min(value = 1, message = "image.height 는 1 이상이어야 합니다.")
        @JsonProperty("height")
        Integer height
) {
}
