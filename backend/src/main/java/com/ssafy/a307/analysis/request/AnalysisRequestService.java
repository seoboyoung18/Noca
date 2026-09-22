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
import com.ssafy.a307.analysis.service.PartSelectionRule;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.repository.EstimateQueryRepository;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
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
 *       완료된 분석은 계약상 다시 돌리지 않는다("사용자 재분석 없음"). 부품을 찾지 못해 산정하지
 *       못한 분석만 부위를 골라 {@link #resolvePart}(S15P21A307-570)로 다시 돌린다</li>
 *   <li>분석에 보낼 사진이 없음 → 400</li>
 * </ol>
 *
 * <p>"예상 소요 시간" 은 주지 않는다. 근거가 되는 측정(S15P21A307-159)이 아직 없다.
 *
 * <p>분석을 다시 요청하는 길은 둘이다 — 실패한 분석의 {@link #retry}, 부품을 못 찾은 분석에 부위를
 * 골라 주는 {@link #resolvePart}. 처음 요청까지 셋이 소유·설정·사진·동시 접수 판정을 함께 쓴다.
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

    private final EstimateQueryRepository estimateQueryRepository;
    private final PartCodeRepository partCodeRepository;
    private final PartSelectionRule partSelectionRule;

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

    /**
     * 부품을 찾지 못해 산정하지 못한 견적에 사용자가 고른 부위로 다시 분석한다 (S15P21A307-570).
     * 같은 사고에 {@code QUEUED} 작업을 새로 만들고 고른 부위를 적는다 — 워커가 AI 요청에 싣는다.
     *
     * <h2>견적 기준으로 받는다</h2>
     *
     * <p>화면(S15P21A307-567)이 산정 불가 견적을 보다가 부위를 고르므로 견적 id 를 들고 있다.
     * 사진이 전부 제외된 분석(작업 {@code FAILED})도 AI 가 {@code PART_NOT_RESOLVED} 견적을 보내고
     * 우리가 저장하므로, 부위 선택 대상 두 경우가 모두 견적을 갖는다.
     *
     * <h2>판정 순서</h2>
     *
     * <ol>
     *   <li>없는 견적·남의 견적 → 404 (둘을 구분하지 않는다), 설정 없음 → 503</li>
     *   <li>고를 수 없는 부위 → 400. {@code GET /api/part-codes} 가 주는 목록(활성 AI 라벨)과 같다 —
     *       확장 코드는 유사 사례 검색 코퍼스에 없어 골라도 분석이 이어지지 않는다</li>
     *   <li>진행 중인 작업이 있음 → 409</li>
     *   <li>사고의 최신 작업의 견적이 아님 → 409. 옛 견적 id 로 새 결과를 덮지 않는다. 판정 규칙이
     *       작업 단위라 한 작업 안의 견적 버전은 따로 보지 않는다</li>
     *   <li>부위 선택 대상이 아님 → 409, 횟수를 다 씀 → 409. 둘 다 {@link PartSelectionRule} 이
     *       판정한다 — 진행 응답의 {@code partSelectionAvailable} 과 답이 같아야 한다</li>
     *   <li>보낼 사진이 없음 → 400</li>
     * </ol>
     *
     * <p><b>AI 를 다시 부른다.</b> 사례 검색과 금액 계산은 AI 에만 있다. 저장된 검출에 부위만 붙여
     * 여기서 산정하면 산정 규칙이 두 벌이 된다.
     */
    @Transactional
    public AnalysisProgressResponse resolvePart(Long memberId, Long estimateId, String partCode) {
        Long estimatedJobId = estimateQueryRepository.findDetail(estimateId, memberId)
                .map(EstimateQueryRepository.EstimateDetailView::getJobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "견적을 찾을 수 없습니다."));
        requireAvailable();

        String selectedPartCode = selectablePartCode(partCode);

        AnalysisJob estimated = analysisJobRepository.findById(estimatedJobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "견적을 찾을 수 없습니다."));
        Long accidentId = estimated.getAccident().getAccidentId();
        // 견적이 있으면 작업이 적어도 하나 있다. 소유 조건은 견적 조회가 이미 확인했다.
        AnalysisJob latest = analysisJobRepository.findByAccidentIdAndMemberId(accidentId, memberId).getFirst();
        rejectNotResolvable(latest, estimated);

        requireAnalyzableImage(memberId, accidentId);

        AnalysisJob job = enqueue(AnalysisJob.partSelectionOf(latest, selectedPartCode, Instant.now()));
        log.info("부위 확정 재분석 접수: accidentId={}, estimateId={}, previousJobId={}, jobId={}, partCode={}, retryCount={}",
                accidentId, estimateId, latest.getJobId(), job.getJobId(), selectedPartCode, job.getRetryCount());
        return AnalysisProgressResponse.of(job, List.of(), List.of(), false);
    }

    private String selectablePartCode(String partCode) {
        String code = partCode == null ? "" : partCode.strip();
        boolean selectable = !code.isEmpty() && partCodeRepository.findById(code)
                .filter(part -> part.isActive() && part.getCodeScope() == PartCodeScope.AI_LABEL)
                .isPresent();
        if (!selectable) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "고를 수 없는 부위입니다: " + code);
        }
        return code;
    }

    private void rejectNotResolvable(AnalysisJob latest, AnalysisJob estimated) {
        if (!latest.finished()) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 분석이 진행 중입니다.");
        }
        if (!latest.getJobId().equals(estimated.getJobId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 다시 분석한 견적입니다.");
        }
        if (!partSelectionRule.isEligible(latest)) {
            throw new BusinessException(ErrorCode.CONFLICT, "부위를 고를 수 있는 견적이 아닙니다.");
        }
        if (latest.getRetryCount() >= AnalysisJob.MAX_RETRY_COUNT) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "재시도 횟수(" + AnalysisJob.MAX_RETRY_COUNT + "회)를 모두 사용했습니다.");
        }
    }

    private Accident ownedAvailableAccident(Long memberId, Long accidentId) {
        Accident accident = accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));

        requireAvailable();
        return accident;
    }

    private void requireAvailable() {
        if (!available()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "분석 요청을 사용할 수 없습니다.");
        }
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
