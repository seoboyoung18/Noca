package com.ssafy.a307.analysis.request;

import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code QUEUED} 분석 작업을 집어 AI 서버에 보내는 워커 (S15P21A307-156).
 *
 * <p><b>왜 폴링인가</b> — {@code RepairChecklistWorker} 와 같다. 재기동해도 대기 건을 다음 주기에
 * 다시 집는다. {@code ix_job_queue} 부분 인덱스가 이 조회를 위해 있다.
 *
 * <p><b>프로퍼티로 켠다.</b> {@code app.analysis-request.enabled=true} 가 아니면 이 빈도
 * 스케줄러도 뜨지 않고, 접수된 분석은 {@code QUEUED} 에 머문다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.analysis-request", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AnalysisRequestWorker {

    private final AnalysisJobRepository jobRepository;
    private final AnalysisRequestProcessor processor;
    private final AiAnalysisClient client;
    private final AnalysisRequestProperties properties;

    /** {@code fixedDelay} 라 이전 주기가 끝난 뒤에 다음 주기가 시작한다 — 주기가 겹치지 않는다. */
    @Scheduled(
            fixedDelayString = "${app.analysis-request.poll-interval:PT3S}",
            initialDelayString = "${app.analysis-request.initial-delay:PT15S}")
    public void pollOnce() {
        try {
            reclaimStale();
            drainQueue();
        } catch (RuntimeException e) {
            // 주기 하나가 통째로 실패해도 스케줄러는 계속 돌아야 한다.
            log.error("분석 요청 워커 주기가 실패했다.", e);
        }
    }

    private void drainQueue() {
        List<Long> candidates = jobRepository.findQueuedIds(PageRequest.of(0, properties.batchSize()));
        for (Long jobId : candidates) {
            if (!processor.claim(jobId, newRequestId())) {
                continue;
            }
            send(jobId);
        }
    }

    /** 건 하나를 보낸다. <b>예외가 밖으로 나가면 안 된다</b> — 같은 주기의 나머지를 막는다. */
    private void send(Long jobId) {
        try {
            AnalysisRequestPayload payload = processor.payload(jobId);
            client.analyze(payload);
            log.info("AI 에 분석을 맡겼다: jobId={}, requestId={}, 사진={}장",
                    jobId, payload.requestId(), payload.images().size());
        } catch (AiAnalysisException e) {
            log.warn("AI 가 분석 요청을 받지 않았다: jobId={}, 사유={}", jobId, e.failureCode(), e);
            recordFailure(jobId, e.failureCode());
        } catch (AnalysisRequestPreparationException e) {
            log.warn("분석 요청을 준비하지 못했다: jobId={}, 사유={}", jobId, e.failure());
            recordFailure(jobId, e.failure().name());
        } catch (RuntimeException e) {
            log.error("분석 요청 중 예상치 못한 오류: jobId={}", jobId, e);
            recordFailure(jobId, AnalysisRequestFailure.INTERNAL.name());
        }
    }

    private void recordFailure(Long jobId, String reason) {
        try {
            processor.markFailed(jobId, reason);
        } catch (RuntimeException e) {
            // 실패 기록마저 실패하면 PROCESSING 으로 남는다. 제한 시간이 지나면 reclaimStale 이 집는다.
            log.error("분석 요청 실패 기록에 실패했다: jobId={}", jobId, e);
        }
    }

    /**
     * AI 에 보냈는데 제한 시간이 지나도 결과가 없는 건을 끝낸다.
     *
     * <p><b>{@code QUEUED} 로 되돌리지 않는다.</b> 다시 보낼지는 S15P21A307-161 이 정한다.
     * 결과가 이 뒤에 도착하면 결과 수신은 끝난 작업으로 보고 저장하지 않는다.
     */
    private void reclaimStale() {
        Instant threshold = Instant.now().minus(properties.processingTimeout());
        for (Long jobId : jobRepository.findStaleProcessingIds(
                threshold, PageRequest.of(0, properties.batchSize()))) {
            log.warn("결과가 오지 않은 분석을 종결한다: jobId={}", jobId);
            recordFailure(jobId, AnalysisRequestFailure.ABANDONED.name());
        }
    }

    /** 계약 예시는 12자리 hex 다. 길이는 {@code request_id VARCHAR(64)} 안이면 된다. */
    static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
