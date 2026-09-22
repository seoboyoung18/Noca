package com.ssafy.a307.estimatevalidation.repository;

import java.util.List;

import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 부품 코드. PK 가 코드 문자열이라 {@code JpaRepository<PartCode, String>} 다.
 *
 * <p>시드 기준 56종(AI 라벨 32 + 확장 24)이지만 확장 코드는 운영 중 늘어난다.
 * 관리자 목록은 그래서 페이지네이션한다 — {@code findAll()} 로 전부 내려보내지 않는다.
 */
public interface PartCodeRepository extends JpaRepository<PartCode, String> {

    /** 관리자 목록. 비활성 코드도 나온다. {@code null} 파라미터는 그 조건을 끄는 뜻이다. */
    @Query("""
            select p from PartCode p
            where (:active is null or p.active = :active)
              and (:layoutZone is null or p.layoutZone = :layoutZone)
              and (:scope is null or p.codeScope = :scope)
              and (:keyword is null
                   or lower(p.partCode) like lower(concat('%', :keyword, '%'))
                   or lower(p.nameKo)   like lower(concat('%', :keyword, '%')))
            """)
    Page<PartCode> searchForAdmin(@Param("keyword") String keyword,
                                  @Param("layoutZone") String layoutZone,
                                  @Param("scope") PartCodeScope scope,
                                  @Param("active") Boolean active,
                                  Pageable pageable);

    long countByCodeScope(PartCodeScope codeScope);

    /**
     * 사용자가 고를 수 있는 부위 (S15P21A307-568). 활성 부위 중 한 범위만, 표시 순서대로.
     * 순서가 같으면 코드 순이다 — 요청마다 목록 순서가 흔들리지 않게.
     */
    List<PartCode> findByActiveTrueAndCodeScopeOrderByDisplayOrderAscPartCodeAsc(PartCodeScope codeScope);
}
