package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 계약의 {@code work_candidates[]} 항목 — <b>확정이 아니라 후보</b>다
 * ({@code work_decision} 이 {@code "CANDIDATE"} 고정).
 *
 * <p>4종({@code COATING}·{@code REPAIR}·{@code SHEET_METAL}·{@code EXCHANGE})뿐이며
 * <b>견적 어휘 13종과 다른 어휘다.</b> 섞으면 {@code ck_dp_method} 를 위반한다.
 */
public record WorkCandidate(

        @NotNull(message = "work_candidates[].code 는 필수입니다.")
        @Pattern(regexp = "COATING|REPAIR|SHEET_METAL|EXCHANGE",
                message = "work_candidates[].code 가 계약의 4종에 없습니다.")
        @JsonProperty("code")
        String code,

        @NotBlank(message = "work_candidates[].name_en 은 필수입니다.")
        @JsonProperty("name_en")
        String nameEn,

        @NotBlank(message = "work_candidates[].name_ko 는 필수입니다.")
        @JsonProperty("name_ko")
        String nameKo
) {
}
