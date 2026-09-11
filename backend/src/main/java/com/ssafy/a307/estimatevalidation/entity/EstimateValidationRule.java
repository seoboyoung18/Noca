package com.ssafy.a307.estimatevalidation.entity;

import com.ssafy.a307.estimatevalidation.domain.GradePolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * 견적서 이상 탐지 임계값 한 벌. <b>행은 만들어진 뒤 바뀌지 않는다.</b>
 *
 * <h2>왜 단일 행 덮어쓰기가 아닌가</h2>
 * 임계값을 덮어쓰면 <b>과거 검증이 어떤 기준으로 "확인 필요" 판정을 받았는지 되짚을 수 없다.</b>
 * 사용자가 "그때는 왜 주의였나" 를 물으면 답할 근거가 사라진다. 그래서 변경은 새 버전을 만들고,
 * {@code estimate_validation.rule_version} 이 어떤 버전으로 판정했는지 가리킨다.
 *
 * <h2>왜 활성 플래그가 없는가</h2>
 * <b>현재 규칙 = {@code ruleVersion} 이 가장 큰 행</b>이다. 플래그를 두면 "활성이 둘" 이나
 * "활성이 없음" 같은 상태가 만들어질 수 있고, 그것을 막으려면 부분 유니크 인덱스가 필요한데
 * H2 가 지원하지 않아 테스트와 운영의 제약이 갈린다. 최대값 규칙은 그런 상태 자체가 없다.
 *
 * <h2>{@code presignedUrlMinutes} 가 여기 없는 이유</h2>
 * 그것은 파일 URL 인프라 설정이지 판정 규칙이 아니다. 관리자가 등급 임계값을 만지다가
 * 파일 다운로드 유효시간을 함께 바꾸는 일이 생기면 안 된다 — {@code application.properties} 에 남는다.
 *
 * <p>{@link GradePolicy} 를 직접 구현한다. {@code GradeDecider} 가 그 인터페이스만 보므로
 * 프로퍼티에서 DB 로 공급원이 바뀌어도 판정 코드는 손대지 않는다.
 */
