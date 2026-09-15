package com.ssafy.a307.admin.dto;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.review.entity.AccidentReview;
import com.ssafy.a307.review.entity.AccidentReviewStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 검수 대기 목록 한 줄 (S15P21A307-350 · -352).
 *
 * <p><b>비교 상세가 아니다.</b> AI 결과와 사용자 수정 내역을 나란히 보여 주는 화면은 별도
 * 작업이고 <b>그 티켓이 아직 없다</b>(answer71 §6-1). 여기는 큐를 훑고 판정 대상을 고르는 데
 * 필요한 값만 담는다 — 차량이 무엇이고, 실제 수리비가 얼마이며, 언제 대기에 올랐는가.
 *
 * <p>차량 값은 {@code accident} 의 <b>스냅샷 열</b>에서 온다. 사고 접수 당시의 차이고, 이후
 * 차량을 고치거나 지워도 바뀌지 않는다.
 *
 * <p><b>한글 라벨을 내려보내지 않는다.</b> {@link #status} 는 DDL 표기 그대로의 코드다.
 *
 * @param actualRepairCost  <b>지금</b> 사고에 적혀 있는 실제 수리비. 승인 대상이 되는 값이다
 * @param snapshotActualRepairCost 승인 시점에 복사해 둔 값. 승인 전에는 {@code null}
 * @param costChangedSinceReview 승인 뒤 사용자가 금액을 고쳤는가. <b>저장된 값이 아니라 두 열을
 *                          비교한 결과</b>다 — 재검수가 필요한 행을 관리자가 목록에서 바로
 *                          알아볼 수 있어야 한다. 자동 재검수 정책은 아직 없다(answer71 §7-3)
 * @param reviewedJobId     관리자가 판정할 때 본 분석 실행분. 판정 전에는 {@code null}
 */
public record AccidentReviewResponse(
        Long reviewId,
        Long accidentId,
        AccidentReviewStatus status,
        Instant queuedAt,
        Instant reviewedAt,
        Long reviewerMemberId,
        Long reviewedJobId,
        String rejectReason,
        Integer actualRepairCost,
        Integer snapshotActualRepairCost,
        boolean costChangedSinceReview,
        LocalDate repairCompletedDate,
        String manufacturer,
        String modelName,
        Short modelYear,
        Instant accidentCreatedAt) {

    public static AccidentReviewResponse from(AccidentReview review) {
        Accident accident = review.getAccident();
        return new AccidentReviewResponse(
                review.getReviewId(),
                accident.getAccidentId(),
                review.getStatus(),
                review.getQueuedAt(),
                review.getReviewedAt(),
                review.getReviewerMemberId(),
                review.getReviewedJobId(),
                review.getRejectReason(),
                accident.getActualRepairCost(),
                review.getSnapshotActualRepairCost(),
                costChanged(review, accident),
                accident.getActualRepairCompletedDate(),
                accident.getSnapshotManufacturer(),
                accident.getSnapshotModelName(),
                accident.getSnapshotModelYear(),
                accident.getCreatedAt());
    }

    /** 승인된 건에만 뜻이 있다 — 사본이 없는 행은 비교할 대상이 없다. */
    private static boolean costChanged(AccidentReview review, Accident accident) {
        return review.getStatus() == AccidentReviewStatus.APPROVED
                && !Objects.equals(review.getSnapshotActualRepairCost(), accident.getActualRepairCost());
    }
}
