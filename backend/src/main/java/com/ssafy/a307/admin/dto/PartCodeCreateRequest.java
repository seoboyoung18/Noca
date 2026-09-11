package com.ssafy.a307.admin.dto;

import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param partCode  <b>등록 후 바뀌지 않는다.</b> {@code damaged_part}·{@code repair_cost_stat}·
 *                  {@code estimate_validation_item}·{@code part_name_mapping} 이 이 문자열을
 *                  그대로 들고 있다
 * @param codeScope 생략하면 {@code EXTENDED} 다 — AI 라벨은 모델을 다시 학습해야 늘어나므로
 *                  관리자가 추가하는 코드는 사실상 전부 확장 코드다
 */
public record PartCodeCreateRequest(
        @NotBlank(message = "부품 코드는 필수입니다.")
        @Size(max = 50, message = "부품 코드는 50자 이하여야 합니다.")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                message = "부품 코드는 대문자 영문으로 시작하고 대문자·숫자·밑줄만 쓸 수 있습니다.")
        String partCode,

        @NotBlank(message = "부품명은 필수입니다.")
        @Size(max = 50, message = "부품명은 50자 이하여야 합니다.")
        String nameKo,

        @NotBlank(message = "레이아웃 구역은 필수입니다.")
        @Pattern(regexp = "^(FRONT|REAR|SIDE_L|SIDE_R|TOP|UNDER)$",
                message = "레이아웃 구역은 FRONT·REAR·SIDE_L·SIDE_R·TOP·UNDER 중 하나여야 합니다.")
        String layoutZone,

        @NotNull(message = "표시 순서는 필수입니다.")
        @Min(value = 0, message = "표시 순서는 0 이상이어야 합니다.")
        @Max(value = 32767, message = "표시 순서는 32767 이하여야 합니다.")
        Integer displayOrder,

        PartCodeScope codeScope) {

    public PartCodeCreateRequest {
        partCode = partCode == null ? null : partCode.strip();
        nameKo = nameKo == null ? null : nameKo.strip();
        layoutZone = layoutZone == null ? null : layoutZone.strip();
        codeScope = codeScope == null ? PartCodeScope.EXTENDED : codeScope;
    }
}
