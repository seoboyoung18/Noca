package com.ssafy.a307.repairchecklist.dto;

import java.util.List;

/**
 * 체크리스트 진행률 (S15P21A307-482 · -483).
 *
 * <h2>퍼센트를 주지 않는다. 두 수를 준다</h2>
 *
 * <p>{@code -482} 본문이 <b>"진행률(완료/전체)"</b> 이라고 적었다. 퍼센트가 아니라 두 수다.
 * 나눗셈은 화면이 하면 되고, 서버가 미리 나누면 <b>0/0 을 어떻게 표시할지</b>·소수점을 몇 자리로
 * 자를지 같은 표현 결정을 서버가 떠안는다. {@code AnalysisProgressResponse} 가 같은 판단을
 * 이미 했다 — "퍼센트를 담을 열이 정본 DDL 어디에도 없다 … 없는 값을 서버가 만들어 내지 않는다".
 *
 * <h2>열을 만들지 않고 센다</h2>
 *
 * <p>{@code repair_checklist} 에 진행률 열이 없는 것은 {@code S15P21A307-509} 의 결정이다 —
 * 비정규화하면 체크·해제·추가·삭제·재생성 다섯 경로가 전부 그 값을 맞춰야 한다. 조회가 항목을
 * 이미 읽으므로 <b>여기서 세는 데 추가 질의가 들지 않는다.</b>
 *
 * @param completed 체크된 항목 수
 * @param total     전체 항목 수. 아직 생성 전이면 0 이다
 */
public record RepairChecklistProgress(int completed, int total) {

    public static final RepairChecklistProgress EMPTY = new RepairChecklistProgress(0, 0);

    public static RepairChecklistProgress of(List<RepairChecklistItemResponse> items) {
        if (items == null || items.isEmpty()) {
            return EMPTY;
        }
        int completed = (int) items.stream().filter(RepairChecklistItemResponse::checked).count();
        return new RepairChecklistProgress(completed, items.size());
    }
}