@Entity
@Table(name = "estimate_validation_rule")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateValidationRule implements GradePolicy {

    public static final int MAX_CHANGE_NOTE_LENGTH = 200;

    /**
     * 비교 기준 분위수. <b>상수다 — 관리자 API 가 받지 않는다.</b>
     *
     * <p>{@code EstimateValidationEngine} 은 {@code repair_cost_stat.cost_p75} 컬럼을 직접
     * 읽는다. 그 표에는 {@code cost_p25}·{@code cost_median}·{@code cost_p75} 세 개의
     * <b>고정 분위수 컬럼</b>만 있어서, 이 값을 60 이나 90 으로 바꿔도 참조할 데이터가 없다.
     * 받아서 저장만 하면 관리자는 "바꿨는데 아무 일도 안 일어나는" 설정을 보게 된다.
     *
     * <p>컬럼과 응답 필드는 남긴다 — 화면이 "무엇과 비교하는지" 는 알아야 하고, 나중에
     * 분위수 데이터가 생기면 그때 수정 가능하게 열면 된다.
     */
    public static final short FIXED_REFERENCE_PERCENTILE = 75;

    /**
     * {@code severe_over_p75_multiplier NUMERIC(5,2)} 가 담을 수 있는 최대값.
     *
     * <p>상한을 엔티티가 보는 이유는 <b>오류 분류</b> 때문이다. 넘겨 보내면 INSERT 에서
     * {@code DataIntegrityViolationException} 이 나고, 저장 경로는 그것을 동시 수정 충돌
     * (409)로 번역한다 — 값이 틀린 관리자에게 "다시 조회하고 재시도" 라고 말하는 셈이다.
     */
    public static final BigDecimal MAX_MULTIPLIER = new BigDecimal("999.99");

    /** {@code caution/needs_review_total_difference_ratio NUMERIC(5,4)} 의 최대값. */
    public static final BigDecimal MAX_DIFFERENCE_RATIO = new BigDecimal("9.9999");

    @Id
    @Column(name = "rule_version", nullable = false, updatable = false)
    private Integer ruleVersion;

    @Column(name = "reference_percentile", nullable = false, updatable = false)
    private short referencePercentile;

    @Column(name = "severe_over_p75_multiplier", nullable = false, precision = 5, scale = 2,
            updatable = false)
    private BigDecimal severeOverP75Multiplier;

    @Column(name = "caution_total_difference_ratio", nullable = false, precision = 5, scale = 4,
            updatable = false)
    private BigDecimal cautionTotalDifferenceRatio;

    @Column(name = "needs_review_total_difference_ratio", nullable = false, precision = 5, scale = 4,
            updatable = false)
    private BigDecimal needsReviewTotalDifferenceRatio;

    @Column(name = "needs_review_item_count", nullable = false, updatable = false)
    private short needsReviewItemCount;

    /** 바꾼 관리자. 탈퇴하면 정본이 {@code SET NULL} 로 끊는다. 시드 행은 처음부터 {@code null} 이다. */
    @Column(name = "changed_by", updatable = false)
    private Long changedBy;

    @Column(name = "change_note", length = MAX_CHANGE_NOTE_LENGTH, updatable = false)
    private String changeNote;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private EstimateValidationRule(int ruleVersion, short referencePercentile,
                                   BigDecimal severeOverP75Multiplier,
                                   BigDecimal cautionTotalDifferenceRatio,
                                   BigDecimal needsReviewTotalDifferenceRatio,
                                   short needsReviewItemCount, Long changedBy, String changeNote) {
        this.ruleVersion = ruleVersion;
        this.referencePercentile = referencePercentile;
        this.severeOverP75Multiplier = severeOverP75Multiplier;
        this.cautionTotalDifferenceRatio = cautionTotalDifferenceRatio;
        this.needsReviewTotalDifferenceRatio = needsReviewTotalDifferenceRatio;
        this.needsReviewItemCount = needsReviewItemCount;
        this.changedBy = changedBy;
        this.changeNote = changeNote;
    }

    /**
     * 다음 버전을 만든다. 값 검증은 요청 DTO 와 서비스가 하고, 여기서는 DB CHECK 와 같은
     * 불변식만 마지막으로 확인한다 — 어느 경로로 들어와도 깨진 규칙이 저장되지 않게.
     */
    public static EstimateValidationRule nextVersion(
            int ruleVersion, short referencePercentile, BigDecimal severeOverP75Multiplier,
            BigDecimal cautionTotalDifferenceRatio, BigDecimal needsReviewTotalDifferenceRatio,
            short needsReviewItemCount, Long changedBy, String changeNote) {

        if (ruleVersion < 1) throw new IllegalArgumentException("ruleVersion must be >= 1");
        if (referencePercentile < 1 || referencePercentile > 100) {
            throw new IllegalArgumentException("referencePercentile must be 1..100");
        }
        if (severeOverP75Multiplier == null || severeOverP75Multiplier.compareTo(BigDecimal.ONE) <= 0) {
            throw new IllegalArgumentException("severeOverP75Multiplier must be > 1.0");
        }
        if (severeOverP75Multiplier.compareTo(MAX_MULTIPLIER) > 0) {
            throw new IllegalArgumentException("severeOverP75Multiplier must be <= " + MAX_MULTIPLIER);
        }
        if (cautionTotalDifferenceRatio == null || cautionTotalDifferenceRatio.signum() < 0) {
            throw new IllegalArgumentException("cautionTotalDifferenceRatio must be >= 0");
        }
        if (cautionTotalDifferenceRatio.compareTo(MAX_DIFFERENCE_RATIO) > 0) {
            throw new IllegalArgumentException(
                    "cautionTotalDifferenceRatio must be <= " + MAX_DIFFERENCE_RATIO);
        }
        if (needsReviewTotalDifferenceRatio == null
                || needsReviewTotalDifferenceRatio.compareTo(cautionTotalDifferenceRatio) < 0) {
            throw new IllegalArgumentException(
                    "needsReviewTotalDifferenceRatio must be >= cautionTotalDifferenceRatio");
        }
        if (needsReviewTotalDifferenceRatio.compareTo(MAX_DIFFERENCE_RATIO) > 0) {
            throw new IllegalArgumentException(
                    "needsReviewTotalDifferenceRatio must be <= " + MAX_DIFFERENCE_RATIO);
        }
        if (needsReviewItemCount < 1) {
            throw new IllegalArgumentException("needsReviewItemCount must be >= 1");
        }
        return new EstimateValidationRule(ruleVersion, referencePercentile,
                severeOverP75Multiplier.setScale(2, java.math.RoundingMode.HALF_UP),
                cautionTotalDifferenceRatio.setScale(4, java.math.RoundingMode.HALF_UP),
                needsReviewTotalDifferenceRatio.setScale(4, java.math.RoundingMode.HALF_UP),
                needsReviewItemCount, changedBy, note(changeNote));
    }

    // ── GradePolicy ────────────────────────────────────────────────────────

    @Override
    public BigDecimal severeOverP75Multiplier() {
        return severeOverP75Multiplier;
    }

    @Override
    public BigDecimal cautionTotalDifferenceRatio() {
        return cautionTotalDifferenceRatio;
    }

    @Override
    public BigDecimal needsReviewTotalDifferenceRatio() {
        return needsReviewTotalDifferenceRatio;
    }

    @Override
    public int needsReviewItemCount() {
        return needsReviewItemCount;
    }

    public int referencePercentile() {
        return referencePercentile;
    }

    private static String note(String changeNote) {
        if (changeNote == null || changeNote.isBlank()) return null;
        String stripped = changeNote.strip();
        return stripped.length() <= MAX_CHANGE_NOTE_LENGTH
                ? stripped
                : stripped.substring(0, MAX_CHANGE_NOTE_LENGTH);
    }
}
