package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimate.domain.BasisNarrative;
import com.ssafy.a307.estimate.domain.FallbackStage;
import com.ssafy.a307.estimate.domain.FallbackStageDisplay;
import com.ssafy.a307.estimate.domain.RefCondition;
import com.ssafy.a307.estimate.domain.RepairMethodDisplay;
import com.ssafy.a307.estimate.repository.EstimateQueryRepository.EstimateBasisItemView;

/**
 * 견적 항목 하나의 산정 근거.
 *
 * <p><b>저장 구조를 그대로 내보내지 않는다.</b> {@link RefCondition} 은 DB 에 담기는 모양이고
 * 이 record 는 화면에 나가는 모양이다. 지금은 거의 같지만, 저장 구조가 바뀔 때마다 FE 계약이
 * 함께 흔들리면 안 된다.
 *
 * @param refCaseCount   참조한 유사 사례 건수. {@code estimate_item} 의 컬럼이라 근거 스냅샷이
 *                       비어 있어도 이 값은 있다
 * @param basisAvailable 근거 스냅샷이 있는지. <b>{@code false} 면 화면은 "근거 없음"을 명시해야
 *                       한다</b> — 명세서 51행이 "근거가 부족한 항목은 그 사실이 명시된다"고
 *                       요구한다. AI 가 근거를 보내지 않았거나 산정하지 못한 견적이면
 *                       {@code false} 다
 * @param narrative      한 줄 근거 문구(S15P21A307-284). 참조한 사례가 없으면 {@code null} 이다
 * @param fallbackStage  조건을 어디까지 넓혔는지. 이 값이 낮은 신뢰도 경고의 근거가 된다
 *                       (S15P21A307-291). 같은 값을 {@code LowConfidenceRule} 이 함께 본다 —
 *                       근거와 경고가 어긋나지 않도록 한 번만 파싱해 양쪽에 넘긴다
 */
public record EstimateBasisItemResponse(
        Long estimateItemId,
        String partCode,
        String partNameKo,
        String repairMethod,
        String repairMethodDisplayName,
        Integer refCaseCount,
        boolean basisAvailable,
        String narrative,
        FallbackStage fallbackStage,

        /**
         * 완화 단계를 사람이 읽는 말로 (S15P21A307-547). "동일 차량명" · "비슷한 수리비대" ·
         * "전체 사례". <b>모르는 값이면 {@code null}</b> 이고 화면은 그 자리를 비운다.
         *
         * <p>{@code repairMethodDisplayName} 과 같은 이유로 서버가 만든다 — PDF 는 서버가
         * 그리는 문서라 그 안의 문구를 화면이 정할 수 없다.
         */
        String fallbackStageDisplayName,

        RefCondition.CostDistribution costDistribution,
        Integer refYearFrom,
        Integer refYearTo,
        RefCondition.RepairMethodReason repairMethodReason) {

    /**
     * 다듬어진 근거 문장으로 바꾼 사본 (S15P21A307-537).
     *
     * <p>LLM 은 <b>규칙이 만든 문장을 다듬기만 한다.</b> 다듬은 결과에 원문에 없던 숫자가
     * 섞이면 {@code EstimateNarrativeGenerator} 가 버리고, 그러면 여기로 {@code null} 이
     * 와서 규칙 문장이 그대로 남는다 — 사용자가 보는 근거는 언제나 저장된 값과 맞는다.
     *
     * @param polished 다듬어진 문장. {@code null} 이면 지금 문장을 그대로 둔다
     */
    public EstimateBasisItemResponse withNarrative(String polished) {
        if (polished == null || polished.isBlank()) {
            return this;
        }
        return new EstimateBasisItemResponse(estimateItemId, partCode, partNameKo, repairMethod,
                repairMethodDisplayName, refCaseCount, basisAvailable, polished, fallbackStage,
                fallbackStageDisplayName, costDistribution, refYearFrom, refYearTo, repairMethodReason);
    }

    public static EstimateBasisItemResponse of(EstimateBasisItemView view, RefCondition basis) {
        String displayName = RepairMethodDisplay.displayNameOf(view.getRepairMethod());

        return new EstimateBasisItemResponse(
                view.getEstimateItemId(),
                view.getPartCode(),
                view.getPartNameKo(),
                view.getRepairMethod(),
                displayName,
                view.getRefCaseCount(),
                !basis.isEmpty(),
                BasisNarrative.of(basis, view.getPartNameKo(), displayName, view.getRefCaseCount()),
                basis.fallbackStage(),
                FallbackStageDisplay.displayNameOf(basis.fallbackStage()),
                basis.costDistribution(),
                basis.refYearFrom(),
                basis.refYearTo(),
                basis.repairMethodReason());
    }
}
