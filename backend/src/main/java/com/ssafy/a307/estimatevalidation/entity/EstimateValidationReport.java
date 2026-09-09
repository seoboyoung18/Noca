package com.ssafy.a307.estimatevalidation.entity;

import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 검증 결과 PDF 한 건의 생성 상태.
 *
 * <p><b>전이 규칙을 이 클래스가 강제한다.</b> 정본 DDL 의 CHECK 세 개가 경계이고, 그것을
 * 서비스·워커가 각자 지키게 두면 어느 한 곳만 어겨도 <b>DB 가 INSERT 를 거절할 뿐 원인을
 * 알려주지 않는다.</b> 그래서 상태를 바꾸는 통로를 아래 네 메서드로만 열어 두었다.
 *
 * <ul>
 *   <li>{@code ck_evr_status} — 상태는 QUEUED·PROCESSING·COMPLETED·FAILED 넷뿐</li>
 *   <li>{@code ck_evr_retry}  — {@code retry_count} 는 0~3. <b>4번째 시도는 CHECK 위반</b>이다</li>
 *   <li>{@code ck_evr_done}   — {@code completed_at} 은 COMPLETED·FAILED 일 때만 채울 수 있다.
 *       {@code PROCESSING} 에서 찍으면 위반이다</li>
 * </ul>
 *
 * <p><b>되돌아가는 전이를 거절한다.</b> 이미 {@code COMPLETED} 인 리포트를 다시
 * {@code PROCESSING} 으로 옮기면, 그 사이에 사용자가 받아 간 PDF 와 저장소의 파일이
 * 어긋난다. 워커 둘이 겹쳐 돌 때 실제로 생길 수 있는 순서라 코드로 막는다.
 */
