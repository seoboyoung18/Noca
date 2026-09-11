package com.ssafy.a307.repaircase.repository;

import com.ssafy.a307.estimate.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 사례 상세 조회.
 *
 * <p>{@link SimilarCaseRepository} 와 같은 이유로 엔티티 없이 네이티브 쿼리로만 읽는다 —
 * {@code repair_case} 계열 엔티티를 만들면 {@code schema-h2.sql} 검증에서 기존 테스트가
 * 컨텍스트 로딩부터 실패한다. {@code JpaRepository<Estimate, Long>} 도 같은 형식 요건이다.
 */
public interface RepairCaseDetailRepository extends JpaRepository<Estimate, Long> {

    /**
     * 사례 헤더. <b>{@code SERVICE} 사례는 없는 것으로 본다.</b> 실사용자 사고를 사례로 색인하게
     * 되면 그 행은 다른 사람의 데이터다. 공개 정책이 정해지기 전까지 AI-Hub 사례만 연다.
     */
    @Query(value = """
            SELECT rc.case_id AS caseId, rc.source AS source,
                   rc.manufacturer AS manufacturer, rc.model_name AS modelName,
                   rc.car_class AS carClass, rc.repair_year AS repairYear,
                   rc.total_cost AS totalCost, rc.claim_amount AS claimAmount
              FROM repair_case rc
             WHERE rc.case_id = :caseId
               AND rc.source <> 'SERVICE'
            """, nativeQuery = true)
    Optional<CaseView> findPublicCase(@Param("caseId") Long caseId);

    /** 이미지. {@code is_searchable} 이 거짓인 것은 품질 검증에서 걸러진 것이라 내보내지 않는다. */
    @Query(value = """
            SELECT i.storage_key AS storageKey
              FROM repair_case_image i
             WHERE i.case_id = :caseId
               AND i.is_searchable
             ORDER BY i.case_image_id
            """, nativeQuery = true)
    List<ImageView> findSearchableImages(@Param("caseId") Long caseId);

    /**
     * 견적서 행. <b>{@code REFERENCE_PRICE} 는 뺀다</b> — AS 의 신품가 참고 정가라 정산에 들지
     * 않는다. 유사 사례 목록(236)의 부위 금액과 같은 기준이다.
     *
     * <p>부위 표시 순서대로 준다. 부위가 없는 부대 비용({@code ANCILLARY})은 맨 뒤다.
     */
    @Query(value = """
            SELECT rci.part_code AS partCode, pc.name_ko AS partNameKo,
                   rci.line_type AS lineType, rci.work_type AS workType, rci.work_code AS workCode,
                   rci.assessment_status AS assessmentStatus, rci.hq AS hq,
                   rci.part_cost AS partCost, rci.paint_material_cost AS paintMaterialCost,
                   rci.labor_cost AS laborCost, rci.item_total AS itemTotal
              FROM repair_case_item rci
              LEFT JOIN part_code pc ON pc.part_code = rci.part_code
             WHERE rci.case_id = :caseId
               AND rci.line_type <> 'REFERENCE_PRICE'
             ORDER BY pc.display_order NULLS LAST, rci.part_code NULLS LAST, rci.case_item_id
            """, nativeQuery = true)
    List<ItemView> findItems(@Param("caseId") Long caseId);

    interface CaseView {
        Long getCaseId();

        String getSource();

        String getManufacturer();

        String getModelName();

        String getCarClass();

        Short getRepairYear();

        Integer getTotalCost();

        Integer getClaimAmount();
    }

    interface ImageView {
        String getStorageKey();
    }

    interface ItemView {
        String getPartCode();

        String getPartNameKo();

        String getLineType();

        String getWorkType();

        String getWorkCode();

        String getAssessmentStatus();

        BigDecimal getHq();

        Integer getPartCost();

        Integer getPaintMaterialCost();

        Integer getLaborCost();

        Integer getItemTotal();
    }
}
