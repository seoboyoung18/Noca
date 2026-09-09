package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.EstimateValidationReport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 검증 결과 PDF 리포트 큐.
 *
 * <p><b>⚠ {@code estimate_validation_report} 에는 큐 인덱스가 없다.</b> 정본 DDL 을 확인한
 * 결과 {@code estimate_report} 쪽에는 {@code ix_er_queue}·{@code ux_er_inflight} 가 있지만
 * 이 테이블에는 인덱스가 하나도 정의돼 있지 않다. 아래 큐 조회는 <b>전체 스캔</b>이다.
 * 검증 리포트는 검증 건수만큼만 생기므로 지금 규모에서는 문제가 되지 않지만,
 * DDL 을 고치는 것은 이 작업의 범위가 아니라 사실만 적어 둔다 (answer40 6장).
 */
public interface EstimateValidationReportRepository extends JpaRepository<EstimateValidationReport, Long> {

    /**
     * PDF 생성을 기다리는 리포트를 오래된 순으로 가져온다.
     *
     * <p><b>검증이 완료된 건만 대상이다.</b> 미완료 검증의 PDF 는 담을 내용이 없다 —
     * 등급도 항목도 아직 정해지지 않았다.
     */
    @Query("""
            select r.validationId from EstimateValidationReport r
             where r.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.QUEUED
               and r.validation.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.COMPLETED
             order by r.createdAt asc, r.validationId asc
            """)
    List<Long> findQueuedIds(Pageable pageable);

    /**
     * 한 건을 선점한다. <b>{@code FOR UPDATE SKIP LOCKED} 대신 조건부 UPDATE 다.</b>
     *
     * <p>테스트가 H2 에서 돌아 그 문법에 기대면 워커를 검증할 수 없다. 조건부 UPDATE 는
     * 경쟁 상태에서 똑같이 안전하고 H2·PostgreSQL 양쪽에서 동작한다 — 두 워커가 같은 건을
     * 집으면 <b>하나만 1행을 바꾸고 나머지는 0행</b>이다.
     *
     * <p>{@code retry_count} 를 여기서 올린다. 선점이 곧 시도이고, 실패 시점에 올리면
     * 프로세스가 죽었을 때 시도가 세어지지 않아 무한히 재시도된다.
     * {@code ck_evr_retry} 가 0~3 이므로 <b>상한을 넘기면 UPDATE 자체가 깨진다</b> —
     * 그래서 조건에 상한을 함께 건다.
     *
     * <p>{@code completed_at} 을 건드리지 않는다 ({@code ck_evr_done}).
     *
     * @return 영향받은 행 수. 1이면 내가 선점한 것, 0이면 남이 가져갔거나 상한에 닿은 것
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update EstimateValidationReport r
               set r.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.PROCESSING,
                   r.retryCount = r.retryCount + 1,
                   r.failureReason = null
             where r.validationId = :validationId
               and r.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.QUEUED
               and r.retryCount < :maxRetryCount
            """)
    int claimQueued(@Param("validationId") Long validationId,
                    @Param("maxRetryCount") short maxRetryCount);

    /**
     * 재시도 상한에 닿은 채 큐에 남아 있는 건.
     *
     * <p>{@link #claimQueued} 가 상한 조건 때문에 이 건들을 영영 집지 못하므로, 그대로 두면
     * 큐에 남아 매 주기 후보로 조회된다. <b>실패로 종결해 큐에서 빼야 한다.</b>
     */
    @Query("""
            select r.validationId from EstimateValidationReport r
             where r.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.QUEUED
               and r.retryCount >= :maxRetryCount
             order by r.createdAt asc
            """)
    List<Long> findExhaustedIds(@Param("maxRetryCount") short maxRetryCount, Pageable pageable);

    /**
     * 오래 {@code PROCESSING} 인 고아 건 — 처리 도중 프로세스가 사라진 것이다.
     *
     * <p><b>{@code created_at} 으로 볼 수밖에 없다.</b> 상태가 바뀐 시각을 담는 열이 없고
     * DDL 변경은 이 작업의 범위가 아니다. 정상 처리는 초 단위이므로 임계값을 넉넉히 잡는다.
     */
    @Query("""
            select r.validationId from EstimateValidationReport r
             where r.status = com.ssafy.a307.estimatevalidation.domain.ValidationStatus.PROCESSING
               and r.createdAt < :threshold
             order by r.createdAt asc
            """)
    List<Long> findStaleProcessingIds(@Param("threshold") java.time.Instant threshold, Pageable pageable);
}