@Entity
@Table(name = "estimate_validation_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateValidationReport {

    /** {@code ck_evr_retry CHECK (retry_count BETWEEN 0 AND 3)}. 이 값을 넘기면 저장이 깨진다. */
    public static final short MAX_RETRY_COUNT = 3;

    /** {@code failure_reason VARCHAR(200)}. 넘치면 INSERT 가 깨져 실패조차 기록되지 않는다. */
    public static final int MAX_FAILURE_REASON_LENGTH = 200;

    @Id
    @Column(name = "validation_id")
    private Long validationId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "validation_id")
    private EstimateValidation validation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ValidationStatus status;

    @Column(name = "s3_key_pdf", length = 500)
    private String s3KeyPdf;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private short retryCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public static EstimateValidationReport queued(EstimateValidation validation) {
        EstimateValidationReport report = new EstimateValidationReport();
        report.validation = validation;
        report.status = ValidationStatus.QUEUED;
        report.retryCount = 0;
        report.createdAt = Instant.now();
        return report;
    }

    /**
     * {@code QUEUED} → {@code PROCESSING}. 워커가 큐에서 선점할 때 쓴다.
     *
     * <p><b>{@code completedAt} 을 건드리지 않는다.</b> {@code ck_evr_done} 이
     * "completed_at 이 있으면 COMPLETED 나 FAILED" 를 요구하므로, 여기서 시각을 찍으면
     * PROCESSING 인데 completed_at 이 있는 행이 되어 UPDATE 가 깨진다.
     *
     * <p><b>재시도 횟수는 여기서 올린다.</b> 선점이 곧 시도이고, 실패 시점에 올리면
     * 프로세스가 죽었을 때 시도가 세어지지 않아 무한히 재시도된다.
     *
     * @throws IllegalStateException 큐에 있지 않은 리포트를 선점하려 할 때
     */
    public void markProcessing() {
        if (status != ValidationStatus.QUEUED) {
            throw new IllegalStateException(
                    "PROCESSING 으로 옮길 수 있는 것은 QUEUED 뿐이다 (현재 " + status + ")");
        }
        this.status = ValidationStatus.PROCESSING;
        this.retryCount = (short) (this.retryCount + 1);
        this.failureReason = null;
    }

    /**
     * {@code PROCESSING} → {@code COMPLETED}. 저장소 키와 완료 시각을 함께 채운다.
     *
     * <p>키 없이 완료로 두면 {@code EstimateFileValidationService.pdfDownload} 가
     * 그 행을 완료로 보고 내려줄 파일을 찾다가 실패한다 — 그래서 키를 필수로 받는다.
     *
     * @throws IllegalStateException 처리 중이 아닌 리포트를 완료로 옮기려 할 때
     */
    public void markCompleted(String s3KeyPdf, Instant now) {
        if (status != ValidationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "COMPLETED 로 옮길 수 있는 것은 PROCESSING 뿐이다 (현재 " + status + ")");
        }
        if (s3KeyPdf == null || s3KeyPdf.isBlank()) {
            throw new IllegalArgumentException("s3KeyPdf is required");
        }
        this.status = ValidationStatus.COMPLETED;
        this.s3KeyPdf = s3KeyPdf;
        this.failureReason = null;
        this.completedAt = now;
    }

    /**
     * {@code PROCESSING} → {@code FAILED}.
     *
     * <p><b>{@code reason} 은 사용자에게 보일 수 있는 분류 문구여야 한다.</b> 내부 예외
     * 메시지·스택·저장소 키·API 키를 넣지 않는다. 열이 VARCHAR(200) 이라 넘치는 값은
     * 여기서 자른다 — 자르지 않으면 INSERT 가 깨져 실패조차 기록되지 않는다.
     *
     * @throws IllegalStateException 처리 중이 아닌 리포트를 실패로 옮기려 할 때
     */
    public void markFailed(String reason, Instant now) {
        if (status != ValidationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "FAILED 로 옮길 수 있는 것은 PROCESSING 뿐이다 (현재 " + status + ")");
        }
        this.status = ValidationStatus.FAILED;
        this.failureReason = truncate(reason);
        this.completedAt = now;
    }

    /**
     * 다시 시도할 수 있는가.
     *
     * <p>{@code retry_count} 가 상한에 닿으면 <b>큐로 돌려보내지 않고 그대로 끝낸다.</b>
     * 상한이 DB CHECK 로도 걸려 있어 되돌리면 4번째 선점에서 저장이 깨진다.
     */
    public boolean canRetry() {
        return retryCount < MAX_RETRY_COUNT;
    }

    /**
     * {@code PROCESSING} → {@code QUEUED}. 일시적 실패를 다시 큐에 올린다.
     *
     * <p>상한에 닿았으면 되돌리지 않는다 — 부르는 쪽이 {@link #canRetry()} 로 먼저 확인한다.
     */
    public void returnToQueue() {
        if (status != ValidationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "큐로 되돌릴 수 있는 것은 PROCESSING 뿐이다 (현재 " + status + ")");
        }
        if (!canRetry()) {
            throw new IllegalStateException("재시도 상한에 도달해 큐로 되돌릴 수 없다");
        }
        this.status = ValidationStatus.QUEUED;
    }

    /**
     * {@code QUEUED} → {@code FAILED}. <b>재시도 상한에 닿은 건만 이 길로 끝낼 수 있다.</b>
     *
     * <p>왜 필요한가 — 상한에 닿은 리포트는 선점 쿼리가 조건 때문에 영영 집지 못하는데,
     * 상태는 여전히 {@code QUEUED} 라 매 주기 후보로만 조회된다. 큐에서 빼려면 종결해야 하고,
     * {@code PROCESSING} 을 거치려면 {@code retry_count} 를 또 올려야 해서
     * {@code ck_evr_retry}(0~3)를 위반한다.
     *
     * <p><b>상한에 닿지 않았으면 거절한다.</b> 이 메서드가 일반적인 우회로가 되면
     * 전이 규칙을 강제하는 의미가 사라진다.
     *
     * @throws IllegalStateException 큐에 있지 않거나 아직 재시도할 수 있을 때
     */
    public void abandon(String reason, Instant now) {
        if (status != ValidationStatus.QUEUED) {
            throw new IllegalStateException(
                    "큐에 있는 건만 이 길로 종결할 수 있다 (현재 " + status + ")");
        }
        if (canRetry()) {
            throw new IllegalStateException(
                    "아직 재시도할 수 있는 건은 종결하지 않는다 (retryCount=" + retryCount + ")");
        }
        this.status = ValidationStatus.FAILED;
        this.failureReason = truncate(reason);
        this.completedAt = now;
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip();
        return stripped.length() > MAX_FAILURE_REASON_LENGTH
                ? stripped.substring(0, MAX_FAILURE_REASON_LENGTH) : stripped;
    }
}
