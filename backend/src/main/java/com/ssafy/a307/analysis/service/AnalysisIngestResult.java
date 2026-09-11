package com.ssafy.a307.analysis.service;

import java.util.List;

/**
 * 적재 한 번의 결과. <b>건너뛴 것을 숨기지 않는다.</b>
 *
 * <p>이미지 한 장의 검출 수와 실제로 저장된 행 수가 다를 수 있고, 그 차이는 오류가 아니라
 * 근거 부족이다. 호출자가 그 차이를 그대로 볼 수 있어야 "분석은 됐는데 화면에 없다" 를
 * 설명할 수 있다.
 */
public record AnalysisIngestResult(
        Long jobId,
        int detectionCount,
        int persistedCount,
        List<Deferred> deferred
) {

    public AnalysisIngestResult {
        deferred = deferred == null ? List.of() : List.copyOf(deferred);
    }

    /** 저장하지 않은 검출 하나. {@code partCode} 는 계약이 준 값 그대로다. */
    public record Deferred(String partCode, DeferralReason reason) {
    }

    public int deferredCount() {
        return deferred.size();
    }
}
