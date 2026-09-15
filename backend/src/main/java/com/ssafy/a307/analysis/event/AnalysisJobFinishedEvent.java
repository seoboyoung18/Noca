package com.ssafy.a307.analysis.event;

import java.time.Instant;

/**
 * 분석 작업 하나가 끝났다는 사실 (S15P21A307-383).
 *
 * <p>{@code AnalysisCallbackService} 가 발행하고 {@code AnalysisJobFinishedListener} 가
 * <b>결과 저장 트랜잭션이 커밋된 뒤에만</b> 받는다. 이벤트를 한 겹 둔 이유는 순서 때문이다 —
 * 단계 표시가 결과보다 먼저 보이면 화면이 "4/4 완료" 인데 결과가 없는 상태를 보여 준다.
 *
 * <p><b>엔티티를 싣지 않는다.</b> 소비자는 커밋 뒤에 도는데, 그때 발행 당시의 영속성 컨텍스트는
 * 이미 닫혀 있다. 식별자와 값만 싣고 소비자가 다시 읽는다.
 *
 * @param jobId      {@code analysis_job.job_id}
 * @param failed     {@code FAILED} 로 끝났는가. 사진 전부 제외({@code ALL_IMAGES_EXCLUDED})도
 *                   여기서는 실패다 — 작업 상태가 그렇기 때문이다
 * @param finishedAt 작업을 끝낸 시각. 단계의 {@code finished_at} 이 이 값과 같아야
 *                   "작업은 10:00 에 끝났는데 단계는 10:03 에 끝났다" 가 생기지 않는다
 */
public record AnalysisJobFinishedEvent(Long jobId, boolean failed, Instant finishedAt) {
}
