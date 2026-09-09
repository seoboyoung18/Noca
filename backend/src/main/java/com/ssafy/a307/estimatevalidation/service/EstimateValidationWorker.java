package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.estimatevalidation.config.EstimateWorkerProperties;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * {@code QUEUED} 로 접수된 견적서 파일 검증을 집어 처리하는 워커.
 *
 * <p><b>왜 폴링인가</b> — {@code @Async} 나 이벤트 리스너를 쓰면 재기동했을 때 진행 중이던 건이
 * 영영 {@code QUEUED} 로 남는다. 아무도 다시 집어 주지 않기 때문이다. 폴링은 다음 주기에 그냥
 * 다시 집는다. 정본 DDL 의 부분 인덱스
 * {@code ix_ev_queue (status, created_at) WHERE status IN ('QUEUED','PROCESSING')} 가
 * 정확히 이 패턴을 위해 이미 만들어져 있다.
 *
 * <p><b>왜 조건부 UPDATE 인가</b> — 팀 핵심 쿼리 8-1 은 {@code FOR UPDATE SKIP LOCKED} 를 쓰지만
 * 테스트가 H2 에서 돌아 그 문법에 기대면 워커를 검증할 수 없다. "조회 → 건별 조건부 UPDATE →
 * 영향 행 수 확인" 은 경쟁 상태에서 똑같이 안전하고 두 DB 에서 모두 동작한다.
 * 다중 인스턴스 최적화({@code SKIP LOCKED})는 후속 과제다.
 *
 * <p><b>프로퍼티로 켠다.</b> {@code app.estimate-worker.enabled=true} 가 아니면 이 빈도
 * {@code EstimateFileProcessor} 도 뜨지 않고, 파일 검증은 지금까지처럼 {@code QUEUED} 에 머문다.
 * 테스트 환경에서는 꺼 둔다 — 워커가 임의로 돌면 다른 테스트가 흔들린다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.estimate-worker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class EstimateValidationWorker {

    private final EstimateValidationRepository validationRepository;
    private final EstimateFileProcessor processor;
    private final EstimateWorkerProperties properties;

    /**
     * 한 주기. {@code fixedDelay} 라 이전 주기가 끝난 뒤에 다음 주기가 시작한다 —
     * {@code fixedRate} 면 처리가 밀릴 때 주기가 겹쳐 같은 건을 여러 스레드가 노린다.
     */
    @Scheduled(
            fixedDelayString = "${app.estimate-worker.poll-interval:PT5S}",
            initialDelayString = "${app.estimate-worker.initial-delay:PT10S}")
    public void pollOnce() {
        try {
            reclaimStale();
            drainQueue();
        } catch (RuntimeException e) {
            // 주기 하나가 통째로 실패해도 스케줄러는 계속 돌아야 한다.
            log.error("견적서 검증 워커 주기가 실패했다.", e);
        }
    }

    private void drainQueue() {
        List<Long> candidates = validationRepository.findQueuedIds(PageRequest.of(0, properties.batchSize()));
        if (candidates.isEmpty()) return;

        int processed = 0;
        for (Long validationId : candidates) {
            if (!processor.claim(validationId)) continue;
            processed++;
            runOne(validationId);
        }
        log.debug("견적서 검증 워커: 후보 {}건 중 {}건 선점", candidates.size(), processed);
    }

    /**
     * 건 하나를 처리한다. <b>여기서 예외가 밖으로 나가면 안 된다</b> — 한 건의 실패가 같은 주기의
     * 나머지를 막는다. 요구사항 22행이 이미지에 대해 요구한 개별 재시도와 같은 판단이다.
     */
    private void runOne(Long validationId) {
        try {
            processor.process(validationId);
        } catch (EstimateProcessingException e) {
            log.warn("견적서 검증 실패: validationId={}, 사유={}", validationId, e.failure(), e);
            recordFailure(validationId, e.failure());
        } catch (RuntimeException e) {
            log.error("견적서 검증 중 예상치 못한 오류: validationId={}", validationId, e);
            recordFailure(validationId, EstimateValidationFailure.INTERNAL);
        }
    }

    private void recordFailure(Long validationId, EstimateValidationFailure failure) {
        try {
            processor.markFailed(validationId, failure);
        } catch (RuntimeException e) {
            // 실패 기록마저 실패하면 건은 PROCESSING 으로 남는다. 고아 회수가 다음 주기에 집는다.
            log.error("검증 실패 기록에 실패했다: validationId={}", validationId, e);
        }
    }

    /**
     * 고아 {@code PROCESSING} 을 정리한다 — 처리 도중 프로세스가 사라진 건이다.
     *
     * <p><b>{@code QUEUED} 로 되돌리지 않고 {@code FAILED} 로 종결한다.</b>
     * {@code estimate_validation} 에는 재시도 횟수를 셀 열이 없고
     * ({@code retry_count} 는 {@code estimate_validation_report} 쪽에만 있다) DDL 변경은 이 작업의
     * 범위가 아니다. 횟수를 못 세는 채로 큐에 돌려보내면 <b>영영 실패하는 건이 매 주기 자원을
     * 먹으며 무한히 재시도된다.</b> 끝내고 사용자에게 다시 등록하라고 알리는 편이 낫다.
     * 재시도를 자동으로 하려면 열이 하나 필요하다 — answer35 6장 후속 항목이다.
     *
     * <p>판정 기준이 {@code created_at} 인 것도 같은 제약 때문이다. 상태가 바뀐 시각을 담는 열이
     * 없어 "접수된 지 오래됐는데 아직 PROCESSING" 으로 볼 수밖에 없다. 그래서 임계값을 넉넉히 잡는다.
     */
    private void reclaimStale() {
        Instant threshold = Instant.now().minus(properties.processingTimeout());
        List<Long> stale = validationRepository.findStaleProcessingIds(
                threshold, PageRequest.of(0, properties.batchSize()));
        for (Long validationId : stale) {
            log.warn("중단된 검증을 실패로 종결한다: validationId={}", validationId);
            recordFailure(validationId, EstimateValidationFailure.ABANDONED);
        }
    }
}
