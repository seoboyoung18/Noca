package com.ssafy.a307.repairchecklist.service;

import com.ssafy.a307.analysis.event.AnalysisJobFinishedEvent;
import com.ssafy.a307.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 분석 결과가 커밋되면 체크리스트를 자동으로 큐에 올린다 (S15P21A307-542).
 *
 * <h2>왜 자동인가</h2>
 *
 * <p>기획은 "견적을 받으면 체크리스트가 만들어져 있다" 이지만, 지금까지는 클라이언트가
 * {@code POST /api/accidents/{id}/repair-checklist} 를 <b>따로 보내야만</b> 큐에 들어갔다.
 * 화면 진입마다 요청을 보내는 것은 프론트의 안전장치일 뿐이고, 만드는 책임은 서버에 있다.
 *
 * <h2>왜 재원님의 {@code AnalysisJobFinishedListener} 에 얹지 않았나</h2>
 *
 * <p>{@code AFTER_COMMIT} 리스너 하나가 던지면 <b>같은 이벤트의 다른 리스너가 돌지 않는다.</b>
 * 단계 기록과 체크리스트는 서로를 막을 이유가 없으므로 각자의 리스너에서 각자 예외를 먹는다.
 *
 * <p>패키지도 여기가 맞다. 체크리스트는 이미 분석 결과를 읽어 프롬프트를 만든다
 * ({@link RepairChecklistProcessor}). 반대로 {@code analysis} 가 체크리스트를 알게 하면
 * 두 패키지가 서로를 가리킨다.
 *
 * <h2>왜 예외를 먹나</h2>
 *
 * <p>커밋 뒤 콜백에서 던진 예외는 <b>커밋을 부른 쪽으로 전파된다.</b> 그대로 두면 분석 결과는
 * 저장됐는데 AI 서버는 500 을 받고 같은 결과를 세 번 더 보낸다. <b>체크리스트는 분석 결과의
 * 부산물이지 전제가 아니다.</b>
 *
 * <p>특히 {@link BusinessException} 셋은 자동 경로에서 <b>정상</b>이라 경고로 남기지 않는다.
 *
 * <ul>
 *   <li>409 — 이미 완성된 체크리스트가 있다. 덮어쓰는 것은 재생성({@code S15P21A307-486})의
 *       몫이고, 조용히 덮으면 사용자가 체크해 둔 항목이 사라진다</li>
 *   <li>503 — {@code GMS_KEY} 가 없다. 키를 넣으면 다음 분석부터 다시 만들어진다</li>
 *   <li>404 — 커밋과 이 호출 사이에 사고가 지워졌다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepairChecklistAutoRequestListener {

    private final RepairChecklistAutoRequestService autoRequestService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobFinished(AnalysisJobFinishedEvent event) {
        if (event.failed()) {
            // 실패한 분석에는 만들지 않는다. 화면이 보여 줄 것은 실패 사유와 재시도이고,
            // 재시도(S15P21A307-161)가 성공하면 그때 이 자리로 다시 온다.
            return;
        }

        try {
            autoRequestService.requestFor(event.jobId());
        } catch (BusinessException e) {
            log.info("체크리스트 자동 요청 생략. jobId={} code={} message={}",
                    event.jobId(), e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            // 분석 결과는 이미 안전하다. 사용자는 체크리스트 화면에서 직접 요청할 수 있다.
            log.warn("체크리스트 자동 요청 실패 — 분석 결과는 저장됐다. jobId={}", event.jobId(), e);
        }
    }
}
