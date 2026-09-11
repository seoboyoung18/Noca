package com.ssafy.a307.admin.dto;

import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * <b>{@code partCode} 는 받지 않는다.</b> 경로 변수로 대상만 고르고, 코드 자체는 바꿀 수 없다.
 * 바꿔야 하면 새 코드를 등록하고 매핑을 옮긴 뒤 기존 코드를 비활성화한다.
 *
 * <p><b>{@code displayOrder} 는 중복을 허용한다.</b> 정본에 UNIQUE 가 없고, 같은 순서면
 * 부품 코드 오름차순으로 안정 정렬한다 — 순서를 하나 끼워 넣으려고 56행을 다시 번호 매기게
 * 만들지 않기 위해서다.
 */
public record PartCodeUpdateRequest(
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

        @NotNull(message = "codeScope 는 필수입니다.")
        PartCodeScope codeScope,

        @NotNull(message = "version 은 필수입니다.")
        @Min(value = 0, message = "version 은 0 이상이어야 합니다.")
        Long version) {

    public PartCodeUpdateRequest {
        nameKo = nameKo == null ? null : nameKo.strip();
        layoutZone = layoutZone == null ? null : layoutZone.strip();
    }
}
