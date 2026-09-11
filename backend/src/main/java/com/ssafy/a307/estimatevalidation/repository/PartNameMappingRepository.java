package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.estimatevalidation.entity.PartNameMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 한글 원문 ↔ 부품 코드 별칭. 시드 기준 약 1만 5천 건이다.
 *
 * <p><b>{@code findAll()} 은 런타임 사전 적재 전용이다.</b> 관리자 목록은
 * {@link #searchForAdmin} 으로 페이지네이션한다 — 1만 5천 행을 응답에 실으면 안 된다.
 */
public interface PartNameMappingRepository extends JpaRepository<PartNameMapping, String> {

    /**
     * 런타임 사전 적재. <b>{@code join fetch} 로 부품 코드를 함께 읽는다</b> —
     * 지연 로딩이면 매핑마다 부품 코드를 다시 조회한다.
     */
    @Query("select m from PartNameMapping m join fetch m.partCode")
    List<PartNameMapping> findAll();

    /**
     * 관리자 목록. 여기도 {@code join fetch} 라 목록 한 페이지에 쿼리가 늘지 않는다.
     * <p>
     * 비활성 부품을 가리키는 매핑도 <b>숨기지 않는다.</b> 관리자가 "왜 이 항목이 사전에서
     * 빠졌는지" 를 알아야 하고, 그 이유가 부품 비활성화이기 때문이다.
     * <p>
     * {@code scope} 는 대상 부품의 {@code code_scope} 다 — {@code AI_LABEL} 로 거르면 AI 핵심
     * 32종을 가리키는 매핑만 남는다. <b>count 쿼리에도 같은 조건이 있어야</b> 페이지 수가 맞는다.
     */
    @Query(value = """
            select m from PartNameMapping m
            join fetch m.partCode p
            where (:partCode is null or p.partCode = :partCode)
              and (:partActive is null or p.active = :partActive)
              and (:scope is null or p.codeScope = :scope)
              and (:keyword is null
                   or lower(m.rawName)  like lower(concat('%', :keyword, '%'))
                   or lower(p.partCode) like lower(concat('%', :keyword, '%'))
                   or lower(p.nameKo)   like lower(concat('%', :keyword, '%')))
            """,
            countQuery = """
            select count(m) from PartNameMapping m
            join m.partCode p
            where (:partCode is null or p.partCode = :partCode)
              and (:partActive is null or p.active = :partActive)
              and (:scope is null or p.codeScope = :scope)
              and (:keyword is null
                   or lower(m.rawName)  like lower(concat('%', :keyword, '%'))
                   or lower(p.partCode) like lower(concat('%', :keyword, '%'))
                   or lower(p.nameKo)   like lower(concat('%', :keyword, '%')))
            """)
    Page<PartNameMapping> searchForAdmin(@Param("keyword") String keyword,
                                         @Param("partCode") String partCode,
                                         @Param("partActive") Boolean partActive,
                                         @Param("scope") PartCodeScope scope,
                                         Pageable pageable);

    /** 부품 코드 물리 삭제·비활성화 판단용. */
    boolean existsByPartCode_PartCode(String partCode);

    long countByPartCode_PartCode(String partCode);
}
