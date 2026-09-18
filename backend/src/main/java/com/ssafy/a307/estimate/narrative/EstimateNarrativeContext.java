package com.ssafy.a307.estimate.narrative;

import java.util.List;

/**
 * 요약 지시문에 실을 값 (S15P21A307-537). <b>전부 이미 확정된 값이다.</b>
 *
 * <p>LLM 이 이 값들을 바꾸지 못한다 — 여기 담긴 숫자는 지시문에 그대로 적히고, 응답에서 원문에
 * 없던 숫자가 섞인 문장은 {@link EstimateNarrativeGenerator} 가 버린다.
 *
 * @param estimable          산정된 견적인가. 아니면 금액 대신 사유를 설명해야 한다
 * @param nonEstimableReason 산정 불가 사유 코드. 산정된 견적이면 {@code null}
 * @param items              산정된 항목. 산정 불가면 빈 목록
 * @param unresolved         총액에서 빠진 부위 (S15P21A307-534). 없으면 빈 목록
 */
public record EstimateNarrativeContext(
        String manufacturer,
        String modelName,
        Short modelYear,
        boolean estimable,
        String nonEstimableReason,
        String confidenceGrade,
        Integer totalMin,
        Integer totalMedian,
        Integer totalMax,
        Integer refCaseTotal,
        List<ItemView> items,
        List<UnresolvedView> unresolved) {

    public EstimateNarrativeContext {
        items = items == null ? List.of() : List.copyOf(items);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    /**
     * 항목 하나.
     *
     * @param basisNarrative 규칙이 만든 근거 문장({@code BasisNarrative}). <b>LLM 은 이 문장을
     *                       다듬을 뿐 새로 쓰지 않는다</b> — 근거가 없으면 {@code null} 이고,
     *                       그때는 다듬을 것도 없다
     */
    public record ItemView(String partCode, String partNameKo, String repairMethodDisplayName,
                           Integer itemMedian, Integer refCaseCount, String basisNarrative) {
    }

    /** @param reasonDisplayName "근거 사례 부족" 처럼 사람이 읽는 사유. 모르는 코드면 {@code null} */
    public record UnresolvedView(String partCode, String partNameKo, String reasonDisplayName) {
    }

    /** 다듬을 근거 문장이 있는 부위 코드. 프롬프트와 검증이 같은 목록을 본다. */
    public List<String> partCodesWithBasis() {
        return items.stream()
                .filter(item -> item.basisNarrative() != null && !item.basisNarrative().isBlank())
                .map(ItemView::partCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
    }
}
