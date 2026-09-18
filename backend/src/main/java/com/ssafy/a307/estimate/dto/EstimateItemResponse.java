package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimate.domain.RepairMethodDisplay;
import com.ssafy.a307.estimate.repository.EstimateQueryRepository.EstimateItemView;

import java.math.BigDecimal;

/**
 * 견적 항목 하나. 파손 부위 한 곳에 대한 산정 결과다.
 *
 * <p>별도 API 를 두지 않고 견적 조회 응답에 담는다(S15P21A307-261) — 항목 없는 견적 화면이
 * 없어 항상 함께 필요한데, 나누면 왕복만 늘어난다.
 *
 * @param partNameKo    화면에 쓰는 한글 부위명. {@code part_code} 마스터에서 온다
 * @param partCostMedian 부품비. <b>원천 견적서에 부품비가 없어 null 일 수 있다</b> —
 *                       그때는 공임 기준으로 산정했다는 고지가 함께 나가야 한다
 * @param refCaseCount  이 항목을 산정하며 참조한 유사 사례 수. 근거 표시에 쓴다
 * @param lowConfidence 참조 사례가 적거나 조건을 완화해 산정했다는 뜻이다.
 *                      화면은 이 항목에 경고를 붙인다(S15P21A307-290)
 */
public record EstimateItemResponse(
        Long estimateItemId,
        String partCode,
        String partNameKo,
        String layoutZone,
        String damageType,
        String repairMethod,
        String repairMethodDisplayName,
        BigDecimal standardHq,
        Integer partCostMedian,
        Integer laborCostMedian,

        /** 도장 재료비 (S15P21A307-547). 도장을 하지 않는 수리 방식이면 {@code null} 이다. */
        Integer paintMaterialCost,
        Integer itemMin,
        Integer itemMedian,
        Integer itemMax,
        Integer refCaseCount,
        boolean lowConfidence) {

    public static EstimateItemResponse from(EstimateItemView view) {
        return new EstimateItemResponse(
                view.getEstimateItemId(),
                view.getPartCode(),
                view.getPartNameKo(),
                view.getLayoutZone(),
                view.getDamageType(),
                view.getRepairMethod(),
                RepairMethodDisplay.displayNameOf(view.getRepairMethod()),
                view.getStandardHq(),
                view.getPartCostMedian(),
                view.getLaborCostMedian(),
                view.getPaintMaterialCost(),
                view.getItemMin(),
                view.getItemMedian(),
                view.getItemMax(),
                view.getRefCaseCount(),
                Boolean.TRUE.equals(view.getLowConfidence()));
    }
}
