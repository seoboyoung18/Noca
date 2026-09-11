package com.ssafy.a307.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 표시명과 순서만 바꾼다. canonical code 는 요청에 없다. */
public record RepairCodeUpdateRequest(
        @NotBlank(message = "표시명은 필수입니다.")
        @Size(max = 50, message = "표시명은 50자 이하여야 합니다.")
        String displayName,

        @NotNull(message = "표시 순서는 필수입니다.")
        @Min(value = 0, message = "표시 순서는 0 이상이어야 합니다.")
        @Max(value = 32767, message = "표시 순서는 32767 이하여야 합니다.")
        Integer displayOrder,

        @NotNull(message = "version 은 필수입니다.")
        @Min(value = 0, message = "version 은 0 이상이어야 합니다.")
        Long version) {

    public RepairCodeUpdateRequest {
        displayName = displayName == null ? null : displayName.strip();
    }
}
