package com.ssafy.a307.review.entity;

import com.ssafy.a307.accident.entity.Accident;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사고 한 건의 검수 상태 (S15P21A307-350 · -352).
 *
 * <h2>이 테이블이 왜 있는가</h2>
 *
 * <p>{@code pipeline/jobs/load_service_accidents.py}(S15P21A307-223)가 실제 수리비가 입력된
 * 사고를 {@code repair_case} 에 {@code source='SERVICE'} 로 이미 적재하고 있다. 그런데 그 행은
 * 조회에서 통째로 빠진다 — {@code rc.source <> 'SERVICE'}. 그 파일 머리말이 이유를 적어 두었다:
 * <b>"공개 정책이 정해지기 전까지 AI-Hub 사례만 연다."</b> 이 엔티티가 그 공개 정책이다
 * (answer71 §2-0).
 *
 * <h2>CHECK 를 지키는 곳은 이 클래스 하나다</h2>
 *
 * <p>정본 DDL 이 네 개의 CHECK 로 규칙을 강제한다. <b>서비스가 열을 따로 만지면 어느 한 경로에서
 * 제약에 걸려 500 이 난다</b> — {@code RepairChecklistItem.check()}·{@code uncheck()} 가 같은
 * 이유로 엔티티에 있다.
 *
 * <ul>
 *   <li>{@code ck_ar_done} : {@code (status = 'PENDING') = (reviewed_at IS NULL)} — <b>양방향</b>.
 *       판정하면 시각을 반드시 쓰고, 대기로 남기면 시각이 없어야 한다</li>
 *   <li>{@code ck_ar_reject} : {@code (status = 'REJECTED') = (reject_reason IS NOT NULL)} —
 *       <b>양방향</b>. 그래서 {@link #approve} 가 사유를 <b>비운다</b>. 반려했다가 승인으로
 *       뒤집을 때 남아 있으면 INSERT 가 아니라 UPDATE 가 거부된다</li>
 *   <li>{@code ck_ar_status} : 세 값뿐</li>
 *   <li>{@code ck_ar_cost} : 금액은 {@code NULL} 이거나 0보다 크다</li>
 * </ul>
 *
 * <p>그래서 전이 메서드를 {@link #approve} · {@link #reject} 둘만 두고, 그 둘이 관련 열을
 * <b>한꺼번에</b> 쓴다. 어긋난 조합을 만드는 호출 자체가 불가능하다.
 */
@Entity
@Table(name = "accident_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccidentReview {

    /** {@code reject_reason VARCHAR(500)}. 넘치면 UPDATE 가 깨져 판정이 저장되지 않는다. */
    public static final int MAX_REJECT_REASON_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long reviewId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accident_id", nullable = false)
    private Accident accident;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccidentReviewStatus status;

    /**
     * 판정할 때 관리자가 본 분석 실행분. <b>큐에 넣을 때가 아니라 판정할 때 채운다</b> —
     * 열의 뜻이 "관리자가 본 분석 결과가 어느 실행분이었나" 이기 때문이다(정본 DDL 주석).
     * 분석을 돌린 적이 없는 사고도 검수 대상이 될 수 있어 {@code NULL} 을 허용한다.
     */
    @Column(name = "reviewed_job_id")
    private Long reviewedJobId;

    /**
     * 승인 시점의 {@code accident.actual_repair_cost} 사본. <b>이 값이 재학습 데이터셋에 실린다</b>
     * (S15P21A307-353). 반려에는 채우지 않는다 — 반려된 건은 데이터셋에 가지 않는다.
     */
    @Column(name = "snapshot_actual_repair_cost")
    private Integer snapshotActualRepairCost;

    /**
     * 누가 판정했나. 탈퇴하면 {@code ON DELETE SET NULL} 로 비워진다 — 그래서 이 열을
     * {@code status} 와 CHECK 로 묶지 않았다(정본 DDL 주석). 회원 삭제가 제약에 걸리면 안 된다.
     */
    @Column(name = "reviewer_member_id")
    private Long reviewerMemberId;

    @Column(name = "reject_reason", length = MAX_REJECT_REASON_LENGTH)
    private String rejectReason;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private Instant queuedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    private AccidentReview(Accident accident, Instant now) {
        this.accident = accident;
        this.status = AccidentReviewStatus.PENDING;
        this.queuedAt = now;
    }

    /**
     * 검수 대기로 큐에 올린다 (S15P21A307-350).
     *
     * <p>판정에 관한 열을 하나도 쓰지 않는다 — {@code ck_ar_done} 이 {@code PENDING} 에
     * {@code reviewed_at} 을 허용하지 않고, {@code ck_ar_reject} 도 사유를 허용하지 않는다.
     */
    public static AccidentReview pending(Accident accident, Instant now) {
        if (accident == null) {
            throw new IllegalArgumentException("accident 는 필수입니다.");
        }
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        return new AccidentReview(accident, now);
    }

    /**
     * 재학습 데이터로 승인한다 (S15P21A307-352).
     *
     * <p><b>{@code rejectReason} 을 비운다.</b> {@code ck_ar_reject} 가 양방향이라 승인 상태에
     * 사유가 남아 있으면 UPDATE 가 거부된다 — 반려했다가 승인으로 뒤집는 경로가 실제로 있다.
     *
     * @param actualRepairCost 그 시점의 {@code accident.actual_repair_cost}. 이 값이 사본이 된다
     */
    public void approve(Long reviewerMemberId, Long reviewedJobId,
                        Integer actualRepairCost, Instant now) {
        requireNow(now);
        this.status = AccidentReviewStatus.APPROVED;
        this.reviewerMemberId = reviewerMemberId;
        this.reviewedJobId = reviewedJobId;
        this.snapshotActualRepairCost = actualRepairCost;
        this.rejectReason = null;
        this.reviewedAt = now;
    }

    /**
     * 반려한다 (S15P21A307-352). <b>사유가 필수다</b> — {@code ck_ar_reject} 가 그렇게 강제한다.
     *
     * <p>{@code snapshotActualRepairCost} 를 비운다. 반려된 건은 데이터셋에 가지 않으므로 승인
     * 금액 사본이 남아 있으면 "무엇이 승인됐나" 를 세는 쿼리가 틀린 답을 낸다.
     */
    public void reject(Long reviewerMemberId, Long reviewedJobId, String reason, Instant now) {
        requireNow(now);
        String stripped = reason == null ? "" : reason.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("반려 사유는 필수입니다.");
        }
        this.status = AccidentReviewStatus.REJECTED;
        this.reviewerMemberId = reviewerMemberId;
        this.reviewedJobId = reviewedJobId;
        this.snapshotActualRepairCost = null;
        this.rejectReason = stripped.length() <= MAX_REJECT_REASON_LENGTH
                ? stripped
                : stripped.substring(0, MAX_REJECT_REASON_LENGTH);
        this.reviewedAt = now;
    }

    private static void requireNow(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
    }
}
