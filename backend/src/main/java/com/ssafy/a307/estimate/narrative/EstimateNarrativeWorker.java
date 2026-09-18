package com.ssafy.a307.estimate.narrative;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * {@code QUEUED} 로 접수된 견적 요약을 집어 처리하는 워커 (S15P21A307-537).
 *
 * <p><b>왜 폴링인가</b> — 접수는 견적 저장 트랜잭션에서 하고 생성은 여기서 한다. 이벤트나
 * {@code @Async} 로 부르면 재기동했을 때 진행 중이던 건이 영영 {@code QUEUED} 로 남는다.
 * 폴링은 다음 주기에 그냥 다시 집는다 — 이 저장소의 워커 넷이 모두 같은 형태다.
 *
 * <p><b>AI 결과 수신 스레드에서 부르지 않는 이유</b>가 여기 있다. 그 스레드는 AI 서버의 HTTP
 * 요청이고, LLM 한 번이 초 단위라 붙들면 AI 가 타임아웃으로 같은 결과를 세 번 더 보낸다.
 *
 * <p><b>프로퍼티로 켠다.</b> {@code app.estimate-narrative.enabled=true} 가 아니면 이 빈도
 * 스케줄러도 뜨지 않고, 접수된 요약은 {@code QUEUED} 에 머문다. 리포트는 그 자리를 비운 채
 * 그대로 나간다 — 요약은 리포트의 필수 구성이 아니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.estimate-narrative", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class EstimateNarrativeWorker {

    private final EstimateNarrativeRepository narrativeRepository;
    private final EstimateNarrativeProcessor processor;
    private final EstimateNarrativeWorkerProperties properties;

    @Scheduled(
            fixedDelayString = "${app.estimate-narrative.poll-interval:PT5S}",
            initialDelayString = "${app.estimate-narrative.initial-delay:PT20S}")
    public void pollOnce() {
        try {
            abandonStale();
            drainQueue();
        } catch (RuntimeException e) {
            // 주기 하나가 통째로 실패해도 스케줄러는 계속 돌아야 한다.
            log.error("견적 요약 워커 주기가 실패했다.", e);
        }
    }

    private void drainQueue() {
        List<Long> candidates = narrativeRepository.findQueuedIds(
                PageRequest.of(0, properties.batchSize()));
        if (candidates.isEmpty()) {
            return;
        }

        int processed = 0;
        for (Long estimateId : candidates) {
            if (!processor.claim(estimateId)) {
                continue;
            }
            processed++;
            runOne(estimateId);
        }
        log.debug("견적 요약 워커: 후보 {}건 중 {}건 선점", candidates.size(), processed);
    }

    /**
     * 한 건을 처리한다. <b>여기서 예외를 먹는다</b> — 한 건이 실패해도 같은 주기의 다음 건은
     * 처리돼야 한다.
     */
    private void runOne(Long estimateId) {
        try {
            processor.process(estimateId);
        } catch (EstimateNarrativeGenerationException e) {
            log.warn("견적 요약 실패: estimateId={}, 사유={}", estimateId, e.getFailure());
            recordFailure(estimateId, e.getFailure());
        } catch (RuntimeException e) {
            log.error("견적 요약 처리 중 예기치 못한 실패: estimateId={}", estimateId, e);
            recordFailure(estimateId, EstimateNarrativeFailure.INTERNAL);
        }
    }

    /** 멈춘 건은 되돌리지 않고 끝낸다 — 이유는 {@link EstimateNarrativeFailure#ABANDONED} 에 적었다. */
    private void abandonStale() {
        Instant threshold = Instant.now().minus(properties.processingTimeout());
        List<Long> stale = narrativeRepository.findStaleProcessingIds(
                threshold, PageRequest.of(0, properties.batchSize()));
        for (Long estimateId : stale) {
            log.warn("중단된 견적 요약을 실패로 종결한다: estimateId={}", estimateId);
            recordFailure(estimateId, EstimateNarrativeFailure.ABANDONED);
        }
    }

    /** 기록마저 실패하면 그 건은 다음 주기에 다시 걸린다. 주기를 멈추지는 않는다. */
    private void recordFailure(Long estimateId, EstimateNarrativeFailure failure) {
        try {
            processor.markFailed(estimateId, failure);
        } catch (RuntimeException e) {
            log.error("견적 요약 실패 기록에 실패했다: estimateId={}", estimateId, e);
        }
    }
}
