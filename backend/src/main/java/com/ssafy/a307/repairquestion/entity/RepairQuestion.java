package com.ssafy.a307.repairquestion.entity;

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
 * 사고 한 건의 정비소 확인 질문 목록 머리 (S15P21A307-476 · -477).
 *
 * <p><b>사고당 한 행이다</b> — {@code uk_rq_accident UNIQUE (accident_id)}. 재생성은 머리를 새로
 * 만들지 않고 항목만 갈아 끼우며, 몇 번째 생성분인지는 {@code generation_no} 로만 남는다
 * ({@code S15P21A307-510} 설계 판단). 구조는 {@code RepairChecklist} 와 같고, 상태 어휘와 CHECK
 * 네 개는 정본 DDL 에서 글자까지 같게 맞춰 두었다.
 *
 * <p><b>{@code failure_reason} 에 한글을 넣지 않는다.</b>
 * {@link com.ssafy.a307.repairquestion.domain.RepairQuestionFailure} 의 이름이 그대로 들어간다.
 * 화면 문구는 FE 가 정한다 — 서버가 문장을 박으면 표현을 바꿀 때마다 배포해야 한다.
 */
@Entity
@Table(name = "repair_question")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairQuestion {

    /** {@code failure_reason VARCHAR(200)}. 넘치면 INSERT 가 깨져 실패조차 기록되지 않는다. */
    public static final int MAX_FAILURE_REASON_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long questionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accident_id", nullable = false)
    private Accident accident;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RepairQuestionStatus status;

    /** {@code ck_rq_generation CHECK (generation_no >= 1)}. 재생성이 올린다. */
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

    private RepairQuestion(Accident accident, Instant now) {
        this.accident = accident;
        this.status = RepairQuestionStatus.QUEUED;
        this.generationNo = 1;
        this.createdAt = now;
    }

    /** 생성 요청을 접수한다. 실제 생성은 워커가 한다 — 여기서 LLM 을 부르지 않는다. */
    public static RepairQuestion queued(Accident accident, Instant now) {
        if (accident == null) {
            throw new IllegalArgumentException("accident 는 필수입니다.");
        }
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        return new RepairQuestion(accident, now);
    }

    /**
     * 실패한 생성을 다시 큐에 올린다. {@code FAILED → QUEUED}.
     *
     * <p><b>재생성이 아니다.</b> {@code generation_no} 를 올리지 않고 {@code regenerated_at} 도
     * 건드리지 않는다 — 완성된 목록을 새로 만드는 것과, 애초에 만들어지지 않은 것을 다시 시도하는
     * 것은 다른 일이다. {@code ck_rq_regen} 이 {@code generation_no > 1} 일 때만
     * {@code regenerated_at} 을 허용하므로 그 구분은 스키마에도 이미 들어 있다.
     *
     * <p>{@code completed_at} 을 비우는 것은 {@code ck_rq_done} 때문이다 — 그 열은
     * {@code COMPLETED} · {@code FAILED} 일 때만 값을 가질 수 있다.
     */
    public void requeue() {
        this.status = RepairQuestionStatus.QUEUED;
        this.failureReason = null;
        this.completedAt = null;
    }

    /** 생성을 끝냈다. {@code PROCESSING → COMPLETED}. */
    public void markCompleted(Instant now) {
        this.status = RepairQuestionStatus.COMPLETED;
        this.failureReason = null;
        this.completedAt = now;
    }

    /**
     * 생성에 실패했다. {@code PROCESSING → FAILED}.
     *
     * @param reason 실패 코드. <b>한글 문장이 아니다</b>
     */
    public void markFailed(String reason, Instant now) {
        this.status = RepairQuestionStatus.FAILED;
        this.failureReason = truncate(reason);
        this.completedAt = now;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= MAX_FAILURE_REASON_LENGTH
                ? stripped
                : stripped.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
}
