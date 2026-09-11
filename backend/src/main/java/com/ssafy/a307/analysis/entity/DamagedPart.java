package com.ssafy.a307.analysis.entity;

import com.ssafy.a307.analysis.domain.AnalysisDamageType;
import com.ssafy.a307.analysis.domain.AnalysisRepairMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 분석이 찾아낸 손상 부품 한 건. {@code uk_dp UNIQUE (job_id, part_code)} 라 <b>작업당 부품 1행</b>이다.
 *
 * <h2>이 엔티티가 저장하지 <b>않는</b> 것</h2>
 *
 * <p><b>검출 영역(bbox·폴리곤)을 버린다.</b> 정본 DDL 의 {@code damaged_part} 에 좌표 컬럼이 없고
 * {@code analysis_image_result} 에도 오버레이 이미지 key 만 있다. 계약
 * ({@code geometry.bbox}·{@code geometry.segmentation.polygons})이 주는 좌표는 적재 후 사라지며,
 * 나중에 오버레이를 다시 만들거나 ROI 를 재계산해야 하면 <b>복구할 수 없다.</b>
 * 컬럼을 늘리는 것은 스키마 변경이라 이 작업에서 하지 않고 승인 대상으로 남겼다.
 *
 * <p><b>심각도를 채우지 않는다.</b> {@code severity_score} 는 nullable 이고, 표준화 계약에
 * 심각도 필드가 없다. {@code S15P21A307-197} 이 "확정 전까지 심각도를 검색·집계 축에서 제외" 로
 * 정리했으므로 <b>없는 값을 만들어 넣지 않는다.</b>
 *
 * <h2>{@code confidence} 를 하나로 줄이는 방법</h2>
 *
 * <p>계약은 {@code confidence.part} 와 {@code confidence.damage} 를 따로 주고 둘 다 {@code null}
 * 일 수 있는데, DDL 의 {@code confidence} 는 <b>컬럼 하나이고 NOT NULL</b> 이다.
 * <b>둘 중 작은 값을 쓴다.</b> 이 행이 뜻하는 것은 "이 부품에 이 손상이 있다" 는 한 쌍의 주장이고,
 * 쌍의 신뢰도는 <b>약한 쪽을 넘지 못하기</b> 때문이다. 곱은 두 판단을 독립으로 가정해 값을
 * 실제보다 낮추고, 최댓값은 약한 근거를 가린다. 한쪽만 있으면 있는 쪽을 쓰고,
 * <b>둘 다 없으면 행을 만들지 않는다</b>(적재 쪽에서 보류한다).
 */
@Entity
@Table(name = "damaged_part")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DamagedPart {

    /** {@code confidence NUMERIC(5,4)} — 소수 넷째 자리까지. 더 길면 반올림해서 넣는다. */
    public static final int CONFIDENCE_SCALE = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "damaged_part_id")
    private Long damagedPartId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private AnalysisJob job;

    /** {@code part_code} FK. {@code ON DELETE RESTRICT} 라 마스터에 없는 코드는 INSERT 가 거절된다. */
    @Column(name = "part_code", nullable = false, length = 50)
    private String partCode;

    /** {@code ck_dp_damage} 가 받는 DDL 표기. enum 이름이 아니라 {@code columnValue()} 다. */
    @Column(name = "damage_type", nullable = false, length = 20)
    private String damageType;

    /** {@code ck_dp_method} 가 받는 DDL 표기. */
    @Column(name = "repair_method", nullable = false, length = 20)
    private String repairMethod;

    @Column(name = "severity_score")
    private BigDecimal severityScore;

    @Column(name = "confidence", nullable = false)
    private BigDecimal confidence;

    private DamagedPart(AnalysisJob job, String partCode, AnalysisDamageType damageType,
                        AnalysisRepairMethod repairMethod, BigDecimal confidence) {
        this.job = job;
        this.partCode = partCode;
        this.damageType = damageType.columnValue();
        this.repairMethod = repairMethod.columnValue();
        this.confidence = confidence;
        this.severityScore = null;
    }

    /**
     * 적재한다. {@code severity_score} 는 비운다 — 계약에 심각도가 없다.
     *
     * @param confidence 0~1. {@code NUMERIC(5,4)} 에 맞춰 소수 넷째 자리로 반올림한다
     */
    public static DamagedPart detected(AnalysisJob job, String partCode,
                                       AnalysisDamageType damageType,
                                       AnalysisRepairMethod repairMethod,
                                       BigDecimal confidence) {
        if (job == null) {
            throw new IllegalArgumentException("job 은 필수입니다.");
        }
        if (partCode == null || partCode.isBlank()) {
            throw new IllegalArgumentException("partCode 는 필수입니다.");
        }
        if (damageType == null) {
            throw new IllegalArgumentException("damageType 은 필수입니다.");
        }
        if (repairMethod == null) {
            throw new IllegalArgumentException("repairMethod 는 필수입니다.");
        }
        if (confidence == null) {
            throw new IllegalArgumentException("confidence 는 필수입니다 — NOT NULL 컬럼입니다.");
        }
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence 는 0 이상 1 이하여야 합니다: " + confidence);
        }
        return new DamagedPart(job, partCode.strip(), damageType, repairMethod,
                confidence.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP));
    }
}
