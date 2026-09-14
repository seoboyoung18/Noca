package com.ssafy.a307.estimate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * 견적 항목 하나 — 부품 단위 금액과 그 근거 (S15P21A307-157).
 *
 * <p><b>쓰기 전용 엔티티다.</b> 조회는 {@code EstimateQueryRepository} 가 네이티브 쿼리로
 * {@code part_code} 를 조인해 가져간다. 같은 테이블에 조회 경로를 둘로 만들면 어느 쪽이
 * 정본인지 흐려지므로 여기에 조회를 더하지 않는다.
 *
 * <h2>{@code refCondition} 을 {@code String} 으로 든다</h2>
 *
 * <p>{@link com.ssafy.a307.estimate.domain.RefCondition} 이 이 JSON 의 <b>내용</b> 계약이고,
 * 이 필드는 그 직렬화 결과를 담는 자리다. {@link JdbcTypeCode}{@code (SqlTypes.JSON)} 이
 * PostgreSQL 의 {@code jsonb} 와 H2 의 {@code json} 을 방언별로 골라 준다 — 네이티브 쿼리로
 * 쓰면 {@code CAST(? AS JSONB)} 와 {@code CAST(? AS JSON)} 이 달라 한 문장으로 못 쓴다.
 *
 * <p>컬럼이 {@code NOT NULL} 이다. 근거가 없어도 {@code '{}'} 를 넣는다 — 읽는 쪽
 * ({@code RefConditionReader})이 빈 객체를 {@code RefCondition.EMPTY} 로 다루고, 화면은
 * "근거 없음" 을 명시한다.
 *
 * <h2>{@code repairMethod} 는 여기서 필수다</h2>
 *
 * <p>{@code damaged_part.repair_method} 는 미정을 허용하도록 풀었지만(S15P21A307-155) 이쪽은
 * {@code NOT NULL} 로 둔다. <b>금액을 산정했다는 것은 수리 방식을 정했다는 뜻</b>이기 때문이다 —
 * 방식 없이 나온 금액은 근거를 되짚을 수 없다.
 */
@Entity
@Table(name = "estimate_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateItem {

    /** 근거가 없는 항목이 넣는 값. {@code NOT NULL} 이라 빈 문자열을 쓸 수 없다. */
    public static final String EMPTY_REF_CONDITION = "{}";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "estimate_item_id")
    private Long estimateItemId;

    @Column(name = "estimate_id", nullable = false)
    private Long estimateId;

    @Column(name = "damaged_part_id", nullable = false)
    private Long damagedPartId;

    @Column(name = "repair_method", nullable = false, length = 20)
    private String repairMethod;

    @Column(name = "standard_hq")
    private BigDecimal standardHq;

    @Column(name = "part_cost_median")
    private Integer partCostMedian;

    @Column(name = "labor_cost_median")
    private Integer laborCostMedian;

    /** 도장 재료비. 도장이 없는 작업에는 값이 없다 — 0 으로 채우지 않는다. */
    @Column(name = "paint_material_cost")
    private Integer paintMaterialCost;

    @Column(name = "item_min", nullable = false)
    private Integer itemMin;

    @Column(name = "item_median", nullable = false)
    private Integer itemMedian;

    @Column(name = "item_max", nullable = false)
    private Integer itemMax;

    @Column(name = "ref_case_count", nullable = false)
    private Integer refCaseCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ref_condition", nullable = false)
    private String refCondition;

    /**
     * 참조 사례가 적거나 조건을 완화해 산정했다는 표시.
     *
     * <p><b>지금은 항상 false 다.</b> 파생 규칙과 임계값은 S15P21A307-291·-205 의 몫이고,
     * 여기서 임계값을 코드에 박으면 그 티켓의 요구가 그 자리에서 깨진다. AI 는 이 값을
     * 보내지 않는다 — 계약 {@code items[]} 에 해당 필드가 없고, 파생에 필요한 재료
     * ({@code fallbackStage}·{@code refCaseCount})만 온다.
     */
    @Column(name = "is_low_confidence", nullable = false)
    private boolean lowConfidence;

    private EstimateItem(Long estimateId, Long damagedPartId, String repairMethod,
                         BigDecimal standardHq, Integer partCostMedian, Integer laborCostMedian,
                         Integer paintMaterialCost, Integer itemMin, Integer itemMedian,
                         Integer itemMax, Integer refCaseCount, String refCondition) {
        this.estimateId = estimateId;
        this.damagedPartId = damagedPartId;
        this.repairMethod = repairMethod;
        this.standardHq = standardHq;
        this.partCostMedian = partCostMedian;
        this.laborCostMedian = laborCostMedian;
        this.paintMaterialCost = paintMaterialCost;
        this.itemMin = itemMin;
        this.itemMedian = itemMedian;
        this.itemMax = itemMax;
        this.refCaseCount = refCaseCount;
        this.refCondition = refCondition;
        this.lowConfidence = false;
    }

    /**
     * AI 가 보낸 항목 하나를 기록한다.
     *
     * <p><b>금액 범위를 셋으로 벌리지 않는다.</b> 계약의 {@code itemTotal} 은 값 하나이고
     * 항목 단위 min·max 는 오지 않는다. 세 컬럼이 모두 {@code NOT NULL} 이라 같은 값을 넣는다 —
     * 없는 범위를 지어내는 것보다 "범위가 좁혀지지 않았다" 가 사실에 가깝다. 총액 범위는
     * {@code estimate.total_min/max} 가 따로 갖는다.
     */
    public static EstimateItem of(Long estimateId, Long damagedPartId, String repairMethod,
                                  BigDecimal standardHq, Integer partCostMedian,
                                  Integer laborCostMedian, Integer paintMaterialCost,
                                  Integer itemTotal, Integer refCaseCount, String refCondition) {
        if (estimateId == null || damagedPartId == null) {
            throw new IllegalArgumentException("estimateId · damagedPartId 는 필수입니다.");
        }
        if (repairMethod == null || repairMethod.isBlank()) {
            throw new IllegalArgumentException(
                    "repairMethod 는 필수입니다 — 금액을 산정했다면 수리 방식이 정해져 있어야 합니다.");
        }
        if (itemTotal == null) {
            throw new IllegalArgumentException("itemTotal 은 필수입니다 — NOT NULL 컬럼입니다.");
        }
        return new EstimateItem(estimateId, damagedPartId, repairMethod.strip(), standardHq,
                partCostMedian, laborCostMedian, paintMaterialCost,
                itemTotal, itemTotal, itemTotal,
                refCaseCount == null ? 0 : refCaseCount,
                refCondition == null || refCondition.isBlank() ? EMPTY_REF_CONDITION : refCondition);
    }
}
