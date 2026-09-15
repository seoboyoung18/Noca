package com.ssafy.a307.repairchecklist.repository;

import com.ssafy.a307.repairchecklist.entity.RepairChecklist;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RepairChecklistRepository extends JpaRepository<RepairChecklist, Long> {

    /**
     * 사고의 체크리스트. <b>{@code uk_rcl_accident} 라 있어도 한 행이다.</b>
     *
     * <p><b>소유자 조건이 쿼리 안에 있다.</b> 조회한 뒤 자바에서 {@code memberId} 를 비교하면
     * "행은 있었다" 는 사실이 응답 시간·분기로 새어 나간다. 없는 사고와 남의 사고가 <b>모두
     * 빈 값</b>으로 돌아오고, 호출자는 둘을 구분하지 말고 404 로 답해야 한다 — 403 은 그 사고가
     * 존재한다는 것을 알려 준다({@code AnalysisProgressService} · {@code EstimateQueryService}).
     *
     * <p>{@code accident} 에 {@code member_id} 가 없으므로 경로는
     * {@code accident → vehicle → member} 다.
     */
    @Query("""
            select c from RepairChecklist c
            join c.accident a
            join a.vehicle v
            where a.accidentId = :accidentId and v.memberId = :memberId
            """)
    Optional<RepairChecklist> findByAccidentIdAndMemberId(@Param("accidentId") Long accidentId,
                                                          @Param("memberId") Long memberId);

    /**
     * 사고의 체크리스트. <b>소유자 조건이 없다</b> — 관리자 검수 조회 전용이다
     * ({@code S15P21A307-514}).
     *
     * <p>{@link #findByAccidentIdAndMemberId} 를 쓸 수 없다. 관리자는 그 사고의 주인이 아니라
     * 소유자 조건이 언제나 빈 값을 낸다. 그렇다고 그 메서드에서 조건을 빼면 <b>사용자 경로가
     * 남의 체크리스트를 보게 된다</b> — 그래서 메서드를 따로 둔다.
     *
     * <p>이 메서드를 부르는 곳은 {@code /api/admin/**} 뿐이어야 한다. 그 경로는
     * {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.
     */
    Optional<RepairChecklist> findByAccident_AccidentId(Long accidentId);

    /**
     * 큐에서 처리 대기 중인 건을 오래된 순으로 가져온다.
     *
     * <p>엔티티가 아니라 ID 만 읽는다 — 선점에 성공한 건만 뒤에서 통째로 읽으면 되고, 경쟁에서
     * 진 건까지 엔티티로 부풀릴 이유가 없다({@code EstimateValidationRepository#findQueuedIds}).
     *
     * <p>{@code checklistId} 2차 정렬 키를 지우지 말 것. {@code created_at} 은
     * {@code TIMESTAMPTZ DEFAULT now()} 이고 PostgreSQL 의 {@code now()} 는 <b>트랜잭션 시작
     * 시각</b>이라 한 트랜잭션에서 만든 여러 건은 값이 같아진다.
     */
    @Query("""
            select c.checklistId from RepairChecklist c
             where c.status = com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus.QUEUED
             order by c.createdAt asc, c.checklistId asc
            """)
    List<Long> findQueuedIds(Pageable pageable);

    /**
     * {@code QUEUED} 인 건만 {@code PROCESSING} 으로 옮긴다. <b>조건부 UPDATE 다.</b>
     *
     * <p>팀 핵심 쿼리는 {@code FOR UPDATE SKIP LOCKED} 를 쓰지만 테스트가 H2 에서 돌아 그 문법에
     * 기대면 워커를 검증할 수 없다. "조회 → 건별 조건부 UPDATE → 영향 행 수 확인" 은 경쟁
     * 상태에서 똑같이 안전하고 두 DB 에서 모두 동작한다({@code EstimateValidationRepository#claimQueued}).
     *
     * <p>{@code completed_at} 을 건드리지 않는다 — {@code ck_rcl_done} 은 그 열을
     * {@code COMPLETED}·{@code FAILED} 에서만 허용한다.
     *
     * @return 1이면 내가 선점한 것, 0이면 남이 이미 가져갔거나 상태가 바뀐 것
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RepairChecklist c
               set c.status = com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus.PROCESSING
             where c.checklistId = :checklistId
               and c.status = com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus.QUEUED
            """)
    int claimQueued(@Param("checklistId") Long checklistId);

    /**
     * 고아 {@code PROCESSING} 을 찾는다 — 처리 도중 프로세스가 사라진 건이다.
     *
     * <p><b>{@code created_at} 으로 볼 수밖에 없다.</b> {@code repair_checklist} 에는 상태가 바뀐
     * 시각을 담는 열이 없고({@code completed_at} 은 끝난 뒤에만 찬다) 스키마 변경은 이 작업의
     * 범위가 아니다. 그래서 기준은 "접수된 지 오래됐는데 아직 {@code PROCESSING}" 이며, 정상
     * 처리가 수 초에 끝나는 것을 전제로 임계값을 넉넉히 잡는다
     * ({@code EstimateValidationRepository#findStaleProcessingIds} 와 같은 제약·같은 판단이다).
     */
    @Query("""
            select c.checklistId from RepairChecklist c
             where c.status = com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus.PROCESSING
               and c.createdAt < :threshold
             order by c.createdAt asc, c.checklistId asc
            """)
    List<Long> findStaleProcessingIds(@Param("threshold") Instant threshold, Pageable pageable);
}
