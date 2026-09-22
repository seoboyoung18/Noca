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
 * ({@code shared/vision/common_schema.json} 1.1.0) 에 <b>모델 버전 필드가 아예 없다.</b>
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

    /** {@code request_id VARCHAR(64)}. 계약 예시는 12자리 hex 다. */
    public static final int MAX_REQUEST_ID_LENGTH = 64;

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

    /**
     * AI callback 멱등 키. 같은 값이 다시 오면 저장을 건너뛴다(S15P21A307-157).
     *
     * <p>작업당 하나다. 재분석으로 새 값이 발급되면 덮어쓰고, 그러면 이전 시도의 늦은 callback 은
     * 값이 달라 거부된다 — 철 지난 결과가 최신 견적을 덮는 것보다 낫다.
     */
    @Column(name = "request_id", length = MAX_REQUEST_ID_LENGTH)
    private String requestId;

    /** AI 가 분석에 쓴 버전 조합 식별자. FK 가 아니라 값 보존용이다. */
    @Column(name = "pipeline_version_id")
    private Long pipelineVersionId;

    /**
     * 사용자가 고른 부위 (S15P21A307-570). 부품을 찾지 못해 산정하지 못한 분석을 부위를 골라
     * 다시 분석할 때 채운다. 워커가 AI 요청에 싣는다 — 접수와 AI 호출 사이에 이 값을 들고 있을
     * 곳이 작업뿐이다. 부위를 고르지 않은 분석(처음 분석과 그 재시도)은 {@code null} 이다.
     */
    @Column(name = "selected_part_code", length = 50)
    private String selectedPartCode;

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

    /**
     * 실패한 작업을 다시 시도할 작업 (S15P21A307-161). 같은 사고에 {@code QUEUED} 로 새로 만들고
     * 재시도 횟수를 이어받아 하나 올린다.
     *
     * <p><b>실패한 작업을 되살리지 않는다.</b> 사진이 전부 제외돼 실패한 작업은 사진별 결과와
     * 산정 불가 견적을 이미 저장했다 — 같은 작업을 다시 돌리면 {@code uk_air(job_id, image_id)} 와
     * 부딪혀 새 결과가 저장되지 않는다. 실패 사유도 덮인다. 새 작업이면 둘 다 생기지 않고,
     * 화면은 이미 사고의 최신 작업을 읽는다.
     *
     * <p><b>고른 부위는 이어받는다</b> (S15P21A307-570). 부위를 골라 다시 분석한 작업이 AI 시간
     * 초과 같은 일로 실패했다면, 다시 시도는 같은 요청을 한 번 더 하는 것이다. 부위를 떨어뜨리면
     * 부품을 못 찾은 처음 결과가 그대로 다시 나오고, 사용자는 부위를 또 고르면서 횟수를 하나 더 쓴다.
     *
     * @throws IllegalStateException 실패한 작업이 아니거나 이미 {@link #MAX_RETRY_COUNT} 번 재시도했다.
     *                               부르는 쪽이 {@link #retriable()} 로 먼저 거른다
     */
    public static AnalysisJob retryOf(AnalysisJob failed, Instant now) {
        if (failed == null || !failed.retriable()) {
            throw new IllegalStateException("재시도할 수 없는 작업입니다.");
        }
        AnalysisJob retry = queued(failed.getAccident(), now);
        retry.retryCount = (short) (failed.retryCount + 1);
        retry.selectedPartCode = failed.selectedPartCode;
        return retry;
    }

    /**
     * 사용자가 고른 부위로 다시 분석할 작업 (S15P21A307-570). {@link #retryOf} 처럼 같은 사고에
     * {@code QUEUED} 로 새로 만들고 횟수를 이어받아 하나 올린다 — 재시도와 횟수를 함께 쓴다.
     *
     * <p>재시도와 달리 <b>완료된 작업에서도</b> 만들 수 있다. 부품을 못 찾아 산정하지 못한 분석은
     * {@code COMPLETED} 로 끝난다. 그 분석이 부위 선택 대상인지는 이 엔티티가 알 수 없어
     * ({@code PartSelectionRule} 이 사진별 결과와 견적을 읽는다) 부르는 쪽이 먼저 거른다.
     *
     * @throws IllegalStateException 아직 끝나지 않았거나 이미 {@link #MAX_RETRY_COUNT} 번 다시 했다
     */
    public static AnalysisJob partSelectionOf(AnalysisJob latest, String partCode, Instant now) {
        if (latest == null || !latest.finished() || latest.retryCount >= MAX_RETRY_COUNT) {
            throw new IllegalStateException("부위를 골라 다시 분석할 수 없는 작업입니다.");
        }
        if (partCode == null || partCode.isBlank()) {
            throw new IllegalArgumentException("partCode 는 필수입니다.");
        }
        AnalysisJob next = queued(latest.getAccident(), now);
        next.retryCount = (short) (latest.retryCount + 1);
        next.selectedPartCode = partCode.strip();
        return next;
    }

    /** 다시 시도할 수 있는가 — 실패했고 재시도 횟수가 남았다. */
    public boolean retriable() {
        return status == AnalysisJobStatus.FAILED && retryCount < MAX_RETRY_COUNT;
    }

    // ── 상태 전이 (S15P21A307-157) ─────────────────────────────────────────
    //
    // 재원님이 적재 계층(S15P21A307-218)에서 이 메서드들을 일부러 두지 않았다 —
    // "두 곳에서 상태를 옮기면 어느 쪽이 옳은지 알 수 없게 된다". 작업의 수명은 이
    // 스토리가 관리하므로 여기에 둔다. 전이는 이 세 메서드로만 일어난다.

    /**
     * AI 에 요청을 보냈다. {@code QUEUED → PROCESSING} 이며 이때 멱등 키가 정해진다.
     *
     * <p>여기서 {@code requestId} 를 심어 두어야 callback 이 "내가 보낸 요청의 답" 인지 대조할 수
     * 있다. 분석 요청 워커(S15P21A307-156)는 같은 전이를 조건부 UPDATE
     * ({@code AnalysisJobRepository#claimQueued})로 한다 — 워커 둘이 같은 건을 옮기지 않게.
     */
    public void markProcessing(String requestId, Instant now) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 는 필수입니다.");
        }
        this.status = AnalysisJobStatus.PROCESSING;
        this.requestId = requestId.strip();
        this.startedAt = now;
    }

    /**
     * 결과를 받아 끝냈다. {@code PROCESSING → COMPLETED}.
     *
     * <p>{@code modelVersion} 은 넘치면 잘라 넣는다 — 버전 문자열이 길다는 이유로 분석 결과
     * 전체를 버리는 것은 균형이 맞지 않는다. {@code failure_reason} 과 달리 이 값은 분류가
     * 아니라 기록이다.
     */
    public void markCompleted(String modelVersion, Long pipelineVersionId, Instant now) {
        this.status = AnalysisJobStatus.COMPLETED;
        this.modelVersion = truncate(modelVersion, MAX_MODEL_VERSION_LENGTH);
        this.pipelineVersionId = pipelineVersionId;
        this.finishedAt = now;
    }

    /**
     * 실패 callback 을 받았다. {@code PROCESSING → FAILED}.
     *
     * <p>{@code retry_count} 를 여기서 올리지 않는다. 재시도는 사용자가 누르는 별도 경로
     * (S15P21A307-161)이고, 실패를 기록하는 것과 다시 시도하는 것은 다른 결정이다.
     */
    public void markFailed(String failureReason, String modelVersion,
                           Long pipelineVersionId, Instant now) {
        this.status = AnalysisJobStatus.FAILED;
        this.failureReason = truncate(failureReason, MAX_FAILURE_REASON_LENGTH);
        this.modelVersion = truncate(modelVersion, MAX_MODEL_VERSION_LENGTH);
        this.pipelineVersionId = pipelineVersionId;
        this.finishedAt = now;
    }

    /** 이미 끝난 작업인가. 멱등 판정에 쓴다 — 끝난 작업에 결과가 또 오면 저장하지 않는다. */
    public boolean finished() {
        return status == AnalysisJobStatus.COMPLETED || status == AnalysisJobStatus.FAILED;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
