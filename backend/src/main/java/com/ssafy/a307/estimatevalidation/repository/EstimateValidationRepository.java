package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface EstimateValidationRepository extends JpaRepository<EstimateValidation, Long> {

    Optional<EstimateValidationStateView> findProjectedByValidationIdAndMemberId(Long validationId, Long memberId);

    @EntityGraph(attributePaths = {"accident", "accident.vehicle", "accident.vehicle.model"})
    Optional<EstimateValidation> findByValidationIdAndMemberId(Long validationId, Long memberId);

    /**
     * 회원 전체 이력. <b>{@code validationId} 가 2차 정렬 키다 — 지우지 말 것.</b>
     *
     * <p>{@code created_at} 은 {@code TIMESTAMPTZ DEFAULT now()} 이고 PostgreSQL 의 {@code now()} 는
     * <b>트랜잭션 시작 시각</b>이다. 한 트랜잭션에서 여러 건을 만들면 {@code created_at} 이
     * 반드시 같아지고, 1차 키만으로는 상대 순서가 DB 가 정하는 대로가 된다. 그러면
     * <b>페이지네이션이 깨진다</b> — 1페이지에 보인 항목이 2페이지에 또 나오거나 사라진다.
     * {@code validation_id} 는 PK 이고 단조 증가하므로 같은 시각 안에서 생성 순서를 그대로 반영한다.
     * {@code findQueuedIds}·{@code AccidentRepository} 가 쓰는 것과 같은 규칙이다.
     *
     * <p><b>{@code @EntityGraph} 를 지우지 말 것.</b> {@code history} 가 사고·차량·모델을 읽으므로
     * 없으면 건수만큼 추가 쿼리가 나간다. 연관이 모두 ToOne 이라 {@code Page} 와 함께 써도
     * {@code HHH90003004}(컬렉션 페치 + 페이지네이션) 경고가 나지 않는다.
     */
    @EntityGraph(attributePaths = {"accident", "accident.vehicle", "accident.vehicle.model"})
    @Query("""
            select v from EstimateValidation v
             where v.memberId = :memberId
             order by v.createdAt desc, v.validationId desc
            """)
    Page<EstimateValidation> findHistoryByMemberId(@Param("memberId") Long memberId, Pageable pageable);

    /**
     * 한 사고의 완료된 검증들. 정렬 규칙은 {@link #findHistoryByMemberId} 와 같다.
     *
     * <p>여기서 순서가 흔들리면 <b>비용 비교의 "가장 최근 견적서" 가 뒤바뀐다</b> —
     * 최신 견적으로 협상하려는 사용자에게 이전 견적을 먼저 보여 주게 된다.
     *
     * <p>{@code @EntityGraph} 를 붙이지 않은 것은 의도다. 호출처({@code CostComparisonService})가
     * 검증 자신의 값만 읽고 사고·차량을 건드리지 않으므로 페치할 것이 없다.
     */
    @Query("""
            select v from EstimateValidation v
             where v.accident.accidentId = :accidentId
               and v.memberId = :memberId
               and v.status = :status
             order by v.createdAt desc, v.validationId desc
            """)
    List<EstimateValidation> findCompletedByAccident(
            @Param("accidentId") Long accidentId,
            @Param("memberId") Long memberId,
            @Param("status") com.ssafy.a307.estimatevalidation.domain.ValidationStatus status);

    /**
     * 큐에서 처리 대기 중인 건의 ID 를 오래된 순으로 가져온다.
     *
     * <p>정본 DDL 의 {@code ix_ev_queue (status, created_at) WHERE status IN ('QUEUED','PROCESSING')}
     * 부분 인덱스가 정확히 이 조회를 위해 있다. 엔티티가 아니라 ID 만 읽는 것은 의도다 —
     * 선점에 성공한 건만 뒤에서 통째로 읽으면 되고, 경쟁에서 진 건까지 엔티티로 부풀릴 이유가 없다.
     */
    @Query("""
            select v.validationId from EstimateValidation v
             where v.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.QUEUED
             order by v.createdAt asc, v.validationId asc
            """)
    List<Long> findQueuedIds(Pageable pageable);

    /**
     * 한 건을 선점한다. <b>{@code FOR UPDATE SKIP LOCKED} 대신 조건부 UPDATE 다.</b>
     *
     * <p>팀 핵심 쿼리 8-1 은 {@code SKIP LOCKED} 를 쓰지만 테스트가 H2 에서 돌아
     * 그 문법에 기대면 워커를 검증할 수 없다. 조건부 UPDATE 는 경쟁 상태에서 똑같이 안전하고
     * H2·PostgreSQL 양쪽에서 동작한다 — 두 워커가 같은 건을 집으면 <b>하나만 1행을 바꾸고
     * 나머지는 0행</b>이다. 0행을 받은 쪽은 건너뛴다.
     *
     * <p>{@code completed_at} 을 건드리지 않는다({@code ck_ev_done}).
     *
     * @return 영향받은 행 수. 1이면 내가 선점한 것, 0이면 남이 이미 가져갔거나 상태가 바뀐 것
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update EstimateValidation v
               set v.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.PROCESSING
             where v.validationId = :validationId
               and v.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.QUEUED
            """)
    int claimQueued(@Param("validationId") Long validationId);

    /**
     * 고아 {@code PROCESSING} 을 찾는다 — 프로세스가 죽어 아무도 손대지 않는 건이다.
     *
     * <p><b>{@code created_at} 으로 볼 수밖에 없다.</b> {@code estimate_validation} 에는
     * 상태가 바뀐 시각을 담는 열이 없고, DDL 변경은 이 작업의 범위가 아니다. 그래서 기준은
     * "접수된 지 오래됐는데 아직 PROCESSING" 이며, 정상 처리가 몇 초에 끝나는 것을 전제로
     * 임계값을 넉넉히 잡는다.
     *
     * <p>{@code MANUAL} 을 제외하는 것은 안전장치다. 직접 입력은 한 트랜잭션 안에서
     * PROCESSING 을 거쳐 COMPLETED 로 끝나므로 이 상태로 남을 수 없지만, 남았다면 워커가
     * 건드릴 대상이 아니다 — 워커는 파일을 읽어 처리하는데 직접 입력에는 파일이 없다.
     *
     * <p>{@code validationId} 2차 정렬 키는 {@link #findQueuedIds} 와 맞춘 것이다. 여기서는
     * 어느 것을 먼저 집든 결국 다 처리되므로 사용자에게 보이는 영향이 없지만, <b>같은 파일의
     * 세 큐 조회가 서로 다른 정렬 규칙을 갖지 않게</b> 한다 — 다르면 다음 사람이 그 차이에
     * 의미가 있는지 매번 확인해야 한다.
     */
    @Query("""
            select v.validationId from EstimateValidation v
             where v.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.PROCESSING
               and v.fileType <> com.ssafy.a307.estimatevalidation.domain.EstimateFileType.MANUAL
               and v.createdAt < :threshold
             order by v.createdAt asc, v.validationId asc
            """)
    List<Long> findStaleProcessingIds(@Param("threshold") java.time.Instant threshold, Pageable pageable);

    @Query(value = """
            SELECT CAST(a.accident_id AS VARCHAR) || ':' ||
                   COALESCE(CAST(ev.validation_id AS VARCHAR), 'none') AS recordKey,
                   a.accident_id AS accidentId, vm.car_class AS carClass,
                   e.estimate_id AS estimateId, e.version AS estimateVersion,
                   e.total_min AS aiTotalMin, e.total_median AS aiTotalMedian, e.total_max AS aiTotalMax,
                   ev.validation_id AS validationId, ev.claimed_total AS shopClaimedTotal,
                   a.actual_repair_cost AS actualRepairCost,
                   a.actual_repair_completed_date AS actualRepairCompletedDate,
                   a.actual_cost_recorded_at AS actualCostRecordedAt
              FROM accident a
              JOIN vehicle v ON v.vehicle_id = a.vehicle_id
              JOIN vehicle_model vm ON vm.model_id = v.model_id
              LEFT JOIN estimate_validation ev
                     ON ev.accident_id = a.accident_id AND ev.status = 'COMPLETED'
              LEFT JOIN estimate e ON e.estimate_id = COALESCE(
                     ev.estimate_id,
                     (SELECT e2.estimate_id
                        FROM estimate e2
                        JOIN analysis_job aj2 ON aj2.job_id = e2.job_id
                       WHERE aj2.accident_id = a.accident_id AND aj2.status = 'COMPLETED'
                       ORDER BY e2.created_at DESC, e2.version DESC
                       FETCH FIRST 1 ROW ONLY))
             WHERE a.actual_repair_cost IS NOT NULL AND a.accident_id > :afterAccidentId
             ORDER BY a.accident_id, ev.validation_id
            """, nativeQuery = true)
    List<ModelImprovementView> findModelImprovementRecords(
            @Param("afterAccidentId") Long afterAccidentId, Pageable pageable);

    interface ModelImprovementView {
        String getRecordKey();
        Long getAccidentId();
        String getCarClass();
        Long getEstimateId();
        Short getEstimateVersion();
        Integer getAiTotalMin();
        Integer getAiTotalMedian();
        Integer getAiTotalMax();
        Long getValidationId();
        Integer getShopClaimedTotal();
        Integer getActualRepairCost();
        LocalDate getActualRepairCompletedDate();
        OffsetDateTime getActualCostRecordedAt();
    }
}
