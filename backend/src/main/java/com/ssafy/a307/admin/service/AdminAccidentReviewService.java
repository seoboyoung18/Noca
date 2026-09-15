package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.dto.AccidentReviewDecisionRequest;
import com.ssafy.a307.admin.dto.AccidentReviewDetailResponse;
import com.ssafy.a307.admin.dto.AccidentReviewResponse;
import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.analysis.repository.AnalysisResultQueryRepository;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.service.AuditLogService;
import com.ssafy.a307.estimate.entity.Estimate;
import com.ssafy.a307.estimate.repository.EstimateRepository;
import com.ssafy.a307.repairchecklist.entity.RepairChecklist;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistItemRepository;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistRepository;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ssafy.a307.review.entity.AccidentReview;
import com.ssafy.a307.review.entity.AccidentReviewStatus;
import com.ssafy.a307.review.repository.AccidentReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 검수 대기 목록 조회와 승인·반려 (S15P21A307-350 목록 · -352 판정).
 *
 * <h2>비교 상세 조회는 여기 없다</h2>
 *
 * <p>AI 결과와 사용자 수정 내역을 나란히 보여 주는 화면은 {@code S15P21A307-349} 본문의 하위
 * Task 2/4 인데 <b>그 티켓이 아직 없다</b>(answer71 §6-1). 목록은 큐를 보는 일이라 적재·판정과
 * 같은 묶음이지만, 비교 상세는 없는 티켓의 몫이라 만들지 않았다.
 *
 * <h2>판정할 때 두 값을 박아 둔다</h2>
 *
 * <p>{@code reviewed_job_id} 와 {@code snapshot_actual_repair_cost} 는 <b>큐에 넣을 때가 아니라
 * 판정할 때</b> 채운다. 두 열의 뜻이 "관리자가 본 분석 실행분" 과 "승인 시점의 금액 사본"
 * 이기 때문이다(정본 DDL 12-3 주석). 그래야 나중에 재분석되거나 금액이 바뀌어도 <b>무엇을
 * 승인했는지</b>가 남는다.
 *
 * <h2>{@code audit_log} 를 병행한다</h2>
 *
 * <p>상태의 정본은 {@code accident_review} 이고, "누가 언제 눌렀나" 는 {@code audit_log} 다
 * (answer71 §2-2). 다른 {@code Admin*Service} 와 같은 방식으로 {@link AuditLogService} 를 부르되,
 * 승인·반려는 {@code UPDATE} 가 아니라 {@code APPROVE}·{@code REJECT} 로 분류한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAccidentReviewService {

    private final AccidentReviewRepository reviewRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisResultQueryRepository analysisResultQueryRepository;
    private final EstimateRepository estimateRepository;
    private final RepairChecklistRepository checklistRepository;
    private final RepairChecklistItemRepository checklistItemRepository;
    private final AuditLogService auditLog;
    private final CurrentMemberProvider currentMemberProvider;
    private final ObjectMapper objectMapper;

    /**
     * 대기 목록. {@code status} 가 {@code null} 이면 전체다.
     *
     * <p>정렬 기본값이 {@code queuedAt} 오름차순이라 {@code ix_ar_queue (status, queued_at)}
     * 를 그대로 탄다 — 오래 기다린 것부터 본다.
     */
    @Transactional(readOnly = true)
    public AdminPageResponse<AccidentReviewResponse> search(AccidentReviewStatus status,
                                                            Pageable pageable) {
        return AdminPageResponse.of(
                reviewRepository.search(status, pageable), AccidentReviewResponse::from);
    }

    /**
     * 검수 대상 한 건의 비교 상세 (S15P21A307-514).
     *
     * <p><b>관리자가 무엇을 승인하는지 보여 주는 자리다.</b> 목록은 금액과 차량만 주므로,
     * 그것만 보고 판정하면 "AI 가 뭘 말했는지" 를 모른 채 학습 데이터를 고르게 된다.
     *
     * <h2>어느 분석 실행분을 보여 주나</h2>
     *
     * <p><b>판정된 건은 기록된 {@code reviewed_job_id} 를 그대로 쓴다.</b> 그 값이 "관리자가 본
     * 분석 결과" 라는 뜻이므로, 나중에 재분석이 돌아도 <b>판정 당시 화면을 재현</b>해야 한다.
     *
     * <p>{@code PENDING} 인 건은 그 열이 비어 있어 <b>가장 최근 {@code COMPLETED}</b> 분을 쓴다 —
     * {@link #decide} 가 판정할 때 고를 값과 같은 규칙이다. 그래야 지금 보는 것과 곧 기록될 것이
     * 같아진다.
     *
     * <p>⚠️ <b>그래도 어긋날 수 있다.</b> 상세를 연 뒤 판정을 누르기 전에 재분석이 완료되면
     * {@code decide()} 가 새 job 을 기록한다. 그 창을 없애려면 판정 요청이 화면이 본 job 을 받아
     * 고정해야 하는데 <b>그것은 판정 쪽 변경이라 이 작업의 범위를 넘는다</b>(prompt73 §4).
     * 대신 응답이 {@code analysisJobId} 를 명시해, 화면이 {@code review.reviewedJobId} 와
     * 비교하면 어긋남을 알 수 있게 했다. answer73 §6 에 적었다.
     *
     * <h2>없는 것은 예외가 아니다</h2>
     *
     * <p>분석이 없는 사고, 견적이 없는 분석, 체크리스트가 없는 사고가 <b>정상적으로 존재한다</b> —
     * 큐 적재 기준이 실제 수리비 입력이라 분석을 한 번도 안 한 사고도 검수 대상이 된다.
     * 전부 {@code null}·빈 목록으로 주고 <b>500 을 내지 않는다.</b>
     *
     * @throws AdminOperationException 없는 {@code reviewId} — {@code ADMIN_TARGET_NOT_FOUND}(404)
     */
    @Transactional(readOnly = true)
    public AccidentReviewDetailResponse findDetail(Long reviewId) {
        AccidentReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> AdminOperationException.notFound("검수 대상"));

        Long accidentId = review.getAccident().getAccidentId();
        Long jobId = review.getReviewedJobId() != null
                ? review.getReviewedJobId()
                : latestCompletedJobId(accidentId);

        List<AccidentReviewDetailResponse.AnalysisImage> images = analysisImagesOf(jobId);

        return new AccidentReviewDetailResponse(
                AccidentReviewResponse.from(review),
                jobId,
                estimateOf(jobId, review.getAccident().getActualRepairCost()),
                damagedPartsOf(jobId),
                summarize(images),
                images,
                checklistStatusOf(accidentId),
                checklistItemsOf(accidentId));
    }

    /** 최신 버전 견적. {@code uk_est (job_id, version)} 이라 버전이 여럿일 수 있다. */
    private AccidentReviewDetailResponse.Estimate estimateOf(Long jobId, Integer actualCost) {
        if (jobId == null) {
            return null;
        }
        return estimateRepository.findFirstByJobIdOrderByVersionDesc(jobId)
                .map(estimate -> toEstimateView(estimate, actualCost))
                .orElse(null);
    }

    private static AccidentReviewDetailResponse.Estimate toEstimateView(Estimate estimate,
                                                                        Integer actualCost) {
        return new AccidentReviewDetailResponse.Estimate(
                estimate.getEstimateId(),
                estimate.getVersion(),
                estimate.isEstimable(),
                estimate.getNonEstimableReason(),
                estimate.getTotalMin(),
                estimate.getTotalMedian(),
                estimate.getTotalMax(),
                estimate.getConfidenceGrade() == null ? null : estimate.getConfidenceGrade().name(),
                withinRange(estimate, actualCost),
                estimate.getCreatedAt());
    }

    /**
     * 실제 수리비가 추정 구간 안에 드는가.
     *
     * <p><b>대푯값 하나를 고르지 않는다.</b> {@code total_min}·{@code total_median}·
     * {@code total_max} 중 무엇을 기준으로 삼을지는 기획이 정하지 않았고, 서버가 고르면 그
     * 선택이 곧 검수 기준이 된다. 구간 포함 여부는 어느 대푯값을 고르든 같은 답이라 계산해 준다.
     *
     * @return 비교할 수 없으면 {@code null} — 실제 금액이 없거나 산정 불가 견적이거나 구간이 없다
     */
    private static Boolean withinRange(Estimate estimate, Integer actualCost) {
        if (actualCost == null || !estimate.isEstimable()) {
            return null;
        }
        Integer min = estimate.getTotalMin();
        Integer max = estimate.getTotalMax();
        if (min == null || max == null) {
            return null;
        }
        return actualCost >= min && actualCost <= max;
    }

    /** 부위 판정. 분석이 없으면 빈 목록이다 — {@code AnalysisResultQueryRepository} 를 그대로 쓴다. */
    private List<AccidentReviewDetailResponse.DamagedPart> damagedPartsOf(Long jobId) {
        if (jobId == null) {
            return List.of();
        }
        return analysisResultQueryRepository.findParts(jobId).stream()
                .map(part -> new AccidentReviewDetailResponse.DamagedPart(
                        part.getPartCode(), part.getPartNameKo(),
                        part.getDamageType(), part.getRepairMethod(), part.getConfidence()))
                .toList();
    }

    /**
     * 분석에 쓰인 사진과 그 검출 (S15P21A307-514 후속 점검).
     *
     * <p><b>{@code damagedParts} 만으로는 AI 가 본 것을 다 보여 주지 못한다.</b> 계약이
     * {@code VECTOR_ONLY} 검출을 견적 {@code items[]} 에서 빼도록 정해 두었고, 부위를 못 찾은
     * 검출은 {@code part_code NOT NULL} 때문에 애초에 {@code damaged_part} 에 들어갈 수 없다
     * ({@code CallbackItem} javadoc). 그래서 원문을 한 번 더 읽는다.
     *
     * <p>{@code AnalysisResultQueryRepository.findImages} 를 그대로 쓴다 — 이미 {@code detections}
     * 를 {@code VARCHAR} 로 꺼내 주고 제외 사진까지 함께 준다. 질의를 새로 만들지 않았다.
     */
    private List<AccidentReviewDetailResponse.AnalysisImage> analysisImagesOf(Long jobId) {
        if (jobId == null) {
            return List.of();
        }
        return analysisResultQueryRepository.findImages(jobId).stream()
                .map(image -> new AccidentReviewDetailResponse.AnalysisImage(
                        image.getImageId(), image.getAngleCode(),
                        image.getExcluded(), image.getExclusionReason(),
                        parseDetections(image.getImageId(), image.getDetections())))
                .toList();
    }

    /**
     * 검출 원문을 판정에 필요한 값만 평탄화한다.
     *
     * <h2>키 이름은 콜백 계약 ⑥ 이다 — {@code common_schema.json} 이 아니다</h2>
     *
     * <p>두 계약이 <b>같은 개념을 다르게 적는다.</b> {@code shared/vision/common_schema.json} 은
     * {@code detection_id}·{@code pair_status} 처럼 snake_case 이고 {@code part} 가 객체지만,
     * 백엔드가 저장하는 것은 <b>AI 서버가 콜백으로 보낸 원문</b>이라 {@code detectionId}·
     * {@code pairStatus}·{@code partCode} 다({@code AnalysisCallbackApiTest} 의 고정 payload 가
     * 그 모양이고 {@code AnalysisResultResponse} javadoc 이 "계약 ⑥ 기준" 이라 적어 두었다).
     * snake_case 로 읽으면 <b>전부 {@code null} 이 되고 아무도 모른다.</b>
     *
     * <h2>깨진 원문 때문에 검수 화면을 죽이지 않는다</h2>
     *
     * <p>이 값은 DDL 이 구조를 강제하지 않는 JSONB 다. 파싱이 실패하면 그 사진의 검출만 비우고
     * 넘어간다 — 고지 문구 하나가 없어 리포트 전체가 500 이 났던 일(2026-09-14)을 되풀이하지
     * 않는다. 대신 로그로 남긴다.
     *
     * @param raw 원문. <b>{@code null} 이거나 빈 배열일 수 있다</b> — 손상이 없는 정상 사진이다
     */
    private List<AccidentReviewDetailResponse.Detection> parseDetections(Long imageId, String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (RuntimeException e) {
            log.warn("검출 원문을 읽지 못했다 — 그 사진의 검출만 비운다. imageId={}, 예외={}",
                    imageId, e.getClass().getSimpleName());
            return List.of();
        }
        if (!root.isArray()) {
            return List.of();
        }

        List<AccidentReviewDetailResponse.Detection> detections = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode confidence = node.path("confidence");
            detections.add(new AccidentReviewDetailResponse.Detection(
                    text(node.path("detectionId")),
                    text(node.path("partCode")),
                    text(node.path("damageType")),
                    text(node.path("pairStatus")),
                    text(node.path("searchability")),
                    decimal(confidence.path("part")),
                    decimal(confidence.path("damage"))));
        }
        return detections;
    }

    /**
     * 검출 분포를 센다. <b>강제가 아니라 판단 재료다</b> — {@code searchability} 는 재학습 적재를
     * 막지 않는다({@code load_service_accidents.py} 가 {@code accident} 만 읽는다).
     */
    private static AccidentReviewDetailResponse.DetectionSummary summarize(
            List<AccidentReviewDetailResponse.AnalysisImage> images) {

        int total = 0;
        int strict = 0;
        int vectorOnly = 0;
        int excluded = 0;
        int withoutPart = 0;
        for (AccidentReviewDetailResponse.AnalysisImage image : images) {
            for (AccidentReviewDetailResponse.Detection detection : image.detections()) {
                total++;
                if (detection.partCode() == null) withoutPart++;
                switch (detection.searchability() == null ? "" : detection.searchability()) {
                    case "STRICT" -> strict++;
                    case "VECTOR_ONLY" -> vectorOnly++;
                    case "EXCLUDED" -> excluded++;
                    default -> { }        // 계약에 없는 값. 세지 않고 total 로만 남는다
                }
            }
        }
        return total == 0
                ? AccidentReviewDetailResponse.DetectionSummary.EMPTY
                : new AccidentReviewDetailResponse.DetectionSummary(
                        total, strict, vectorOnly, excluded, withoutPart);
    }

    private static String text(JsonNode node) {
        if (!node.isString()) return null;
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    private String checklistStatusOf(Long accidentId) {
        return checklist(accidentId)
                .map(found -> found.getStatus().name())
                .orElse(null);
    }

    /**
     * 체크리스트 항목 전부. <b>사용자가 손댄 흔적이 여기에만 있다</b> — {@code USER} 항목과
     * 체크·메모다. 한 사고에 최대 16줄이라 잘라 줄 크기가 아니다.
     *
     * <p>완성되지 않은 체크리스트는 항목이 없다. 그래도 빈 목록이지 오류가 아니다.
     */
    private List<AccidentReviewDetailResponse.ChecklistItem> checklistItemsOf(Long accidentId) {
        return checklist(accidentId)
                .map(found -> checklistItemRepository
                        .findByChecklist_ChecklistIdOrderByDisplayOrderAscItemIdAsc(
                                found.getChecklistId())
                        .stream()
                        .map(item -> new AccidentReviewDetailResponse.ChecklistItem(
                                item.getItemId(), item.getSource().name(), item.getContent(),
                                item.isChecked(), item.getMemo(), item.getDisplayOrder()))
                        .toList())
                .orElseGet(List::of);
    }

    private Optional<RepairChecklist> checklist(Long accidentId) {
        return checklistRepository.findByAccident_AccidentId(accidentId);
    }

    /**
     * 승인하거나 반려한다 (S15P21A307-352).
     *
     * <p><b>이미 판정된 건도 다시 판정할 수 있다.</b> 관리자가 잘못 눌렀을 때 되돌릴 경로가
     * 없으면 행이 영영 잠긴다 — {@code accident_review} 에는 판정 이력을 담을 열이 없고,
     * 그 이력은 {@code audit_log} 가 갖는다. 반려에서 승인으로 뒤집을 때 {@code reject_reason}
     * 이 남아 {@code ck_ar_reject} 를 어기는 문제는 {@link AccidentReview#approve} 가 막는다.
     *
     * <p>없는 {@code reviewId} 는 {@code ADMIN_TARGET_NOT_FOUND}(404)다.
     */
    @Transactional
    public AccidentReviewResponse decide(Long reviewId, AccidentReviewDecisionRequest request) {
        AccidentReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> AdminOperationException.notFound("검수 대상"));

        AccidentReviewResponse before = AccidentReviewResponse.from(review);
        Long reviewerId = currentMemberProvider.currentMemberId();
        Long reviewedJobId = latestCompletedJobId(review.getAccident().getAccidentId());
        Instant now = Instant.now();

        boolean approved = request.decision() == AccidentReviewStatus.APPROVED;
        if (approved) {
            review.approve(reviewerId, reviewedJobId,
                    review.getAccident().getActualRepairCost(), now);
        } else {
            review.reject(reviewerId, reviewedJobId, request.rejectReason(), now);
        }
        reviewRepository.flush();

        AccidentReviewResponse after = AccidentReviewResponse.from(review);
        auditLog.reviewDecided(AuditTargetType.ACCIDENT_REVIEW, String.valueOf(reviewId),
                before, after, approved, review.getRejectReason());

        log.info("사고 데이터 검수 판정: reviewId={}, accidentId={}, 결과={}",
                reviewId, after.accidentId(), review.getStatus());
        return after;
    }

    /**
     * 관리자가 지금 보고 있을 분석 실행분. <b>가장 최근 {@code COMPLETED}</b> 한 건이다.
     *
     * <p>진행 중이거나 실패한 작업의 부분 결과를 가리키면 "무엇을 보고 승인했나" 가 거짓이 된다.
     * 분석을 돌린 적이 없는 사고도 검수 대상이 될 수 있어({@code actual_repair_cost} 만으로
     * 큐에 오른다) {@code null} 이 정상이다 — 열도 nullable 이다.
     *
     * <p>{@code RepairChecklistProcessor.context()} 가 같은 방식으로 최신 완료분을 고른다.
     */
    private Long latestCompletedJobId(Long accidentId) {
        return analysisJobRepository
                .findByAccident_AccidentIdOrderByCreatedAtDescJobIdDesc(accidentId).stream()
                .filter(job -> job.getStatus() == AnalysisJobStatus.COMPLETED)
                .findFirst()
                .map(AnalysisJob::getJobId)
                .orElse(null);
    }
}
