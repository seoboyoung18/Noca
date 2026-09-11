package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimate.repository.EstimateQueryRepository.EstimateDetailView;
import com.ssafy.a307.estimate.repository.NativeTimestamps;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 견적 상세. 화면 한 장에 필요한 것을 한 번에 준다.
 *
 * @param estimable          산정 가능 여부. <b>false 면 금액 칸이 전부 null 이고
 *                           {@code nonEstimableReason} 만 있다</b> — 참조할 사례가 모자라
 *                           근거 없는 숫자를 내는 대신 사유를 밝힌 것이다
 * @param laborRate          공임 단가. 공임 = {@code standardHq × laborRate}
 * @param refCaseTotal       산정에 참조한 유사 사례 건수 합계
 * @param confidenceGrade    HIGH · MEDIUM · LOW. 산정 불가면 null 이다.
 *                           LOW 면 화면이 경고를 띄운다(S15P21A307-290)
 * @param items              파손 부위별 산정 결과. 부위 표시 순서대로 정렬돼 있다
 * @param notices            고지 문구. <b>지금은 항상 빈 배열이다</b> — 문구 테이블이
 *                           들어오면(S15P21A307-288) 채워진다
 */
public record EstimateResponse(
        Long estimateId,
        Long jobId,
        short version,
        boolean estimable,
        String nonEstimableReason,
        Integer laborRate,
        BigDecimal totalHq,
        Integer totalMin,
        Integer totalMedian,
        Integer totalMax,
        Integer refCaseTotal,
        String confidenceGrade,
        List<EstimateItemResponse> items,
        List<EstimateNotice> notices,
        Instant createdAt) {

    public static EstimateResponse of(EstimateDetailView view,
                                      List<EstimateItemResponse> items,
                                      List<EstimateNotice> notices) {
        return new EstimateResponse(
                view.getEstimateId(),
                view.getJobId(),
                view.getVersion(),
                Boolean.TRUE.equals(view.getEstimable()),
                view.getNonEstimableReason(),
                view.getLaborRate(),
                view.getTotalHq(),
                view.getTotalMin(),
                view.getTotalMedian(),
                view.getTotalMax(),
                view.getRefCaseTotal(),
                view.getConfidenceGrade(),
                items,
                notices,
                NativeTimestamps.toInstant(view.getCreatedAt()));
    }
}
