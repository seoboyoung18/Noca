package com.ssafy.a307.estimate.repository;

import com.ssafy.a307.estimate.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 견적 조회 전용 쿼리.
 *
 * <p><b>소유자 검사를 WHERE 절에 넣는다.</b> 조회한 뒤 회원을 비교하면 "남의 견적이지만
 * 존재한다"는 사실이 404 와 403 의 차이로 새어 나간다. 조건에 넣으면 남의 것은 애초에
 * 결과에 들어오지 않는다 — {@code AccidentRepository}·{@code VehicleRepository} 와 같은 방식이다.
 *
 * <p>소유자까지 가는 길이 길다: {@code estimate → analysis_job → accident → vehicle → member}.
 * {@code accident} 에 {@code member_id} 를 두지 않은 것은 차량 주인과 사고 주인이 어긋나는
 * 상태를 DB 가 막지 못하기 때문이고, 그 대가로 조인이 하나 늘었다.
 *
 * <p><b>네이티브 쿼리를 쓴다.</b> {@code damaged_part}·{@code estimate_item} 은 아직 엔티티가
 * 없는데, 조회만 하는 이 경로 때문에 엔티티를 만들 이유는 없다.
 * {@code EstimateReadRepository} 가 같은 방식으로 {@code damaged_part} 를 읽고 있다.
 */
public interface EstimateQueryRepository extends JpaRepository<Estimate, Long> {

    @Query(value = """
            SELECT e.estimate_id AS estimateId, e.job_id AS jobId, e.version AS version,
                   e.is_estimable AS estimable, e.non_estimable_reason AS nonEstimableReason,
                   e.labor_rate AS laborRate, e.total_hq AS totalHq,
                   e.total_min AS totalMin, e.total_median AS totalMedian, e.total_max AS totalMax,
                   e.ref_case_total AS refCaseTotal, e.confidence_grade AS confidenceGrade,
                   e.created_at AS createdAt
              FROM estimate e
              JOIN analysis_job aj ON aj.job_id = e.job_id
              JOIN accident a ON a.accident_id = aj.accident_id
              JOIN vehicle v ON v.vehicle_id = a.vehicle_id
             WHERE e.estimate_id = :estimateId AND v.member_id = :memberId
            """, nativeQuery = true)
    Optional<EstimateDetailView> findDetail(@Param("estimateId") Long estimateId,
                                            @Param("memberId") Long memberId);

    /**
     * 항목 목록. 부위 표시 순서({@code part_code.display_order})대로 정렬한다 —
     * 화면이 앞·뒤·좌·우를 일정한 차례로 그려야 사용자가 매번 같은 자리에서 찾는다.
     */
    @Query(value = """
            SELECT ei.estimate_item_id AS estimateItemId,
                   dp.part_code AS partCode, pc.name_ko AS partNameKo,
                   pc.layout_zone AS layoutZone, dp.damage_type AS damageType,
                   ei.repair_method AS repairMethod, ei.standard_hq AS standardHq,
                   ei.part_cost_median AS partCostMedian, ei.labor_cost_median AS laborCostMedian,
                   ei.item_min AS itemMin, ei.item_median AS itemMedian, ei.item_max AS itemMax,
                   ei.ref_case_count AS refCaseCount, ei.is_low_confidence AS lowConfidence
              FROM estimate_item ei
              JOIN damaged_part dp ON dp.damaged_part_id = ei.damaged_part_id
              JOIN part_code pc ON pc.part_code = dp.part_code
             WHERE ei.estimate_id = :estimateId
             ORDER BY pc.display_order, ei.estimate_item_id
            """, nativeQuery = true)
    List<EstimateItemView> findItems(@Param("estimateId") Long estimateId);

