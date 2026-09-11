package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 계약의 {@code detection.part}. {@code code} 가 {@code part_code} 마스터의 PK 와 같은 어휘다
 * ({@code pipeline/standardization/catalog.py} 의 {@code PARTS}, 좌우 구분은 {@code _L}·{@code _R} 접미사).
 *
 * <p>{@code group}·{@code side} 는 계약이 열거로 막고 있으나 <b>적재가 쓰지 않는다</b> —
 * {@code damaged_part} 에 해당 컬럼이 없고, 같은 정보가 이미 {@code part_code.layout_zone} 에 있다.
 * 그래도 필드를 지우지 않는 이유는 계약이 {@code additionalProperties: false} 라
 * <b>빠뜨리면 역직렬화가 아니라 검증이 어긋나기</b> 때문이고, 나중에 쓸 때 계약을 다시 읽지
 * 않아도 되게 하기 위해서다.
 */
public record DetectedPart(

        @NotNull(message = "part.code 는 필수입니다.")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "part.code 는 UPPER_SNAKE 여야 합니다.")
        @JsonProperty("code")
        String code,

        @NotBlank(message = "part.name_en 은 필수입니다.")
        @JsonProperty("name_en")
        String nameEn,

        @NotBlank(message = "part.name_ko 는 필수입니다.")
        @JsonProperty("name_ko")
        String nameKo,

        @NotNull(message = "part.group 은 필수입니다.")
        @Pattern(regexp = "BODY_PANEL|BUMPER|GLASS|LAMP|MIRROR|WHEEL|UNDERBODY",
                message = "part.group 이 계약의 7종에 없습니다.")
        @JsonProperty("group")
        String group,

        @NotNull(message = "part.side 는 필수입니다.")
        @Pattern(regexp = "LEFT|RIGHT|CENTER", message = "part.side 는 LEFT·RIGHT·CENTER 중 하나입니다.")
        @JsonProperty("side")
        String side,

        @NotNull(message = "part.raw_label 은 필수입니다.")
        @JsonProperty("raw_label")
        String rawLabel
) {
}
