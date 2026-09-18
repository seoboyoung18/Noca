package com.ssafy.a307.estimate.narrative;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 견적 한 건의 LLM 요약 (S15P21A307-537).
 *
 * <p><b>견적과 1:1 이고 PK 가 곧 FK 다.</b> 견적은 재산정할 때마다 새 행이므로({@code uk_est})
 * 요약도 버전마다 따로 생긴다 — 예전 버전의 요약이 새 금액에 붙는 일이 스키마에서 불가능하다.
 *
 * <p><b>접수는 견적 저장과 같은 트랜잭션에서 한다</b>({@code EstimateVersioningService}).
 * 견적이 롤백되면 접수도 함께 사라지고, 견적이 남으면 반드시 큐에 들어가 있다. 실제 생성은
 * 폴링 워커가 하고, 그때 LLM 이 실패해도 견적은 이미 안전하다.
 *
 * <p>{@code content} 는 문자열 그대로 둔다. 읽을 때 {@link EstimateNarrativeReader} 가 해석하며,
 * 깨진 JSON 이 조회를 죽이지 않게 하는 곳도 거기다({@code estimate.unresolved_parts} 와 같은 방식).
 */
@Entity
@Table(name = "estimate_narrative")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateNarrative {

    /** {@code failure_reason VARCHAR(200)}. 넘치면 INSERT 가 깨져 실패조차 기록되지 않는다. */
    public static final int MAX_FAILURE_REASON_LENGTH = 200;

    @Id
    @Column(name = "estimate_id")
    private Long estimateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EstimateNarrativeStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content")
    private String content;

    @Column(name = "failure_reason", length = MAX_FAILURE_REASON_LENGTH)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private EstimateNarrative(Long estimateId, Instant now) {
        this.estimateId = estimateId;
        this.status = EstimateNarrativeStatus.QUEUED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 생성을 접수한다. 실제 생성은 워커가 한다 — 여기서 LLM 을 부르지 않는다. */
    public static EstimateNarrative queued(Long estimateId, Instant now) {
        if (estimateId == null) {
            throw new IllegalArgumentException("estimateId 는 필수입니다.");
        }
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        return new EstimateNarrative(estimateId, now);
    }

    /**
     * 생성을 끝냈다. {@code PROCESSING → COMPLETED}.
     *
     * @param content 문장 묶음 JSON. <b>{@code ck_en_done} 이 완료에 빈 값을 허용하지 않는다</b>
     */
    public void markCompleted(String content, Instant now) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("완료에는 content 가 필요합니다.");
        }
        this.status = EstimateNarrativeStatus.COMPLETED;
        this.content = content;
        this.failureReason = null;
        this.updatedAt = now;
    }

    /**
     * 생성에 실패했다. {@code PROCESSING → FAILED}.
     *
     * <p><b>앞서 만들어 둔 {@code content} 를 지우지 않는다.</b> 재생성이 실패한 경우 예전 문장이
     * 남아 있는 편이 빈 화면보다 낫고, 완료가 아니면 조회가 어차피 읽지 않는다.
     */
    public void markFailed(EstimateNarrativeFailure failure, Instant now) {
        this.status = EstimateNarrativeStatus.FAILED;
        this.failureReason = truncate(failure == null ? null : failure.name());
        this.updatedAt = now;
    }

    public boolean completed() {
        return this.status == EstimateNarrativeStatus.COMPLETED;
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
