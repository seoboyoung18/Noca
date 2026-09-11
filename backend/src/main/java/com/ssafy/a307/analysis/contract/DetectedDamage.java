package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 계약의 {@code detection.damage}. {@code code} 는 UPPER_SNAKE 4종이며
 * {@link com.ssafy.a307.analysis.domain.AnalysisDamageType} 이 DDL 표기로 바꾼다.
 */
public record DetectedDamage(

        @NotNull(message = "damage.code 는 필수입니다.")
        @Pattern(regexp = "SCRATCHED|SEPARATED|CRUSHED|BREAKAGE",
                message = "damage.code 가 계약의 4종에 없습니다.")
        @JsonProperty("code")
        String code,

        @NotBlank(message = "damage.name_en 은 필수입니다.")
        @JsonProperty("name_en")
        String nameEn,

        @NotBlank(message = "damage.name_ko 는 필수입니다.")
        @JsonProperty("name_ko")
        String nameKo,

        @NotNull(message = "damage.raw_label 은 필수입니다.")
        @JsonProperty("raw_label")
        String rawLabel
) {
}
