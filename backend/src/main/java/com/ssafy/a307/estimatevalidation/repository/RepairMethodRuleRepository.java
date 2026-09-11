package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.RepairMethodRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RepairMethodRuleRepository extends JpaRepository<RepairMethodRule, Long> {

    Page<RepairMethodRule> findByActive(boolean active, Pageable pageable);

    /**
     * 같은 적용 범위의 활성 규칙. 구간 겹침 검사에 쓴다.
     * <p>
     * {@code partCode} 가 {@code null} 인 기본 규칙끼리도 같은 범위로 묶여야 해서
     * {@code =} 가 아니라 null-safe 비교를 쓴다.
     */
    @Query("""
            select r from RepairMethodRule r
            where r.active = true
              and r.damageType = :damageType
              and ((:partCode is null and r.partCode is null) or r.partCode = :partCode)
            """)
    List<RepairMethodRule> findActiveInScope(@Param("damageType") String damageType,
                                             @Param("partCode") String partCode);

    /**
     * 결정기가 쓰는 조회. 우선순위 내림차순 — 부품 예외 규칙이 기본 규칙을 이기게 한다.
     * 같은 우선순위면 부품 지정 규칙이 먼저다.
     */
    @Query("""
            select r from RepairMethodRule r
            where r.active = true
              and r.damageType = :damageType
              and (r.partCode is null or r.partCode = :partCode)
            order by r.priority desc, case when r.partCode is null then 1 else 0 end asc, r.ruleId asc
            """)
    List<RepairMethodRule> findCandidates(@Param("damageType") String damageType,
                                          @Param("partCode") String partCode);

    long countByActiveTrue();

    /** 부품 코드 비활성화를 막을지 판단한다. 활성 규칙이 쓰고 있으면 먼저 규칙을 바꿔야 한다. */
    boolean existsByPartCodeAndActiveTrue(String partCode);

    boolean existsByRepairMethodAndActiveTrue(String repairMethod);

    boolean existsByDamageTypeAndActiveTrue(String damageType);
}
