package com.ssafy.a307.repairquestion.service;

import com.ssafy.a307.repairquestion.config.RepairQuestionWorkerProperties;
import com.ssafy.a307.repairquestion.domain.RepairQuestionFailure;
import com.ssafy.a307.repairquestion.domain.RepairQuestionGenerationException;
import com.ssafy.a307.repairquestion.repository.RepairQuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * {@code QUEUED} 로 접수된 질문 목록 생성을 집어 처리하는 워커 (S15P21A307-477).
 *
 * <p><b>왜 폴링인가</b> — {@code @Async} 나 이벤트 리스너를 쓰면 재기동했을 때 진행 중이던 건이
 * 영영 {@code QUEUED} 로 남는다. 아무도 다시 집어 주지 않기 때문이다. 폴링은 다음 주기에 그냥
 * 다시 집는다. 이 저장소에 워커 패턴을 두 가지 만들지 않는다 — {@code RepairChecklistWorker} ·
 * {@code EstimateValidationWorker} · {@code ValidationPdfWorker} 가 모두 같은 형태다.
 *
 * <p><b>프로퍼티로 켠다.</b> {@code app.repair-question.enabled=true} 가 아니면 이 빈도
 * 스케줄러도 뜨지 않고, 접수된 생성은 {@code QUEUED} 에 머문다. 테스트 환경에서는 꺼 둔다 —
 * 워커가 임의로 돌면 다른 테스트가 흔들린다.
 *
 * <p><b>{@code repair_question} 에는 큐 인덱스가 없다.</b> {@code estimate_validation} 의
 * {@code ix_ev_queue} 같은 부분 인덱스가 이 테이블에는 없어 대기 건 조회가 풀스캔이다. 사고당
 * 한 행이고 대기 상태로 남는 기간이 짧아 지금 규모에서는 문제가 되지 않는다. 스키마를 고치는
 * 것은 이 작업의 범위가 아니라({@code S15P21A307-510} 에서 끝났다) 보고서에만 적었다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.repair-question", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class RepairQuestionWorker {

    private final RepairQuestionRepository questionRepository;
    private final RepairQuestionProcessor processor;
    private final RepairQuestionWorkerProperties properties;

    /**
     * 한 주기. {@code fixedDelay} 라 이전 주기가 끝난 뒤에 다음 주기가 시작한다 —
     * {@code fixedRate} 면 LLM 호출이 밀릴 때 주기가 겹쳐 같은 건을 여러 스레드가 노린다.
     */
    @Scheduled(
            fixedDelayString = "${app.repair-question.poll-interval:PT5S}",
            initialDelayString = "${app.repair-question.initial-delay:PT15S}")
    public void pollOnce() {
        try {
            reclaimStale();
            drainQueue();
        } catch (RuntimeException e) {
            // 주기 하나가 통째로 실패해도 스케줄러는 계속 돌아야 한다.
            log.error("질문 목록 생성 워커 주기가 실패했다.", e);
        }
    }

    private void drainQueue() {
        List<Long> candidates = questionRepository.findQueuedIds(
                PageRequest.of(0, properties.batchSize()));
        if (candidates.isEmpty()) return;

        int processed = 0;
        for (Long questionId : candidates) {
            if (!processor.claim(questionId)) continue;
            processed++;
            runOne(questionId);
        }
        log.debug("질문 목록 생성 워커: 후보 {}건 중 {}건 선점", candidates.size(), processed);
    }

    /**
     * 건 하나를 처리한다. <b>여기서 예외가 밖으로 나가면 안 된다</b> — 한 건의 실패가 같은 주기의
     * 나머지를 막는다.
     */
    private void runOne(Long questionId) {
        try {
            processor.process(questionId);
        } catch (RepairQuestionGenerationException e) {
            log.warn("질문 목록 생성 실패: questionId={}, 사유={}", questionId, e.failure(), e);
            recordFailure(questionId, e.failure());
        } catch (RuntimeException e) {
            log.error("질문 목록 생성 중 예상치 못한 오류: questionId={}", questionId, e);
            recordFailure(questionId, RepairQuestionFailure.INTERNAL);
        }
    }

    private void recordFailure(Long questionId, RepairQuestionFailure failure) {
        try {
            processor.markFailed(questionId, failure);
        } catch (RuntimeException e) {
            // 실패 기록마저 실패하면 건은 PROCESSING 으로 남는다. 고아 회수가 다음 주기에 집는다.
            log.error("질문 목록 실패 기록에 실패했다: questionId={}", questionId, e);
        }
    }

    /**
     * 고아 {@code PROCESSING} 을 정리한다 — 처리 도중 프로세스가 사라진 건이다.
     *
     * <p><b>{@code QUEUED} 로 되돌리지 않고 {@code FAILED} 로 종결한다.</b>
     * {@code repair_question} 에는 시도 횟수를 셀 열이 없어, 횟수를 못 세는 채로 큐에 돌려보내면
     * <b>영영 실패하는 건이 매 주기 GMS 크레딧을 태우며 무한히 재시도된다.</b> 끝내 두면 사용자가
     * 생성을 다시 요청해 큐로 되돌릴 수 있다({@link RepairQuestionRequestService}) — 재시도 횟수를
     * 사람이 쥐는 셈이다. {@code RepairChecklistWorker} 가 같은 제약에서 같은 결론에 이르렀다.
     */
    private void reclaimStale() {
        Instant threshold = Instant.now().minus(properties.processingTimeout());
        List<Long> stale = questionRepository.findStaleProcessingIds(
                threshold, PageRequest.of(0, properties.batchSize()));
        for (Long questionId : stale) {
            log.warn("중단된 질문 목록 생성을 실패로 종결한다: questionId={}", questionId);
            recordFailure(questionId, RepairQuestionFailure.ABANDONED);
        }
    }
}
