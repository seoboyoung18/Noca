package com.ssafy.a307.estimate.narrative;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 요약 큐 (S15P21A307-537). 모양은 {@code RepairChecklistRepository} 와 같다.
 */
public interface EstimateNarrativeRepository extends JpaRepository<EstimateNarrative, Long> {

    /**
     * 큐에서 대기 중인 건을 오래된 순으로. <b>엔티티가 아니라 ID 만 읽는다</b> — 선점에 성공한
     * 건만 뒤에서 통째로 읽으면 되고, 경쟁에서 진 건까지 엔티티로 부풀릴 이유가 없다.
     */
    @Query("""
            select n.estimateId from EstimateNarrative n
             where n.status = com.ssafy.a307.estimate.narrative.EstimateNarrativeStatus.QUEUED
             order by n.createdAt asc, n.estimateId asc
            """)
    List<Long> findQueuedIds(Pageable pageable);

    /**
     * {@code QUEUED} 인 건만 {@code PROCESSING} 으로 옮긴다. <b>조건부 UPDATE 다.</b>
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 를 쓰지 않는 이유는 테스트가 H2 에서 돌기 때문이다.
     * "조회 → 건별 조건부 UPDATE → 영향 행 수 확인" 은 경쟁 상태에서 똑같이 안전하고 두 DB 에서
     * 모두 동작한다({@code RepairChecklistRepository#claimQueued} 와 같은 판단).
     *
     * @return 1 이면 내가 선점했다. 0 이면 다른 워커가 이미 가져갔다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update EstimateNarrative n
               set n.status = com.ssafy.a307.estimate.narrative.EstimateNarrativeStatus.PROCESSING,
                   n.updatedAt = :now
             where n.estimateId = :estimateId
               and n.status = com.ssafy.a307.estimate.narrative.EstimateNarrativeStatus.QUEUED
            """)
    int claimQueued(@Param("estimateId") Long estimateId, @Param("now") Instant now);

    /**
     * 멈춘 채 남은 건. 워커가 처리 도중 죽으면 그 행은 {@code PROCESSING} 으로 남고 아무도
     * 다시 집어 주지 않는다.
     *
     * <p><b>큐로 되돌리지 않고 실패로 종결한다.</b> 시도 횟수를 셀 열이 없어, 되돌리면 영영
     * 실패하는 건이 매 주기 크레딧을 태운다({@code RepairChecklistWorker} 와 같은 판단).
     *
     * @param threshold 이 시각보다 오래 {@code PROCESSING} 인 건
     */
    @Query("""
            select n.estimateId from EstimateNarrative n
             where n.status = com.ssafy.a307.estimate.narrative.EstimateNarrativeStatus.PROCESSING
               and n.updatedAt < :threshold
             order by n.updatedAt asc, n.estimateId asc
            """)
    List<Long> findStaleProcessingIds(@Param("threshold") Instant threshold, Pageable pageable);

    /**
     * 견적 주인. 요약 생성에는 요청자가 없어 소유자를 읽어 <b>사용자와 같은 조립 경로</b>를 탄다
     * ({@code EstimatePdfRepository#findOwnerMemberId} 와 같은 질의다).
     */
    @Query(value = """
            SELECT v.member_id
              FROM estimate e
              JOIN analysis_job aj ON aj.job_id = e.job_id
              JOIN accident a ON a.accident_id = aj.accident_id
              JOIN vehicle v ON v.vehicle_id = a.vehicle_id
             WHERE e.estimate_id = :estimateId
            """, nativeQuery = true)
    Optional<Long> findOwnerMemberId(@Param("estimateId") Long estimateId);
}
