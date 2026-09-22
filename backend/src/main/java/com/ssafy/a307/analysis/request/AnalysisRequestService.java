package com.ssafy.a307.analysis.request;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.accident.repository.AccidentImageRepository;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.analysis.callback.InternalApiProperties;
import com.ssafy.a307.analysis.dto.AnalysisProgressResponse;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 분석 요청 접수 (S15P21A307-156).
 *
 * <h2>접수만 하고 즉시 응답한다</h2>
 *
 * <p>작업을 {@code QUEUED} 로 만들고 끝이다. AI 호출은 폴링 워커({@link AnalysisRequestWorker})가
 * 한다 — {@code @Async} 나 이벤트를 쓰지 않는 이유는 {@code RepairChecklistRequestService} 와 같다.
 *
 * <h2>판정 순서</h2>
 *
 * <ol>
 *   <li>없는 사고·남의 사고 → 404 (둘을 구분하지 않는다)</li>
 *   <li>AI 주소·토큰·사진 저장소 중 하나라도 없음 → 503. 애초에 보낼 수 없는 요청은 받지 않는다</li>
 *   <li>이 사고에 작업이 이미 있음 → 409. 진행 중이면 같은 요청을 두 번 보낸 것이고, 끝났으면
 *       다시 분석하는 것이다 — 실패한 분석은 {@link #retry}(S15P21A307-161)로 다시 시도하고,
 *       완료된 분석은 계약상 다시 돌리지 않는다("사용자 재분석 없음")</li>
 *   <li>분석에 보낼 사진이 없음 → 400</li>
 * </ol>
 *
 * <p>"예상 소요 시간" 은 주지 않는다. 근거가 되는 측정(S15P21A307-159)이 아직 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisRequestService {

    private final AccidentRepository accidentRepository;
    private final AccidentImageRepository accidentImageRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisRequestProperties properties;
    private final InternalApiProperties internalApiProperties;

    /** 없으면 사진 URL 을 만들 수 없다. <b>여기서 호출하지 않는다</b> — 있는지만 본다. */
    private final Optional<AccidentImageStoragePort> storagePort;

    @Transactional
    public AnalysisProgressResponse request(Long memberId, Long accidentId) {
        Accident accident = ownedAvailableAccident(memberId, accidentId);

        analysisJobRepository.findByAccidentIdAndMemberId(accidentId, memberId).stream()
                .findFirst()
                .ifPresent(AnalysisRequestService::rejectExisting);

        requireAnalyzableImage(memberId, accidentId);

        AnalysisJob job = enqueue(AnalysisJob.queued(accident, Instant.now()));
        log.info("분석 요청 접수: accidentId={}, jobId={}", accidentId, job.getJobId());
        return AnalysisProgressResponse.of(job, List.of(), List.of(), false);
    }

    /**
     * 실패한 분석을 다시 시도한다 (S15P21A307-161). 같은 사고에 {@code QUEUED} 작업을 새로 만든다
     * — 왜 되살리지 않는지는 {@link AnalysisJob#retryOf} 에 적었다.
     *
     * <h2>판정 순서</h2>
     *
     * <ol>
     *   <li>없는 사고·남의 사고 → 404, 설정 없음 → 503 — {@link #request} 와 같다</li>
     *   <li>분석을 요청한 적이 없음 → 409. 재시도가 아니라 분석 요청을 불러야 한다</li>
     *   <li><b>가장 최근 작업</b>이 {@code FAILED} 가 아님 → 409. 진행 중이면 두 번 누른 것이고,
     *       끝났으면 완료된 분석을 다시 돌리는 것이다 — AI 계약이 "사용자 재분석 없음" 이다</li>
     *   <li>이미 {@value AnalysisJob#MAX_RETRY_COUNT} 번 재시도함 → 409</li>
     *   <li>보낼 사진이 없음 → 400</li>
     * </ol>
     *
     * <p><b>실패 사유는 가리지 않는다.</b> 사진이 전부 제외돼 실패했다면 사진을 다시 올린 뒤
     * 재시도하도록 화면이 안내한다(S15P21A307-160). 같은 사진으로 다시 눌러도 횟수 제한이 막는다.
     */
    @Transactional
    public AnalysisProgressResponse retry(Long memberId, Long accidentId) {
        ownedAvailableAccident(memberId, accidentId);

        AnalysisJob latest = analysisJobRepository.findByAccidentIdAndMemberId(accidentId, memberId).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "재시도할 분석이 없습니다. 분석을 먼저 요청해 주세요."));
        rejectNotRetriable(latest);

        requireAnalyzableImage(memberId, accidentId);

        AnalysisJob job = enqueue(AnalysisJob.retryOf(latest, Instant.now()));
        log.info("분석 재시도 접수: accidentId={}, failedJobId={}, jobId={}, retryCount={}",
                accidentId, latest.getJobId(), job.getJobId(), job.getRetryCount());
        return AnalysisProgressResponse.of(job, List.of(), List.of(), false);
    }

    private Accident ownedAvailableAccident(Long memberId, Long accidentId) {
        Accident accident = accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));

        if (!available()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "분석 요청을 사용할 수 없습니다.");
        }
        return accident;
    }

    private void requireAnalyzableImage(Long memberId, Long accidentId) {
        boolean hasImage = accidentImageRepository.findAllOwned(accidentId, memberId).stream()
                .anyMatch(AnalysisRequestPayload::isAnalyzable);
        if (!hasImage) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "분석할 사진이 없습니다. 사진 업로드를 먼저 끝내 주세요.");
        }
    }

    private AnalysisJob enqueue(AnalysisJob job) {
        try {
            // flush 까지 해야 ux_job_inflight 위반이 여기서 드러난다. 두 요청이 동시에 위의 조회를
            // 통과해도 인덱스가 하나만 남긴다.
            return analysisJobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 분석이 진행 중입니다.");
        }
    }

    private static void rejectNotRetriable(AnalysisJob latest) {
        if (latest.retriable()) {
            return;
        }
        String message = switch (latest.getStatus()) {
            case QUEUED, PROCESSING -> "이미 분석이 진행 중입니다.";
            case COMPLETED -> "분석이 끝난 사고는 다시 시도할 수 없습니다.";
            case FAILED -> "재시도 횟수(" + AnalysisJob.MAX_RETRY_COUNT + "회)를 모두 사용했습니다.";
        };
        throw new BusinessException(ErrorCode.CONFLICT, message);
    }

    private boolean available() {
        return properties.configured() && internalApiProperties.configured() && storagePort.isPresent();
    }

    private static void rejectExisting(AnalysisJob latest) {
        boolean inFlight = latest.getStatus() == AnalysisJobStatus.QUEUED
                || latest.getStatus() == AnalysisJobStatus.PROCESSING;
        throw new BusinessException(ErrorCode.CONFLICT,
                inFlight ? "이미 분석이 진행 중입니다." : "이미 분석을 요청한 사고입니다.");
    }
}
