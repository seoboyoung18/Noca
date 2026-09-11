package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimate.repository.EstimateQueryRepository.EstimateSummaryView;
import com.ssafy.a307.estimate.repository.NativeTimestamps;

import java.time.Instant;

/**
 * 사고 하나에 딸린 견적 목록의 한 줄. 재산정할 때마다 버전이 쌓이므로 이력 목록이 된다.
 *
 * <p>항목({@code items})은 담지 않는다 — 목록 화면은 총액과 버전만 보여 주고, 자세한 건
 * 상세 조회에서 받는다. 목록에 항목까지 담으면 버전 수 × 부위 수만큼 행이 딸려 온다.
 */
public record EstimateSummaryResponse(
        Long estimateId,
        short version,
        boolean estimable,
        Integer totalMin,
        Integer totalMedian,
        Integer totalMax,
        String confidenceGrade,
        Instant createdAt) {

    public static EstimateSummaryResponse from(EstimateSummaryView view) {
        return new EstimateSummaryResponse(
                view.getEstimateId(),
                view.getVersion(),
                Boolean.TRUE.equals(view.getEstimable()),
                view.getTotalMin(),
                view.getTotalMedian(),
                view.getTotalMax(),
                view.getConfidenceGrade(),
                NativeTimestamps.toInstant(view.getCreatedAt()));
    }
}
