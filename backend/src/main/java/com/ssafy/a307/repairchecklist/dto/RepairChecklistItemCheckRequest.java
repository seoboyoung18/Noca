package com.ssafy.a307.repairchecklist.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 항목 체크·해제 (S15P21A307-483).
 *
 * <p><b>토글이 아니라 값을 받는다.</b> "뒤집어 달라" 로 만들면 요청이 두 번 도착했을 때 결과가
 * 보낸 쪽 기대와 달라진다 — 정비소 현장에서 통신이 불안정한 채로 쓰는 화면이라 재전송이 잦다.
 * 같은 값을 두 번 보내면 같은 상태로 끝나야 한다.
 *
 * @param checked {@code true} 면 완료, {@code false} 면 해제. <b>필수다</b> — 빠뜨리면 무엇을
 *                하려는지 알 수 없다
 */
public record RepairChecklistItemCheckRequest(@NotNull Boolean checked) {
}
