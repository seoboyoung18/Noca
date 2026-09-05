package com.ssafy.a307.guide.dto;

import java.util.List;

/**
 * 체크리스트 한 단계. Story 의 "단계별 카드" 하나에 대응한다.
 *
 * <p>{@code items} 는 문자열 배열이다 — 항목 식별자를 두지 않는다. 완료 체크 상태를 서버가
 * 저장하지 않으므로 서버가 항목을 식별할 일이 없기 때문이다. FE 가 로컬 저장 키로 쓸
 * 식별자가 필요해지면 그때 계약을 확장한다 (answer10.md 6장).
 */
public record ChecklistStep(
        int order,
        String title,
        List<String> items
) {
}
