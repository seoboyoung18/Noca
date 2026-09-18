package com.ssafy.a307.repairchecklist.entity;

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
 * 사고 한 건의 정비 체크리스트 머리 (S15P21A307-460 · -461).
 *
 * <p><b>사고당 한 행이다</b> — {@code uk_rcl_accident UNIQUE (accident_id)}. 재생성은 머리를
 * 새로 만들지 않고 항목만 갈아 끼우며, 몇 번째 생성분인지는 {@code generation_no} 로만 남는다
 * ({@code -509} 설계 판단). 그래서 이 엔티티에는 "다시 만들기" 가 곧 상태 되돌리기다.
 *
 * <p><b>진행률 열이 없다.</b> 체크 개수는 {@code repair_checklist_item} 을 {@code COUNT} 두 번
 * 세면 나오고, 비정규화하면 체크·해제·추가·삭제·재생성 다섯 경로가 전부 그 값을 맞춰야 한다.
 * 여기에 만들지 않는 것이 {@code -509} 의 결정이고 이 작업도 따른다.
 *
 * <p><b>{@code failure_reason} 에 한글을 넣지 않는다.</b> {@link com.ssafy.a307.repairchecklist.domain.RepairChecklistFailure}
 * 의 이름이 그대로 들어간다. 화면 문구는 FE 가 정한다 — 서버가 문장을 박으면 표현을 바꿀 때마다
 * 배포해야 하고, 이 저장소는 한글 라벨을 서버가 만들지 않는다({@code AnalysisProgressResponse}).
 */
@Entity
@Table(name = "repair_checklist")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairChecklist {

    /** {@code failure_reason VARCHAR(200)}. 넘치면 INSERT 가 깨져 실패조차 기록되지 않는다. */
    public static final int MAX_FAILURE_REASON_LENGTH = 200;

    /**
     * {@code summary VARCHAR(300)}. 생성은 200자 이내로 지시하고 열은 300 으로 둔다 —
     * 모델이 조금 넘겨도 저장이 깨지지 않게 하고, 넘친 만큼은 여기서 자른다.
     */
    public static final int MAX_SUMMARY_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checklist_id")
    private Long checklistId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accident_id", nullable = false)
    private Accident accident;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RepairChecklistStatus status;

    /** {@code ck_rcl_generation CHECK (generation_no >= 1)}. 재생성({@code -486})이 올린다. */
    @Column(name = "generation_no", nullable = false)
    private short generationNo;

    @Column(name = "failure_reason", length = MAX_FAILURE_REASON_LENGTH)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "regenerated_at")
    private Instant regeneratedAt;

    /**
     * AI 한 줄 요약 (S15P21A307-544). 사고 성격과 중점 확인 권장을 한 문장으로 적는다.
     *
     * <p><b>{@code null} 일 수 있다.</b> 이 변경 이전에 만들어진 체크리스트에는 없고,
     * 모델이 빈 문자열을 내도 {@code null} 로 둔다 — 화면은 없으면 그 영역을 그리지 않는다.
     */
    @Column(name = "summary", length = MAX_SUMMARY_LENGTH)
    private String summary;

    private RepairChecklist(Accident accident, Instant now) {
        this.accident = accident;
        this.status = RepairChecklistStatus.QUEUED;
        this.generationNo = 1;
        this.createdAt = now;
    }

    /** 생성 요청을 접수한다. 실제 생성은 워커가 한다 — 여기서 LLM 을 부르지 않는다. */
    public static RepairChecklist queued(Accident accident, Instant now) {
        if (accident == null) {
            throw new IllegalArgumentException("accident 는 필수입니다.");
        }
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        return new RepairChecklist(accident, now);
    }

    /**
     * 실패한 생성을 다시 큐에 올린다. {@code FAILED → QUEUED}.
     *
     * <p><b>재생성이 아니다.</b> {@code generation_no} 를 올리지 않고 {@code regenerated_at} 도
     * 건드리지 않는다 — 완성된 체크리스트를 새로 만드는 것({@code S15P21A307-486})과, 애초에
     * 만들어지지 않은 것을 다시 시도하는 것은 다른 일이다. {@code ck_rcl_regen} 은
     * {@code generation_no > 1} 일 때만 {@code regenerated_at} 을 허용하므로 그 구분이 스키마에도
     * 이미 들어 있다.
     *
     * <p>{@code completed_at} 을 비우는 것은 {@code ck_rcl_done} 때문이다 — 그 열은
     * {@code COMPLETED}·{@code FAILED} 일 때만 값을 가질 수 있다.
     */
    public void requeue() {
        this.status = RepairChecklistStatus.QUEUED;
        this.failureReason = null;
        this.completedAt = null;
    }

    /**
     * 완성된 체크리스트를 다시 만든다 (S15P21A307-486). {@code COMPLETED → QUEUED}.
     *
     * <p><b>머리를 새로 만들지 않는다.</b> {@code uk_rcl_accident} 가 사고당 한 행을 강제하므로
     * 재생성은 이 행을 고쳐 쓰는 것이다 — {@code S15P21A307-509} 가 그렇게 설계했다.
     *
     * <p><b>{@code generationNo} 와 {@code regeneratedAt} 을 함께 올린다.</b>
     * {@code ck_rcl_regen} 이 {@code regenerated_at IS NULL OR generation_no > 1} 이라, 둘을 따로
     * 두면 "{@code regeneratedAt} 만 채우는" 호출이 가능해지고 그 호출은 DB 가 거부한다.
     * 메서드를 하나로 두는 것이 그 조합을 코드에서 불가능하게 만드는 방법이다.
     *
     * <p>{@link #requeue()} 와 다르다. 저쪽은 <b>실패한 생성의 재시도</b>라 세대를 올리지 않는다 —
     * 만들어진 적이 없는 것을 다시 시도하는 일과, 만들어진 것을 새로 만드는 일은 다르다.
     *
     * <p>{@code completedAt} 을 비우는 것은 {@code ck_rcl_done} 때문이다 — 그 열은
     * {@code COMPLETED}·{@code FAILED} 일 때만 값을 가질 수 있다.
     */
    public void regenerate(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        this.status = RepairChecklistStatus.QUEUED;
        this.generationNo = (short) (this.generationNo + 1);
        this.regeneratedAt = now;
        this.failureReason = null;
        this.completedAt = null;
    }

    /**
     * 생성을 끝냈다. {@code PROCESSING → COMPLETED}.
     *
     * @param summary 한 줄 요약. <b>비어 있으면 {@code null} 로 둔다</b> — 화면이 "없음" 과
     *                "빈 문장" 을 구분할 이유가 없다. 재생성이면 앞 세대의 요약을 덮는다
     */
    public void markCompleted(String summary, Instant now) {
        this.status = RepairChecklistStatus.COMPLETED;
        this.failureReason = null;
        this.completedAt = now;
        this.summary = truncate(summary, MAX_SUMMARY_LENGTH);
    }

    /**
     * 생성에 실패했다. {@code PROCESSING → FAILED}.
     *
     * @param reason 실패 코드. <b>한글 문장이 아니다</b>
     */
    public void markFailed(String reason, Instant now) {
        this.status = RepairChecklistStatus.FAILED;
        this.failureReason = truncate(reason);
        this.completedAt = now;
    }

    private static String truncate(String value) {
        return truncate(value, MAX_FAILURE_REASON_LENGTH);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
