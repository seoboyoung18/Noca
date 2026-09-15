package com.ssafy.a307.repairchecklist.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 사용자 항목의 문안 (S15P21A307-485). 추가와 수정이 같은 모양이라 하나를 쓴다.
 *
 * <p><b>{@code source} 를 받지 않는다.</b> 서버가 {@code USER} 로 고정한다 — 클라이언트가
 * {@code AI}·{@code COMMON} 을 자처하면 생성 이력이 거짓이 된다.
 * {@code displayOrder} 도 받지 않는다. 서버가 맨 뒤에 붙인다.
 *
 * <p><b>길이 상한을 검증으로 막지 않는다.</b> 500자를 넘기면 400 이 아니라
 * {@code RepairChecklistItem} 이 <b>잘라서 저장</b>한다({@code content VARCHAR(500)}).
 * prompt67 §2-1 · §3-1 이 그렇게 정했고, {@code ai(...)}·{@code common(...)} 이 이미 같은
 * {@code requireContent} 를 쓰고 있어 세 경로가 같은 규칙이 된다.
 *
 * <p>⚠️ 같은 화면의 <b>메모</b>({@code S15P21A307-483})는 반대로 {@code @Size} 로 400 을 낸다.
 * 두 필드의 초과 처리가 다르다 — 사람이 정할 문제라 {@code answer67} 8장에 적어 두었다.
 *
 * @param content 항목 문안. 비어 있으면 400 이다 — 빈 줄을 체크리스트에 넣을 이유가 없다
 */
public record RepairChecklistItemContentRequest(@NotBlank String content) {
}
