package com.ssafy.a307.accident.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 사고 이력 숨김 · 되돌리기 (S15P21A307-554).
 *
 * <p><b>숨기기와 되돌리기를 한 요청으로 받는다.</b> 화면이 토글이고, 두 엔드포인트로 나누면
 * 지금 상태를 모를 때 어느 쪽을 불러야 할지 화면이 먼저 판단해야 한다.
 *
 * @param hidden {@code true} 면 목록에서 감추고 {@code false} 면 되돌린다. <b>필수다</b> —
 *               비워 보내면 무엇을 원하는지 알 수 없으므로 기본값을 정하지 않는다
 */
public record AccidentHiddenRequest(

        @NotNull(message = "hidden 은 필수입니다.")
        Boolean hidden) {
}
