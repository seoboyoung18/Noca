package com.ssafy.a307.repaircase.repository;

import com.ssafy.a307.estimate.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 참조 사례 조회.
 *
 * <p><b>검색하지 않는다.</b> 유사 사례를 찾는 일은 AI 서버가 하고, 그 결과로 받은 사례 ID 를
 * {@code estimate_item.ref_condition} 에 저장해 둔다. 여기서 다시 찾으면 AI 가 실제로 참고한
 * 사례와 달라져, 화면의 "이 사례들 보기" 가 근거가 아닌 다른 목록을 보여 주게 된다.
 *
 * <p><b>{@code repair_case} 계열에 엔티티를 만들지 않았다.</b> 만들면 {@code ddl-auto=validate}
 * 가 테스트 클래스패스의 {@code schema-h2.sql} 을 검사하는데 거기에는 그 테이블들이 없다 —
 * <b>기존 테스트 전부가 컨텍스트 로딩에서 실패한다.</b> 그래서 네이티브 쿼리로만 읽는다.
 * {@code EstimateQueryRepository} 가 {@code damaged_part}·{@code estimate_item} 을 다루는
 * 방식과 같다.
 *
 * <p><b>{@code JpaRepository<Estimate, Long>} 를 상속한 것은 형식 요건이다.</b> 이 인터페이스는
 * {@code Estimate} 를 다루지 않는다. 네이티브 쿼리만 쓰려 해도 Spring Data 는 엔티티 타입을
 * 요구하는데, H2 스키마에 있는 엔티티여야 컨텍스트가 뜬다.
 */
public interface SimilarCaseRepository extends JpaRepository<Estimate, Long> {

    /**
     * 항목 하나와 그 근거. <b>소유자 검사를 WHERE 절에서 한다</b> — 조회한 뒤 비교하면
     * "남의 견적이지만 존재한다" 는 사실이 404 와 403 의 차이로 새어 나간다.
     * {@code EstimateQueryRepository} 와 같은 방식이다.
     *
     * <p>{@code estimateId} 를 조건에 함께 넣는 이유는 <b>다른 견적의 항목 번호를 넣어도
     * 통과하지 않게</b> 하기 위해서다. 경로의 견적과 실제 항목이 어긋나면 결과가 없다.
     */
    @Query(value = """
            SELECT ei.estimate_item_id AS estimateItemId,
                   dp.part_code AS partCode, pc.name_ko AS partNameKo,
                   ei.repair_method AS repairMethod, ei.ref_case_count AS refCaseCount,
                   CAST(ei.ref_condition AS VARCHAR) AS refCondition
              FROM estimate_item ei
              JOIN estimate e ON e.estimate_id = ei.estimate_id
              JOIN damaged_part dp ON dp.damaged_part_id = ei.damaged_part_id
              JOIN part_code pc ON pc.part_code = dp.part_code
              JOIN analysis_job aj ON aj.job_id = e.job_id
              JOIN accident a ON a.accident_id = aj.accident_id
              JOIN vehicle v ON v.vehicle_id = a.vehicle_id
             WHERE ei.estimate_item_id = :estimateItemId
               AND e.estimate_id = :estimateId
               AND v.member_id = :memberId
            """, nativeQuery = true)
    Optional<BasisItemView> findOwnedItem(@Param("estimateId") Long estimateId,
                                          @Param("estimateItemId") Long estimateItemId,
                                          @Param("memberId") Long memberId);

    /**
     * 사례 요약. 그 부위에 실제로 든 비용만 모은다.
     *
     * <p><b>{@code REFERENCE_PRICE} 행은 뺀다.</b> AS 견적서의 신품가 참고 정가라 정산에
     * 포함되지 않고 {@code item_total} 에도 합산되지 않는다 — 넣으면 사례 금액이 부풀려진다
     * ({@code repair_case_item} DDL 주석).
     *
     * <p><b>이미지는 사례당 한 장만.</b> 목록이라 대표 한 장이면 되고, 사례마다 이미지 수가
     * 달라 조인으로 붙이면 행이 곱해진다. {@code is_searchable} 이 거짓인 이미지는 품질 검증에서
     * 걸러진 것이라 화면에 내보내지 않는다.
     *
     * <p>정렬은 하지 않는다. <b>AI 가 준 순서가 유사도 순일 수 있어</b> 호출한 쪽이 그 순서대로
     * 다시 세운다.
     */
    @Query(value = """
            SELECT rc.case_id AS caseId,
                   rc.manufacturer AS manufacturer,
                   rc.model_name AS modelName,
                   rc.car_class AS carClass,
                   rc.repair_year AS repairYear,
                   COALESCE(SUM(rci.item_total), 0) AS partTotal,
                   (SELECT i.storage_key
                      FROM repair_case_image i
                     WHERE i.case_id = rc.case_id AND i.is_searchable
                     ORDER BY i.case_image_id
                     LIMIT 1) AS storageKey
              FROM repair_case rc
              LEFT JOIN repair_case_item rci
                     ON rci.case_id = rc.case_id
                    AND rci.part_code = :partCode
                    AND rci.line_type <> 'REFERENCE_PRICE'
             WHERE rc.case_id IN (:caseIds)
             GROUP BY rc.case_id, rc.manufacturer, rc.model_name, rc.car_class, rc.repair_year
            """, nativeQuery = true)
    List<SimilarCaseView> findCases(@Param("caseIds") Collection<Long> caseIds,
                                    @Param("partCode") String partCode);

    interface BasisItemView {
        Long getEstimateItemId();

        String getPartCode();

        String getPartNameKo();

        String getRepairMethod();

        Integer getRefCaseCount();

        /** {@code ref_condition} 원문. 참조 사례 ID 가 여기 들어 있다(S15P21A307-285). */
        String getRefCondition();
    }

    interface SimilarCaseView {
        Long getCaseId();

        String getManufacturer();

        String getModelName();

        String getCarClass();

        Short getRepairYear();

        /** 그 부위에 든 비용 합계. 해당 부위 항목이 없으면 0 이다. */
        Long getPartTotal();

        /** 대표 이미지의 S3 key. 이미지가 없으면 {@code null} 이다. */
        String getStorageKey();
    }
}
