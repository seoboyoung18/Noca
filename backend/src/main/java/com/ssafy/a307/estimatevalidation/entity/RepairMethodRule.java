package com.ssafy.a307.estimatevalidation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 심각도 구간 하나를 수리 방식 하나에 대응시키는 규칙.
 *
 * <h2>심각도 범위를 강제하지 않는 이유</h2>
 * {@code damaged_part.severity_score} 는 {@code NUMERIC(6,2)} 이고, <b>이 저장소에 그 값을 쓰는
 * 코드가 아직 없다.</b> AI-Hub 원본의 {@code severity_level}(1~4)은
 * {@code pipeline/sql/003_aihub_staging.sql} 의 별개 컬럼이고 여기로 이어지지 않는다.
 * 0~1 이나 0~100 으로 단정할 근거가 없어서, DB 와 이 엔티티는 <b>"하한 &lt; 상한" 과 컬럼 타입만</b>
 * 강제한다. 실제 범위가 확정되면 CHECK 를 좁히면 된다.
 *
 * <h2>경계 규칙</h2>
 * 구간은 기본이 {@code [min, max)} 다. 상한을 열어 두면 이웃 구간과 경계값이 겹치지 않는다.
 * 마지막 구간만 {@code maxInclusive = true} 로 닫아 최대값이 어느 규칙에도 잡히지 않는 구멍을 막는다.
 *
 * <h2>적용 범위</h2>
 * {@code partCode} 가 {@code null} 이면 그 손상 유형 전체에 적용되는 <b>기본 규칙</b>이고,
 * 값이 있으면 그 부품에만 적용되는 <b>예외 규칙</b>이다. 둘이 함께 걸리면 {@code priority} 가
 * 큰 쪽이 이긴다.
 */
@Entity
@Table(name = "repair_method_rule")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairMethodRule {

    /** {@code NUMERIC(6,2)} 이 담을 수 있는 최대값. 이 이상은 INSERT 가 깨진다. */
    public static final BigDecimal MAX_SEVERITY = new BigDecimal("9999.99");
    public static final int SEVERITY_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "damage_type", nullable = false, length = 20)
    private String damageType;

    /** {@code null} 이면 손상 유형 전체에 적용되는 기본 규칙이다. */
    @Column(name = "part_code", length = 50)
    private String partCode;

    @Column(name = "severity_min", nullable = false, precision = 6, scale = SEVERITY_SCALE)
    private BigDecimal severityMin;

    @Column(name = "severity_max", nullable = false, precision = 6, scale = SEVERITY_SCALE)
    private BigDecimal severityMax;

    @Column(name = "max_inclusive", nullable = false)
    private boolean maxInclusive;

    @Column(name = "repair_method", nullable = false, length = 20)
    private String repairMethod;

    @Column(name = "priority", nullable = false)
    private short priority;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private RepairMethodRule(String damageType, String partCode,
                             BigDecimal severityMin, BigDecimal severityMax, boolean maxInclusive,
                             String repairMethod, short priority) {
        this.damageType = damageType;
        this.partCode = partCode;
        this.severityMin = severityMin;
        this.severityMax = severityMax;
        this.maxInclusive = maxInclusive;
        this.repairMethod = repairMethod;
        this.priority = priority;
        this.active = true;
    }

    public static RepairMethodRule register(String damageType, String partCode,
                                            BigDecimal severityMin, BigDecimal severityMax,
                                            boolean maxInclusive, String repairMethod, short priority) {
        return new RepairMethodRule(damageType, partCode,
                scaled(severityMin, "severityMin"), scaled(severityMax, "severityMax"),
                maxInclusive, repairMethod, priority);
    }

    /** 적용 대상(손상 유형·부품)은 바꾸지 않는다 — 바꾸면 다른 규칙이지 같은 규칙의 수정이 아니다. */
    public void modify(BigDecimal severityMin, BigDecimal severityMax, boolean maxInclusive,
                       String repairMethod, short priority) {
        this.severityMin = scaled(severityMin, "severityMin");
        this.severityMax = scaled(severityMax, "severityMax");
        this.maxInclusive = maxInclusive;
        this.repairMethod = repairMethod;
        this.priority = priority;
    }

    public void changeStatus(boolean active) {
        this.active = active;
    }

    /** 이 규칙이 점수를 품는가. 경계 판정을 한 곳에만 두어 조회와 검증이 어긋나지 않게 한다. */
    public boolean covers(BigDecimal severity) {
        if (severity == null) return false;
        if (severity.compareTo(severityMin) < 0) return false;
        int upper = severity.compareTo(severityMax);
        return maxInclusive ? upper <= 0 : upper < 0;
    }

    /** 두 구간이 한 점이라도 겹치는가. 열린 상한을 고려한다. */
    public boolean overlaps(BigDecimal otherMin, BigDecimal otherMax, boolean otherMaxInclusive) {
        boolean thisEndsBefore = maxInclusive
                ? severityMax.compareTo(otherMin) < 0
                : severityMax.compareTo(otherMin) <= 0;
        boolean otherEndsBefore = otherMaxInclusive
                ? otherMax.compareTo(severityMin) < 0
                : otherMax.compareTo(severityMin) <= 0;
        return !(thisEndsBefore || otherEndsBefore);
    }

    /** 같은 적용 범위인가. {@code null} 부품끼리도 같은 범위다. */
    public boolean sameScope(String damageType, String partCode) {
        return this.damageType.equals(damageType)
                && java.util.Objects.equals(this.partCode, partCode);
    }

    /**
     * 컬럼에 담길 수 있는 값인지 확인한다. <b>반올림하지 않는다.</b>
     *
     * <p>{@code RoundingMode.UNNECESSARY} 는 소수 셋째 자리가 오면 {@link ArithmeticException}
     * 을 던진다. 임계값에서 조용한 반올림은 <b>관리자가 입력한 경계와 실제 판정 경계가
     * 달라진다</b>는 뜻이라 반올림보다 거절이 맞다.
     *
     * <p>크기 상한도 여기서 본다. {@link #MAX_SEVERITY} 를 넘겨 보내면 INSERT 단계에서
     * {@code DataIntegrityViolationException} 이 나고, 그것은 "값이 잘못됐다"(400)가 아니라
     * "제약 위반"(409)으로 번역돼 관리자가 무엇을 고쳐야 할지 알 수 없게 된다.
     */
    private static BigDecimal scaled(BigDecimal value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        if (value.compareTo(MAX_SEVERITY) > 0) {
            throw new IllegalArgumentException(field + " must be <= " + MAX_SEVERITY);
        }
        return value.setScale(SEVERITY_SCALE, java.math.RoundingMode.UNNECESSARY);
    }
}
