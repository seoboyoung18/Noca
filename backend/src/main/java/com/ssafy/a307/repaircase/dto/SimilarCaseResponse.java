package com.ssafy.a307.repaircase.dto;

import java.util.List;

/**
 * 견적 항목 하나가 참고한 유사 사례 목록.
 *
 * <p>항목별로 주는 이유 — 앞범퍼 사례와 헤드램프 사례는 서로 다른 사례다. 화면의
 * "이 사례들 보기" 도 항목 근거 안에 있다.
 *
 * @param refCaseCount 통계 산정에 쓴 <b>전체</b> 건수. {@code cases} 길이와 다르다 —
 *                     화면에는 대표 사례만 내린다
 * @param fallbackStage 조건을 어디까지 넓혀 찾은 사례인지. 이 값이 근거의 강도를 가른다
 */
public record SimilarCaseResponse(
        Long estimateItemId,
        String partNameKo,
        String repairMethod,
        String repairMethodDisplayName,
        Integer refCaseCount,
        String fallbackStage,
        List<SimilarCaseItemResponse> cases) {
}
