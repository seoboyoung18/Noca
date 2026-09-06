package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface EstimateValidationRepository extends JpaRepository<EstimateValidation, Long> {

    Optional<EstimateValidationStateView> findProjectedByValidationIdAndMemberId(Long validationId, Long memberId);

    @EntityGraph(attributePaths = {"accident", "accident.vehicle", "accident.vehicle.model"})
    Optional<EstimateValidation> findByValidationIdAndMemberId(Long validationId, Long memberId);

    @EntityGraph(attributePaths = {"accident", "accident.vehicle", "accident.vehicle.model"})
    Page<EstimateValidation> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);

    java.util.List<EstimateValidation> findByAccident_AccidentIdAndMemberIdAndStatusOrderByCreatedAtDesc(
            Long accidentId, Long memberId, com.ssafy.a307.estimatevalidation.domain.ValidationStatus status);

    @Query(value = """
            SELECT CAST(a.accident_id AS VARCHAR) || ':' ||
                   COALESCE(CAST(ev.validation_id AS VARCHAR), 'none') AS recordKey,
                   a.accident_id AS accidentId, vm.car_class AS carClass,
                   e.estimate_id AS estimateId, e.version AS estimateVersion,
                   e.total_min AS aiTotalMin, e.total_median AS aiTotalMedian, e.total_max AS aiTotalMax,
                   ev.validation_id AS validationId, ev.claimed_total AS shopClaimedTotal,
                   a.actual_repair_cost AS actualRepairCost,
                   a.actual_repair_completed_date AS actualRepairCompletedDate,
                   a.actual_cost_recorded_at AS actualCostRecordedAt
              FROM accident a
              JOIN vehicle v ON v.vehicle_id = a.vehicle_id
              JOIN vehicle_model vm ON vm.model_id = v.model_id
              LEFT JOIN estimate_validation ev
                     ON ev.accident_id = a.accident_id AND ev.status = 'COMPLETED'
              LEFT JOIN estimate e ON e.estimate_id = COALESCE(
                     ev.estimate_id,
                     (SELECT e2.estimate_id
                        FROM estimate e2
                        JOIN analysis_job aj2 ON aj2.job_id = e2.job_id
                       WHERE aj2.accident_id = a.accident_id AND aj2.status = 'COMPLETED'
                       ORDER BY e2.created_at DESC, e2.version DESC
                       FETCH FIRST 1 ROW ONLY))
             WHERE a.actual_repair_cost IS NOT NULL AND a.accident_id > :afterAccidentId
             ORDER BY a.accident_id, ev.validation_id
            """, nativeQuery = true)
    List<ModelImprovementView> findModelImprovementRecords(
            @Param("afterAccidentId") Long afterAccidentId, Pageable pageable);

    interface ModelImprovementView {
        String getRecordKey();
        Long getAccidentId();
        String getCarClass();
        Long getEstimateId();
        Short getEstimateVersion();
        Integer getAiTotalMin();
        Integer getAiTotalMedian();
        Integer getAiTotalMax();
        Long getValidationId();
        Integer getShopClaimedTotal();
        Integer getActualRepairCost();
        LocalDate getActualRepairCompletedDate();
        OffsetDateTime getActualCostRecordedAt();
    }
}
