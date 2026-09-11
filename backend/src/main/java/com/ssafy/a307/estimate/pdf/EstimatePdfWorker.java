package com.ssafy.a307.estimate.pdf;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * {@code QUEUED} 견적 PDF 를 집어 만드는 워커 (S15P21A307-392).
 *
 * <p><b>검증 PDF 워커와 같은 패턴이다</b> — {@code @Scheduled} 폴링 + 조건부 UPDATE 선점. 한 저장소에
 * 두 가지 워커 패턴을 만들지 않는다. {@code @Async} 나 이벤트는 재기동하면 진행 중이던 건이 영영 남는다.
 *
 * <p>{@code app.estimate-pdf.enabled=true} 일 때만 뜬다. 테스트에서는 꺼 둔다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.estimate-pdf", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class EstimatePdfWorker {

    /** 사용자에게 보일 수 있는 분류 문구. 내부 예외 메시지를 넣지 않는다. */
    static final String FAILURE_GENERATION = "PDF를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.";
    static final String FAILURE_ABANDONED = "PDF 생성이 반복 실패해 중단되었습니다. 고객센터에 문의해 주세요.";
    static final String FAILURE_STALLED = "PDF 생성이 중단되었습니다. 다시 시도해 주세요.";
    static final String FAILURE_NO_STORAGE = "PDF 보관소가 준비되지 않았습니다. 고객센터에 문의해 주세요.";

    private final EstimatePdfRepository pdfRepository;
    private final EstimatePdfProcessor processor;
    private final EstimatePdfProperties properties;

    /** {@code fixedDelay} — 이전 주기가 끝나야 다음이 시작한다. 주기가 겹쳐 같은 건을 노리지 않게. */
    @Scheduled(
            fixedDelayString = "${app.estimate-pdf.poll-interval:PT10S}",
            initialDelayString = "${app.estimate-pdf.initial-delay:PT15S}")
    public void pollOnce() {
        try {
            reclaimStale();
            abandonExhausted();
            drainQueue();
        } catch (RuntimeException e) {
            // 주기 하나가 통째로 실패해도 스케줄러는 계속 돌아야 한다.
            log.error("견적 PDF 워커 주기가 실패했다.", e);
        }
    }

    private void drainQueue() {
        List<Long> candidates = pdfRepository.findQueuedIds(
                EstimatePdfProcessor.MAX_RETRY_COUNT, properties.batchSize());
        for (Long reportId : candidates) {
            if (processor.claim(reportId)) {
                runOne(reportId);
            }
        }
    }

    /** 건 하나. 예외가 밖으로 나가면 같은 주기의 나머지가 막힌다. */
    private void runOne(Long reportId) {
        try {
            processor.process(reportId);
        } catch (RuntimeException e) {
            String reason = e instanceof EstimatePdfProcessor.MissingStorageException
                    ? FAILURE_NO_STORAGE : FAILURE_GENERATION;
            log.warn("견적 PDF 생성 실패: reportId={}, 원인={}", reportId, e.getClass().getSimpleName(), e);
            try {
                processor.markFailed(reportId, reason);
            } catch (RuntimeException recordFailure) {
                // 기록마저 실패하면 PROCESSING 으로 남는다. 고아 회수가 다음 주기에 집는다.
                log.error("견적 PDF 실패 기록에 실패했다: reportId={}", reportId, recordFailure);
            }
        }
    }

    /** 처리 도중 프로세스가 사라진 PROCESSING. retry_count 가 있어 되돌려도 무한 반복되지 않는다. */
    private void reclaimStale() {
        Instant threshold = Instant.now().minus(properties.processingTimeout());
        for (Long reportId : pdfRepository.findStaleProcessingIds(threshold, properties.batchSize())) {
            log.warn("중단된 견적 PDF 생성을 회수한다: reportId={}", reportId);
            processor.markFailed(reportId, FAILURE_STALLED);
        }
    }

    private void abandonExhausted() {
        for (Long reportId : pdfRepository.findExhaustedIds(
                EstimatePdfProcessor.MAX_RETRY_COUNT, properties.batchSize())) {
            processor.abandonExhausted(reportId, FAILURE_ABANDONED);
        }
    }
}
