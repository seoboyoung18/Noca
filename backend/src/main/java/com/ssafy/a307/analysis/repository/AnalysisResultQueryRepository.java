package com.ssafy.a307.analysis.repository;

import com.ssafy.a307.analysis.entity.AnalysisImageResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 분석 결과 조회 (S15P21A307-203).
 *
 * <p>화면이 사진 위에 손상 부위를 그리는 데 필요한 것만 모은다. 쓰기는
 * {@link AnalysisImageResultRepository} 가 한다 — 같은 테이블이라도 읽는 쪽과 쓰는 쪽의
 * 관심사가 달라, 한 인터페이스에 섞으면 조회용 프로젝션이 적재 경로까지 따라다닌다.
 */
public interface AnalysisResultQueryRepository extends JpaRepository<AnalysisImageResult, Long> {

    /**
     * 검출된 부품 목록. 화면의 콜아웃과 목록이 이 순서로 그려진다.
     *
     * <p><b>정렬이 {@code part_code.display_order} 인 것은 견적 항목과 같은 기준이다</b>
     * ({@code EstimateQueryRepository#findItems}). 두 화면이 같은 차례로 부위를 보여 줘야
     * 사용자가 같은 자리에서 찾는다.
     *
     * <p>한글 표시명을 여기서 붙인다 — {@code part_code} 마스터가 정본이고, FE 가 코드 32종을
     * 하드코딩하지 않게 한다. 수리 방식 표시명은 {@code repair_code} 를 거쳐야 해
     * 서비스가 {@code RepairMethodDisplay} 로 붙인다.
     */
    @Query(value = """
            SELECT dp.part_code AS partCode, pc.name_ko AS partNameKo,
                   pc.layout_zone AS layoutZone,
                   dp.damage_type AS damageType, dp.repair_method AS repairMethod,
                   dp.confidence AS confidence
              FROM damaged_part dp
              JOIN part_code pc ON pc.part_code = dp.part_code
             WHERE dp.job_id = :jobId
             ORDER BY pc.display_order, dp.part_code
            """, nativeQuery = true)
    List<DamagedPartView> findParts(@Param("jobId") Long jobId);

    /**
     * 이미지별 검출 결과.
     *
     * <p><b>{@code detections} 를 문자열로 꺼낸다.</b> JSONB 를 그대로 받으면 드라이버마다 타입이
     * 달라지고(H2 는 JSON, PostgreSQL 은 jsonb), 어차피 우리는 해석하지 않고 그대로 내보낸다 —
     * {@code EstimateQueryRepository#findBasisItems} 가 {@code ref_condition} 을 다루는 방식과 같다.
     *
     * <p><b>원본 크기는 {@code accident_image_asset} 에서 온다.</b> AI 가 콜백에 실어 보낸
     * {@code width}·{@code height} 는 저장하지 않았다(저장할 컬럼이 없다). 대신 업로드 전처리가
     * EXIF 회전까지 반영해 기록한 {@code ORIGINAL} 치수를 쓴다 — AI 가 내려받는 것이 바로 그
     * 원본 파일이므로 같은 값이어야 한다. LEFT JOIN 인 것은 전처리가 치수를 얻지 못하면
     * {@code null} 일 수 있기 때문이다(정본이 nullable 로 둔 이유).
     *
     * <p>제외된 사진도 내보낸다. 화면이 "이 사진은 분석에서 빠졌다" 를 사유와 함께 보여 줘야
     * 사용자가 다시 찍을지 판단할 수 있다.
     */
    @Query(value = """
            SELECT air.image_id AS imageId, ai.angle_code AS angleCode,
                   orig.width AS width, orig.height AS height,
                   air.is_excluded AS excluded, air.exclusion_reason AS exclusionReason,
                   CAST(air.detections AS VARCHAR) AS detections
              FROM analysis_image_result air
              JOIN accident_image ai ON ai.image_id = air.image_id
              LEFT JOIN accident_image_asset orig
                     ON orig.image_id = air.image_id AND orig.variant = 'ORIGINAL'
             WHERE air.job_id = :jobId
             ORDER BY air.image_id
            """, nativeQuery = true)
    List<ImageResultView> findImages(@Param("jobId") Long jobId);

    /** 검출된 부품 한 줄. */
    interface DamagedPartView {
        String getPartCode();
        String getPartNameKo();
        String getLayoutZone();
        String getDamageType();
        /** {@code null} 일 수 있다 — 후보가 둘인 손상은 규칙이 서기 전까지 확정하지 않는다. */
        String getRepairMethod();
        BigDecimal getConfidence();
    }

    /** 사진 한 장의 결과. */
    interface ImageResultView {
        Long getImageId();
        String getAngleCode();
        /** 원본 가로 픽셀. 좌표 환산의 기준이다. 전처리가 얻지 못했으면 {@code null} */
        Integer getWidth();
        Integer getHeight();
        boolean getExcluded();
        String getExclusionReason();
        /** AI 가 보낸 검출 배열 원문. 손상이 없는 사진은 {@code "[]"} 이거나 {@code null} */
        String getDetections();
    }
}
