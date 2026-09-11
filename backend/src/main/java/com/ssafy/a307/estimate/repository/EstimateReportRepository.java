package com.ssafy.a307.estimate.repository;

import com.ssafy.a307.estimate.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 리포트 조립용 조회 (S15P21A307-337).
 *
 * <p>견적·항목·근거는 {@link EstimateQueryRepository} 를 그대로 쓰고, 여기에는 리포트에만 필요한
 * 것 — 사고·차량 스냅샷, 분석 이미지, 연결된 견적서 검증 — 만 둔다.
 *
 * <p><b>소유자 검사는 {@link #findContext} 의 WHERE 절이 한다.</b> 나머지 두 쿼리는 그 결과로 얻은
 * {@code jobId}·{@code accidentId} 로만 불리므로 따로 검사하지 않는다.
 */
public interface EstimateReportRepository extends JpaRepository<Estimate, Long> {

    /**
     * 사고·차량 정보. 차량은 <b>사고 접수 당시 스냅샷</b>이다 — 그 뒤 차량을 고치거나 지워도
     * 리포트는 사고 당시의 차로 남아야 한다.
     */
    @Query(value = """
            SELECT a.accident_id AS accidentId, e.job_id AS jobId,
                   a.snapshot_manufacturer AS manufacturer, a.snapshot_model_name AS modelName,
                   a.snapshot_vehicle_type AS vehicleType, a.snapshot_car_class AS carClass,
                   a.snapshot_model_year AS modelYear, a.created_at AS accidentCreatedAt
              FROM estimate e
              JOIN analysis_job aj ON aj.job_id = e.job_id
              JOIN accident a ON a.accident_id = aj.accident_id
              JOIN vehicle v ON v.vehicle_id = a.vehicle_id
             WHERE e.estimate_id = :estimateId AND v.member_id = :memberId
            """, nativeQuery = true)
    Optional<ReportContextView> findContext(@Param("estimateId") Long estimateId,
                                            @Param("memberId") Long memberId);

    /**
     * 분석에 쓰인 사진과 그 오버레이. <b>분석에서 제외된 사진은 뺀다</b> — 차량이 아니거나 파손
     * 비율이 기준에 못 미친 사진은 견적 근거가 아니다. 오버레이가 없는 사진은 남는다(key 가 null).
     */
    @Query(value = """
            SELECT ai.image_id AS imageId, ai.angle_code AS angleCode,
                   air.s3_key_overlay AS overlayKey
              FROM analysis_image_result air
              JOIN accident_image ai ON ai.image_id = air.image_id
             WHERE air.job_id = :jobId AND NOT air.is_excluded
             ORDER BY ai.image_id
            """, nativeQuery = true)
    List<ReportImageView> findAnalyzedImages(@Param("jobId") Long jobId);

    /**
     * 이 견적에 연결된 완료 검증 중 가장 최근 것.
     *
     * <p><b>연결 규칙은 {@code EstimateValidationRepository.findModelImprovementRecords} 와 같다.</b>
     * {@code estimate_id} 가 비어 있는 검증은 그 사고의 최신 완료 견적에 붙은 것으로 본다 — 검증을
     * 올릴 때 견적을 고르지 않은 경우다. 두 곳의 규칙이 다르면 같은 검증이 한쪽에서만 보인다.
     */
    @Query(value = """
            SELECT ev.validation_id
              FROM estimate_validation ev
             WHERE ev.accident_id = :accidentId AND ev.status = 'COMPLETED'
               AND :estimateId = COALESCE(
                     ev.estimate_id,
                     (SELECT e2.estimate_id
                        FROM estimate e2
                        JOIN analysis_job aj2 ON aj2.job_id = e2.job_id
                       WHERE aj2.accident_id = ev.accident_id AND aj2.status = 'COMPLETED'
                       ORDER BY e2.created_at DESC, e2.version DESC
                       FETCH FIRST 1 ROW ONLY))
             ORDER BY ev.completed_at DESC NULLS LAST, ev.validation_id DESC
             FETCH FIRST 1 ROW ONLY
            """, nativeQuery = true)
    Optional<Long> findLinkedValidationId(@Param("accidentId") Long accidentId,
                                          @Param("estimateId") Long estimateId);

    interface ReportContextView {
        Long getAccidentId();

        Long getJobId();

        String getManufacturer();

        String getModelName();

        String getVehicleType();

        String getCarClass();

        Short getModelYear();

        /** 드라이버마다 타입이 달라 {@code Object} 로 받는다 — {@link NativeTimestamps} 참고. */
        Object getAccidentCreatedAt();
    }

    interface ReportImageView {
        Long getImageId();

        String getAngleCode();

        /** 오버레이 S3 key. 응답으로 내보내지 않는다 — 조회 URL 로만 바꿔 준다. */
        String getOverlayKey();
    }
}
