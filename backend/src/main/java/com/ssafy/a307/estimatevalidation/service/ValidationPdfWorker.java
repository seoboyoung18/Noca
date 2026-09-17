package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.estimatevalidation.config.ValidationPdfProperties;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationReport;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * {@code QUEUED} 인 검증 리포트를 집어 PDF 를 만드는 워커.
 *
 * <p><b>패턴은 검증 워커와 같다</b> — {@code @Scheduled} 폴링 + 조건부 UPDATE 선점.
 * 한 저장소에 두 가지 워커 패턴을 만들지 않는다. {@code @Async} 나 이벤트를 쓰면 재기동했을 때
 * 진행 중이던 건이 영영 {@code QUEUED} 로 남는다.
 *
 * <p><b>선점에 {@code FOR UPDATE SKIP LOCKED} 를 쓰지 않는 이유</b>는 테스트가 H2 에서
 * 돌기 때문이다. 조건부 UPDATE 는 경쟁 상태에서 똑같이 안전하고 두 DB 에서 모두 동작한다.
 *
 * <p><b>배치를 작게 잡는다.</b> 한 건마다 LLM 호출이 들어가고 그 계층의 read timeout 이
 * 60초, 재시도가 2회다. 최악의 경우 한 건에 2분이 걸리므로 배치가 크면 한 주기가 끝나지 않는다.
 *
 * <p><b>프로퍼티로 켠다.</b> {@code app.validation-pdf.enabled=true} 가 아니면 이 빈도
 * 처리기도 뜨지 않고, PDF 다운로드는 지금까지처럼 409 다. 테스트 환경에서는 꺼 둔다 —
 * 워커가 임의로 돌면 다른 테스트가 흔들린다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.validation-pdf", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ValidationPdfWorker {

    /** 사용자에게 보일 수 있는 분류 문구. 내부 예외 메시지를 넣지 않는다. */
    static final String FAILURE_GENERATION = "PDF를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.";
    static final String FAILURE_ABANDONED = "PDF 생성이 반복 실패해 중단되었습니다. 고객센터에 문의해 주세요.";
    static final String FAILURE_STALLED = "PDF 생성이 중단되었습니다. 다시 시도해 주세요.";
    /** 설정 문제라 다시 시도해도 같다. 사용자가 할 수 있는 일이 없으므로 문의를 안내한다. */
    static final String FAILURE_NO_STORAGE = "PDF 보관소가 준비되지 않았습니다. 고객센터에 문의해 주세요.";

    private final EstimateValidationReportRepository reportRepository;
    private final ValidationPdfProcessor processor;
    private final ValidationPdfProperties properties;

    /**
     * 한 주기. {@code fixedDelay} 라 이전 주기가 끝난 뒤에 다음 주기가 시작한다 —
     * {@code fixedRate} 면 LLM 호출로 처리가 밀릴 때 주기가 겹쳐 같은 건을 여러 스레드가 노린다.
     */
    @Scheduled(
            fixedDelayString = "${app.validation-pdf.poll-interval:PT10S}",
            initialDelayString = "${app.validation-pdf.initial-delay:PT15S}")
    public void pollOnce() {
        try {
            reclaimStale();
            abandonExhausted();
            drainQueue();
        } catch (RuntimeException e) {
            // 주기 하나가 통째로 실패해도 스케줄러는 계속 돌아야 한다.
            log.error("검증 PDF 워커 주기가 실패했다.", e);
        }
    }

    private void drainQueue() {
        List<Long> candidates = reportRepository.findQueuedIds(PageRequest.of(0, properties.batchSize()));
        if (candidates.isEmpty()) return;

        int processed = 0;
        for (Long validationId : candidates) {
            if (!processor.claim(validationId)) continue;
            processed++;
            runOne(validationId);
        }
        log.debug("검증 PDF 워커: 후보 {}건 중 {}건 선점", candidates.size(), processed);
    }

    /**
     * 건 하나를 처리한다. <b>여기서 예외가 밖으로 나가면 안 된다</b> — 한 건의 실패가 같은
     * 주기의 나머지를 막는다.
     */
    private void runOne(Long validationId) {
        try {
            processor.process(validationId);
        } catch (RuntimeException e) {
            boolean noStorage = e instanceof ValidationPdfProcessor.MissingDocumentStorageException;
            log.warn("검증 PDF 생성 실패: validationId={}, 원인={}",
                    validationId, e.getClass().getSimpleName(), e);
            try {
                if (noStorage) {
                    // 설정 문제라 다시 시도해도 같다 — 큐로 되돌리지 않는다 (S15P21A307-526).
                    processor.markFailedWithoutRetry(validationId, FAILURE_NO_STORAGE);
                } else {
                    processor.markFailed(validationId, FAILURE_GENERATION);
                }
            } catch (RuntimeException recordFailure) {
                // 기록마저 실패하면 PROCESSING 으로 남는다. 고아 회수가 다음 주기에 집는다.
                log.error("검증 PDF 실패 기록에 실패했다: validationId={}", validationId, recordFailure);
            }
        }
    }

    /**
     * 고아 {@code PROCESSING} 을 정리한다 — 처리 도중 프로세스가 사라진 건이다.
     *
     * <p>{@code retry_count} 가 있으므로 <b>큐로 되돌려도 무한 반복되지 않는다.</b>
     * 되돌린 뒤 상한에 닿으면 {@link #abandonExhausted()} 가 종결한다.
     * 검증 워커가 {@code retry_count} 열이 없어 곧바로 {@code FAILED} 로 끝낸 것과 다른 점이다.
     */
    private void reclaimStale() {
        Instant threshold = Instant.now().minus(properties.processingTimeout());
        List<Long> stale = reportRepository.findStaleProcessingIds(
                threshold, PageRequest.of(0, properties.batchSize()));
        for (Long validationId : stale) {
            log.warn("중단된 PDF 생성을 회수한다: validationId={}", validationId);
            processor.markFailed(validationId, FAILURE_STALLED);
        }
    }

    /**
     * 재시도 상한에 닿은 채 큐에 남은 건을 종결한다.
     *
     * <p>선점 쿼리가 상한 조건 때문에 이 건들을 집지 못하므로, 빼지 않으면 매 주기 후보로만
     * 조회되며 큐가 영영 비지 않는다.
     */
    private void abandonExhausted() {
        List<Long> exhausted = reportRepository.findExhaustedIds(
                EstimateValidationReport.MAX_RETRY_COUNT, PageRequest.of(0, properties.batchSize()));
        for (Long validationId : exhausted) {
            processor.abandonExhausted(validationId, FAILURE_ABANDONED);
        }
    }
}
