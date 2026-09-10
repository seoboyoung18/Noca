package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimate.domain.BasisNarrative;
import com.ssafy.a307.estimate.domain.FallbackStage;
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
 *                       요구한다. 산정 로직(S15P21A307-256·257)이 들어오기 전에는 항상
 *                       {@code false} 다
 * @param narrative      한 줄 근거 문구(S15P21A307-284). 참조한 사례가 없으면 {@code null} 이다
 * @param fallbackStage  조건을 어디까지 넓혔는지. 이 값이 낮은 신뢰도 경고의 근거가 된다
 *                       (S15P21A307-291, 이번 범위 아님)
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
        RefCondition.CostDistribution costDistribution,
        Integer refYearFrom,
        Integer refYearTo,
        RefCondition.RepairMethodReason repairMethodReason) {

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
                basis.costDistribution(),
                basis.refYearFrom(),
                basis.refYearTo(),
                basis.repairMethodReason());
    }
}
