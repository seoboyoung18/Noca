package com.ssafy.a307.estimate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * AI 분석 결과로 산정한 예상 견적. 사용자가 올린 정비소 견적서를 검증하는
 * {@code estimatevalidation} 과는 다른 도메인이다 — 이쪽은 우리가 만들어 내는 값이다.
 *
 * <p><b>같은 {@code estimate} 테이블에 엔티티가 하나 더 있다.</b>
 * {@code estimatevalidation.EstimateReadModel} 은 검증이 총액만 읽으려고 만든 부분 투영이고
 * 쓰기 경로가 없다. 이 클래스가 쓰기 쪽이며 컬럼을 전부 매핑한다.
 *
 * <p><b>재산정하면 행을 고치지 않고 새 버전을 만든다.</b> 과거 견적을 사용자에게 이미
 * 보여 줬을 수 있어 덮어쓰면 근거가 사라진다. {@code UNIQUE(job_id, version)} 이 그걸 강제한다.
 *
 * <p>{@code job_id} 를 연관 매핑하지 않고 {@code Long} 으로 든다 — 다른 도메인의 FK 를
 * 이렇게 다루는 것이 이 프로젝트의 방식이다({@code Vehicle.memberId},
 * {@code EstimateReadModel.jobId} 와 같다).
 */
@Entity
@Table(name = "estimate")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Estimate {

    /** 첫 산정의 버전. 재산정할 때마다 1씩 올라간다. */
    public static final short FIRST_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "estimate_id")
    private Long estimateId;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "version", nullable = false)
    private short version;

    /**
     * 산정 가능 여부. 참조할 유사 사례가 모자라면 금액을 내지 않고 사유만 남긴다 —
     * 근거 없는 숫자를 보여 주는 것보다 "산정 불가"가 정직하다(S15P21A307-256).
     *
     * <p><b>판단은 AI 가 한다.</b> callback 의 {@code estimable} 을 그대로 옮긴다
     * (S15P21A307-157). 백엔드가 사례 수를 세어 다시 판정하지 않는다 — 두 곳이 판단하면
     * AI 가 "산정했다" 고 보낸 견적을 우리가 "불가" 로 뒤집을 수 있다.
     */
    @Column(name = "is_estimable", nullable = false)
    private boolean estimable;

    /**
     * 산정하지 못한 이유. 계약이 값을 고정했다 —
     * {@code PART_NOT_RESOLVED} · {@code INSUFFICIENT_CASES} · {@code null}
     * (AI 연동 계약 2차 수정본, 2026-09-12).
     *
     * <p>CHECK 제약을 걸지 않았다. 계약이 아직 "협의 후 확정" 상태라 값이 늘 수 있고,
     * 그때마다 마이그레이션을 따라 붙이는 것보다 수신 계층이 검증하는 편이 낫다.
     */
    @Column(name = "non_estimable_reason", length = 100)
    private String nonEstimableReason;

    /** 공임 단가. 공임 = {@code standard_hq × labor_rate} 로 계산한다. */
    @Column(name = "labor_rate")
    private Integer laborRate;

    /** 표준 정비시간 합계. */
    @Column(name = "total_hq", precision = 7, scale = 2)
    private BigDecimal totalHq;

    @Column(name = "total_min")
    private Integer totalMin;

    @Column(name = "total_median")
    private Integer totalMedian;

    @Column(name = "total_max")
    private Integer totalMax;

    /** 산정에 참조한 유사 사례 건수 합계. 근거 표시에 쓴다(S15P21A307-283). */
    @Column(name = "ref_case_total")
    private Integer refCaseTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_grade", length = 10)
    private ConfidenceGrade confidenceGrade;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Estimate(Long jobId, short version) {
        this.jobId = jobId;
        this.version = version;
    }

    /**
     * 금액이 산정된 견적. 총액 세 값은 함께 오거나 함께 비어야 의미가 있다.
     *
     * @param version 채번은 호출부가 한다 — 같은 {@code job_id} 안에서 정해지는 값이라
     *                엔티티 혼자서는 알 수 없다
     */
    public static Estimate estimated(Long jobId, short version, Amounts amounts,
                                     ConfidenceGrade confidenceGrade) {
        Estimate estimate = new Estimate(requireJobId(jobId), requireVersion(version));
        estimate.estimable = true;
        estimate.laborRate = amounts.laborRate();
        estimate.totalHq = amounts.totalHq();
        estimate.totalMin = amounts.totalMin();
        estimate.totalMedian = amounts.totalMedian();
        estimate.totalMax = amounts.totalMax();
        estimate.refCaseTotal = amounts.refCaseTotal();
        estimate.confidenceGrade = confidenceGrade;
        return estimate;
    }

    /**
     * 산정하지 못한 견적. 금액 칸은 비워 두고 사유만 남긴다.
     * <p>
     * 행 자체를 만들지 않으면 "분석은 끝났는데 견적이 없다"는 상태를 화면이 구분하지 못한다.
     */
    public static Estimate nonEstimable(Long jobId, short version, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("산정 불가 사유는 필수입니다.");
        }
        Estimate estimate = new Estimate(requireJobId(jobId), requireVersion(version));
        estimate.estimable = false;
        estimate.nonEstimableReason = reason;
        return estimate;
    }

    public boolean isFirstVersion() {
        return version == FIRST_VERSION;
    }

    private static Long requireJobId(Long jobId) {
        if (jobId == null || jobId <= 0) {
            throw new IllegalArgumentException("jobId 는 양수여야 합니다.");
        }
        return jobId;
    }

    private static short requireVersion(short version) {
        if (version < FIRST_VERSION) {
            throw new IllegalArgumentException("version 은 " + FIRST_VERSION + " 이상이어야 합니다.");
        }
        return version;
    }

    /**
     * 산정된 금액 묶음. 하나씩 세터로 넣지 않고 한 덩어리로 받는 이유는,
     * 총액만 있고 참조 건수가 빠진 것 같은 반쪽짜리 견적을 만들 수 없게 하려는 것이다.
     */
    public record Amounts(Integer laborRate, BigDecimal totalHq,
                          Integer totalMin, Integer totalMedian, Integer totalMax,
                          Integer refCaseTotal) {

        public Amounts {
            if (totalMin != null && totalMedian != null && totalMin > totalMedian) {
                throw new IllegalArgumentException("totalMin 은 totalMedian 보다 클 수 없습니다.");
            }
            if (totalMedian != null && totalMax != null && totalMedian > totalMax) {
                throw new IllegalArgumentException("totalMedian 은 totalMax 보다 클 수 없습니다.");
            }
        }
    }
}
