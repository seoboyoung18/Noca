package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.dto.AccidentReviewDecisionRequest;
import com.ssafy.a307.admin.dto.AccidentReviewResponse;
import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.service.AuditLogService;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.review.entity.AccidentReview;
import com.ssafy.a307.review.entity.AccidentReviewStatus;
import com.ssafy.a307.review.repository.AccidentReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
    private final AuditLogService auditLog;
    private final CurrentMemberProvider currentMemberProvider;

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
