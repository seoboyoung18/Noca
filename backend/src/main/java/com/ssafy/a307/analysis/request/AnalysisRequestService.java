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
 *       다시 분석하는 것이다 — 재시도는 S15P21A307-161 의 몫이고 계약도 "사용자 재분석 없음" 이다</li>
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
        Accident accident = accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));

        if (!available()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "분석 요청을 사용할 수 없습니다.");
        }

        analysisJobRepository.findByAccidentIdAndMemberId(accidentId, memberId).stream()
                .findFirst()
                .ifPresent(AnalysisRequestService::rejectExisting);

        boolean hasImage = accidentImageRepository.findAllOwned(accidentId, memberId).stream()
                .anyMatch(AnalysisRequestPayload::isAnalyzable);
        if (!hasImage) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "분석할 사진이 없습니다. 사진 업로드를 먼저 끝내 주세요.");
        }

        AnalysisJob job;
        try {
            // flush 까지 해야 ux_job_inflight 위반이 여기서 드러난다. 두 요청이 동시에 위의 조회를
            // 통과해도 인덱스가 하나만 남긴다.
            job = analysisJobRepository.saveAndFlush(AnalysisJob.queued(accident, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 분석이 진행 중입니다.");
        }

        log.info("분석 요청 접수: accidentId={}, jobId={}", accidentId, job.getJobId());
        return AnalysisProgressResponse.of(job, List.of(), List.of());
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
