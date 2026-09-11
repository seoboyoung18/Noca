package com.ssafy.a307.analysis.entity;

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
 * 사고 한 건에 대한 분석 작업. 적재된 {@link DamagedPart} 들이 매달리는 소유자다.
 *
 * <p><b>{@code model_version} 을 이 작업에서 채우지 않는다.</b> 적재 입력인 표준화 계약
 * ({@code pipeline/standardization/common_schema.json} 1.1.0) 에 <b>모델 버전 필드가 아예 없다.</b>
 * 버전은 상위 원본 계약({@code raw_yolo_schema.json} 의 {@code model.name}·{@code model.version})
 * 에만 있고 표준화 과정에서 떨어져 나간다. 그래서 적재 쪽이 채울 값이 없다 —
 * 없는 값을 지어내는 대신 비워 두고 보고서에 적었다.
 *
 * <p>{@code accident} 에 {@code member_id} 가 없으므로 소유자 검사는
 * {@code accident → vehicle → member} 경로다. 이 엔티티는 소유자 판정을 하지 않는다.
 */
@Entity
@Table(name = "analysis_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJob {

    /** {@code ck_aj_retry CHECK (retry_count BETWEEN 0 AND 3)}. */
    public static final short MAX_RETRY_COUNT = 3;

    /** {@code failure_reason VARCHAR(50)}. 넘치면 INSERT 가 깨져 실패조차 기록되지 않는다. */
    public static final int MAX_FAILURE_REASON_LENGTH = 50;

    /** {@code model_version VARCHAR(50)}. */
    public static final int MAX_MODEL_VERSION_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long jobId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accident_id", nullable = false)
    private Accident accident;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisJobStatus status;

    @Column(name = "retry_count", nullable = false)
    private short retryCount;

    @Column(name = "failure_reason", length = MAX_FAILURE_REASON_LENGTH)
    private String failureReason;

    @Column(name = "model_version", length = MAX_MODEL_VERSION_LENGTH)
    private String modelVersion;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AnalysisJob(Accident accident, Instant now) {
        this.accident = accident;
        this.status = AnalysisJobStatus.QUEUED;
        this.retryCount = 0;
        this.createdAt = now;
    }

    /**
     * 큐에 올린다. <b>적재 경로가 부르는 메서드가 아니다</b> — 테스트와, 나중에 붙을
     * 분석 요청 스토리({@code S15P21A307-155})가 쓴다.
     */
    public static AnalysisJob queued(Accident accident, Instant now) {
        if (accident == null) {
            throw new IllegalArgumentException("accident 는 필수입니다.");
        }
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        return new AnalysisJob(accident, now);
    }
}
