package com.ssafy.a307.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 활성 상태만 바꾼다. 비활성화와 재활성화가 같은 요청이고 감사 로그에서만 갈린다.
 *
 * @param version 조회할 때 받은 값. 상태 전환도 lost update 가 생길 수 있다 —
 *                한 관리자가 끄는 사이 다른 관리자가 이름을 바꾸면 둘 중 하나가 사라진다
 */
public record StatusUpdateRequest(
        @NotNull(message = "active 는 필수입니다.")
        Boolean active,

        @NotNull(message = "version 은 필수입니다.")
        @Min(value = 0, message = "version 은 0 이상이어야 합니다.")
        Long version) {
}
