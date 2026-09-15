package com.ssafy.a307.analysis.request;

import com.ssafy.a307.accident.config.AccidentImageProperties;
import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.accident.repository.AccidentImageRepository;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.event.AnalysisJobFinishedEvent;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 분석 요청 한 건의 DB 쪽 일. <b>트랜잭션 경계를 위해 워커에서 떼어낸 빈이다</b>
 * ({@code RepairChecklistProcessor} 와 같은 이유).
 *
 * <h2>requestId 는 AI 에 보내기 전에 커밋한다</h2>
 *
 * <p>{@link #claim} 이 {@code PROCESSING} 과 {@code request_id} 를 함께 커밋한 뒤에야 워커가
 * AI 를 부른다. 순서가 반대면 AI 결과가 먼저 도착했을 때 결과 수신이 "기다리던 요청이 아니다"
 * 를 판정할 근거가 없고, 뒤늦게 적은 {@code PROCESSING} 이 끝난 작업을 덮는다.
 *
 * <p><b>AI 를 부르는 동안 트랜잭션을 잡고 있지 않는다.</b> {@link #payload} 는 읽기만 하고 닫힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisRequestProcessor {

    private final AnalysisJobRepository jobRepository;
    private final AccidentImageRepository imageRepository;
    private final AccidentImageProperties imageProperties;
    private final AnalysisRequestProperties properties;
    private final Optional<AccidentImageStoragePort> storagePort;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@code QUEUED} 인 건만 {@code PROCESSING} 으로 옮기며 멱등 키를 심는다.
     *
     * @return 내가 선점했으면 true. 0행이면 다른 워커가 가져갔거나 상태가 바뀐 것이다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long jobId, String requestId) {
        return jobRepository.claimQueued(jobId, requestId, Instant.now()) == 1;
    }

    /**
     * 선점한 건의 요청 본문을 만든다. 사진마다 {@link AnalysisRequestPayload#ANALYSIS_VARIANT}
     * 조회 URL 을 새로 서명한다 — URL 은 만료가 있어 저장해 두지 않는다.
     *
     * @throws AnalysisRequestPreparationException 저장소가 없거나 보낼 사진이 없다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AnalysisRequestPayload payload(Long jobId) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new AnalysisRequestPreparationException(
                        AnalysisRequestFailure.INTERNAL, "선점한 분석 작업이 사라졌다: " + jobId));
        AccidentImageStoragePort storage = storagePort
                .orElseThrow(() -> new AnalysisRequestPreparationException(
                        AnalysisRequestFailure.STORAGE_UNAVAILABLE, "사진 저장소가 설정되지 않았다"));

        Accident accident = job.getAccident();
        // 소유자 조건이 붙은 조회를 그대로 쓴다. 워커에는 세션이 없으므로 사고의 주인을 넘긴다.
        Long ownerId = accident.getVehicle().getMemberId();

        List<AnalysisRequestPayload.Image> images = new ArrayList<>();
        for (AccidentImage image : imageRepository.findAllOwned(accident.getAccidentId(), ownerId)) {
            image.asset(AnalysisRequestPayload.ANALYSIS_VARIANT).ifPresent(asset -> images.add(
                    AnalysisRequestPayload.Image.of(image, storage.createPresignedDownloadUrl(
                            asset.getS3Key(), imageProperties.downloadUrlValidity()))));
        }
        if (images.isEmpty()) {
            throw new AnalysisRequestPreparationException(
                    AnalysisRequestFailure.NO_IMAGE, "보낼 사진이 없다: jobId=" + jobId);
        }

        return AnalysisRequestPayload.of(job, accident, images, properties.callbackUrl(jobId));
    }

    /**
     * 실패를 기록한다. <b>이미 끝난 작업은 건드리지 않는다</b> — AI 결과가 먼저 도착해 끝난 작업을
     * 실패로 덮지 않기 위해서다.
     *
     * <p>결과 수신의 실패 callback 과 같은 종료 이벤트를 낸다. 그래야 진행 단계도 똑같이
     * {@code FAILED} 로 적힌다({@code AnalysisJobFinishedListener}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long jobId, String reason) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.finished()) {
                return;
            }
            Instant now = Instant.now();
            job.markFailed(reason, null, null, now);
            eventPublisher.publishEvent(new AnalysisJobFinishedEvent(jobId, true, now));
            log.info("분석 요청 실패로 종결: jobId={}, 사유={}", jobId, reason);
        });
    }
}
