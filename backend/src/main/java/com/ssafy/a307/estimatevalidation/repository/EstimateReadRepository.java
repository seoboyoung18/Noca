package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.EstimateReadModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EstimateReadRepository extends JpaRepository<EstimateReadModel, Long> {

    @Query(value = """
            SELECT e.estimate_id AS estimateId, aj.accident_id AS accidentId,
                   aj.status AS analysisStatus, e.version AS version,
                   e.total_min AS totalMin, e.total_median AS totalMedian, e.total_max AS totalMax
              FROM estimate e
              JOIN analysis_job aj ON aj.job_id = e.job_id
             WHERE e.estimate_id = :estimateId
            """, nativeQuery = true)
    Optional<EstimateContextView> findContext(@Param("estimateId") Long estimateId);

    @Query(value = """
            SELECT dp.part_code AS partCode, dp.damage_type AS damageType,
                   dp.repair_method AS analysisRepairMethod
              FROM estimate e
              JOIN analysis_job aj ON aj.job_id = e.job_id
              JOIN damaged_part dp ON dp.job_id = aj.job_id
             WHERE e.estimate_id = :estimateId
             ORDER BY dp.damaged_part_id
            """, nativeQuery = true)
    List<AnalyzedPartView> findAnalyzedParts(@Param("estimateId") Long estimateId);

    @Query(value = """
            SELECT e.estimate_id AS estimateId, e.version AS version,
                   e.total_min AS totalMin, e.total_median AS totalMedian, e.total_max AS totalMax
              FROM estimate e
              JOIN analysis_job aj ON aj.job_id = e.job_id
             WHERE aj.accident_id = :accidentId AND aj.status = 'COMPLETED'
             ORDER BY e.created_at DESC, e.version DESC
             FETCH FIRST 1 ROW ONLY
            """, nativeQuery = true)
    Optional<LatestEstimateView> findLatestCompletedForAccident(@Param("accidentId") Long accidentId);

    interface EstimateContextView {
        Long getEstimateId();
        Long getAccidentId();
        String getAnalysisStatus();
        Short getVersion();
        Integer getTotalMin();
        Integer getTotalMedian();
        Integer getTotalMax();
    }

    interface AnalyzedPartView {
        String getPartCode();
        String getDamageType();
        String getAnalysisRepairMethod();
    }

    interface LatestEstimateView {
        Long getEstimateId();
        Short getVersion();
        Integer getTotalMin();
        Integer getTotalMedian();
        Integer getTotalMax();
    }
}