    /**
     * 항목별 산정 근거. 정렬은 {@link #findItems} 와 같아야 한다 — 화면이 두 응답을 나란히
     * 놓고 항목과 근거를 짝지으므로, 순서가 어긋나면 다른 부위의 근거가 붙어 보인다.
     *
     * <p><b>{@code ref_condition} 을 문자열로 꺼낸다.</b> JSONB 를 그대로 받으면 드라이버마다
     * 타입이 달라지고(H2 는 JSON, PostgreSQL 은 jsonb) 엔티티 매핑도 없다. 읽는 쪽이
     * {@link com.ssafy.a307.estimate.domain.RefConditionReader} 하나로 모이도록 원문을 넘긴다.
     */
    @Query(value = """
            SELECT ei.estimate_item_id AS estimateItemId,
                   dp.part_code AS partCode, pc.name_ko AS partNameKo,
                   ei.repair_method AS repairMethod, ei.ref_case_count AS refCaseCount,
                   CAST(ei.ref_condition AS VARCHAR) AS refCondition
              FROM estimate_item ei
              JOIN damaged_part dp ON dp.damaged_part_id = ei.damaged_part_id
              JOIN part_code pc ON pc.part_code = dp.part_code
             WHERE ei.estimate_id = :estimateId
             ORDER BY pc.display_order, ei.estimate_item_id
            """, nativeQuery = true)
    List<EstimateBasisItemView> findBasisItems(@Param("estimateId") Long estimateId);

    /**
     * 사고 하나에 딸린 견적 이력. 최신이 앞이다.
     * <p>
     * 한 사고에 분석 작업이 여러 번 있을 수 있어({@code analysis_job} 재시도·재분석)
     * {@code version} 만으로는 순서가 정해지지 않는다. {@code created_at} 을 먼저 본다.
     */
    @Query(value = """
            SELECT e.estimate_id AS estimateId, e.version AS version,
                   e.is_estimable AS estimable,
                   e.total_min AS totalMin, e.total_median AS totalMedian, e.total_max AS totalMax,
                   e.confidence_grade AS confidenceGrade, e.created_at AS createdAt
              FROM estimate e
              JOIN analysis_job aj ON aj.job_id = e.job_id
              JOIN accident a ON a.accident_id = aj.accident_id
              JOIN vehicle v ON v.vehicle_id = a.vehicle_id
             WHERE a.accident_id = :accidentId AND v.member_id = :memberId
             ORDER BY e.created_at DESC, e.version DESC
            """, nativeQuery = true)
    List<EstimateSummaryView> findHistoryByAccident(@Param("accidentId") Long accidentId,
                                                    @Param("memberId") Long memberId);

    /** 사고가 이 회원 것인지. 견적이 하나도 없을 때 404 와 빈 목록을 가르는 데 쓴다. */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM accident a
                  JOIN vehicle v ON v.vehicle_id = a.vehicle_id
                 WHERE a.accident_id = :accidentId AND v.member_id = :memberId)
            """, nativeQuery = true)
    boolean existsOwnedAccident(@Param("accidentId") Long accidentId,
                                @Param("memberId") Long memberId);

    interface EstimateDetailView {
        Long getEstimateId();

        Long getJobId();

        short getVersion();

        Boolean getEstimable();

        String getNonEstimableReason();

        Integer getLaborRate();

        BigDecimal getTotalHq();

        Integer getTotalMin();

        Integer getTotalMedian();

        Integer getTotalMax();

        Integer getRefCaseTotal();

        String getConfidenceGrade();

        /**
         * {@code Object} 로 받아 {@link NativeTimestamps#toInstant} 로 바꾼다. 드라이버마다 타입이 달라
         * (PostgreSQL {@code Instant}, H2 {@code OffsetDateTime}) 한쪽으로 고정하면 다른 쪽에서 500 이 난다.
         */
        Object getCreatedAt();
    }

    interface EstimateItemView {
        Long getEstimateItemId();

        String getPartCode();

        String getPartNameKo();

        String getLayoutZone();

        String getDamageType();

        String getRepairMethod();

        BigDecimal getStandardHq();

        Integer getPartCostMedian();

        Integer getLaborCostMedian();

        Integer getItemMin();

        Integer getItemMedian();

        Integer getItemMax();

        Integer getRefCaseCount();

        Boolean getLowConfidence();
    }

    interface EstimateSummaryView {
        Long getEstimateId();

        short getVersion();

        Boolean getEstimable();

        Integer getTotalMin();

        Integer getTotalMedian();

        Integer getTotalMax();

        String getConfidenceGrade();

        /** {@link EstimateDetailView#getCreatedAt} 와 같은 이유로 {@code Object} 다. */
        Object getCreatedAt();
    }

    interface EstimateBasisItemView {
        Long getEstimateItemId();

        String getPartCode();

        String getPartNameKo();

        String getRepairMethod();

        Integer getRefCaseCount();

        /** {@code estimate_item.ref_condition} 원문. 빈 근거는 {@code '{}'} 로 저장돼 있다. */
        String getRefCondition();
    }
}
