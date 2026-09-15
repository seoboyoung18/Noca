package com.ssafy.a307.analysis.service;

import com.ssafy.a307.analysis.event.AnalysisJobFinishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 분석 결과가 <b>커밋된 뒤에</b> 단계를 적는다 (S15P21A307-383).
 *
 * <h2>왜 커밋 뒤인가 — 순서를 한쪽으로 고정한다</h2>
 *
 * <p>진행 조회 응답은 두 곳을 섞어 만든다. 작업 상태는 {@code analysis_job} 에서, 완료 단계
 * 수는 {@code analysis_stage} 행에서 온다. 두 쓰기가 어긋나는 방향은 둘인데 <b>나쁜 쪽이
 * 분명하다.</b>
 *
 * <ul>
 *   <li>단계가 먼저 보인다 → 화면이 <b>"4/4 완료" 인데 결과가 없는</b> 상태를 보여 준다.
 *       결과 저장이 뒤에 롤백되면 그 거짓이 그대로 남는다</li>
 *   <li>결과가 먼저 보인다 → {@code COMPLETED} 인데 {@code 0/4} 다. <b>지금과 같다.</b>
 *       이 스토리 이전의 동작이고, AI 재시도가 오면 저절로 메워진다</li>
 * </ul>
 *
 * <p>그래서 {@link TransactionPhase#AFTER_COMMIT} 이다. 결과가 롤백되면 이벤트가 소비되지
 * 않으므로 <b>단계만 남는 일이 없다.</b>
 *
 * <h2>왜 여기서 예외를 먹는가</h2>
 *
 * <p>커밋 뒤 콜백에서 던진 예외는 <b>커밋을 부른 쪽으로 전파된다.</b> 그대로 두면 결과는
 * 저장됐는데 AI 는 500 을 받고 같은 결과를 세 번 더 보낸다. 단계 기록이 실패해도 분석 결과
 * 저장을 막지 않는다는 것이 이 기능의 전제이므로 여기서 끊는다.
 *
 * <p>{@code try-catch} 를 트랜잭션 <b>바깥</b>에 둔 이유가 있다.
 * {@link AnalysisStageRecorder#recordFinished} 안에서 잡으면 제약 위반으로 이미
 * rollback-only 가 된 트랜잭션이 커밋 시점에 다시 던진다. 잡으려면 트랜잭션 경계 밖이어야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisJobFinishedListener {

    private final AnalysisStageRecorder recorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobFinished(AnalysisJobFinishedEvent event) {
        try {
            recorder.recordFinished(event);
        } catch (RuntimeException e) {
            // 진행 표시가 비는 것뿐이다. 결과는 이미 안전하다.
            log.warn("분석 단계 기록 실패 — 결과는 저장됐다. jobId={} failed={}",
                    event.jobId(), event.failed(), e);
        }
    }
}
