package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.estimate.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code estimate_report} 큐 (S15P21A307-390·391·393).
 *
 * <p><b>엔티티 없이 네이티브 쿼리로만 다룬다.</b> 엔티티를 만들면 {@code ddl-auto=validate} 가
 * 테스트 스키마({@code schema-h2.sql})를 검사하는데 거기에 이 테이블이 없어 H2 로 도는 기존 테스트가
 * 전부 깨진다. 그 파일은 다른 담당 영역이라 건드리지 않는다 — {@code repair_case} 계열과 같은 판단이다.
 *
 * <p><b>상태 전이는 UPDATE 의 WHERE 절이 강제한다.</b> 엔티티의 전이 메서드 대신, 허용된 이전 상태가
 * 아니면 0행이 바뀐다. 정본 DDL 의 CHECK 셋({@code ck_er_status}·{@code ck_er_retry}·{@code ck_er_done})이
 * 경계이고, 조건에 그 경계를 그대로 옮겼다 — 4번째 선점이나 PROCESSING 의 completed_at 은 애초에
 * UPDATE 되지 않는다.
 *
 * <p><b>선점은 조건부 UPDATE 다</b>({@code EstimateValidationReportRepository} 와 같은 방식).
 * 두 워커가 같은 건을 노리면 하나만 1행을 바꾼다.
 */
public interface EstimatePdfRepository extends JpaRepository<Estimate, Long> {

    // ------------------------------------------------------------------ 소유·요청

    /** 소유자 검사는 견적 조회와 같은 경로다. 남의 견적과 없는 견적을 가르지 않는다. */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM estimate e
                  JOIN analysis_job aj ON aj.job_id = e.job_id
                  JOIN accident a ON a.accident_id = aj.accident_id
                  JOIN vehicle v ON v.vehicle_id = a.vehicle_id
                 WHERE e.estimate_id = :estimateId AND v.member_id = :memberId)
            """, nativeQuery = true)
    boolean existsOwnedEstimate(@Param("estimateId") Long estimateId, @Param("memberId") Long memberId);

    /** 워커가 리포트를 조립할 때 쓴다. 워커에는 요청자가 없어 소유자를 여기서 읽는다. */
    @Query(value = """
            SELECT v.member_id
              FROM estimate e
              JOIN analysis_job aj ON aj.job_id = e.job_id
              JOIN accident a ON a.accident_id = aj.accident_id
              JOIN vehicle v ON v.vehicle_id = a.vehicle_id
             WHERE e.estimate_id = :estimateId
            """, nativeQuery = true)
    Optional<Long> findOwnerMemberId(@Param("estimateId") Long estimateId);

    /**
     * 번호 발급을 날짜 단위로 한 줄로 세운다. 트랜잭션이 끝나면 풀린다.
     *
     * <p><b>왜 잠금인가</b> — "그날 최대값 + 1" 은 동시에 두 요청이 같은 값을 읽는다. 유일 제약에
     * 걸리면 다시 시도하는 방법은 PostgreSQL 에서 쓸 수 없다 — 제약 위반이 난 트랜잭션은 그 뒤
     * 문장을 모두 거절한다. 같은 잠금 안에서 "진행 중인 작업이 있는가" 도 확인하므로
     * {@code ux_er_inflight} 경합도 함께 사라진다.
     *
     * <p>함수 결과가 {@code void} 라 바깥에서 상수를 고른다.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:lockKey)) AS issued", nativeQuery = true)
    Integer lockIssuance(@Param("lockKey") long lockKey);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM estimate_report
                 WHERE estimate_id = :estimateId AND status IN ('QUEUED','PROCESSING'))
            """, nativeQuery = true)
    boolean existsInFlight(@Param("estimateId") Long estimateId);

    @Query(value = "SELECT MAX(report_no) FROM estimate_report WHERE report_no LIKE CONCAT(:prefix, '%')",
            nativeQuery = true)
    String findMaxReportNo(@Param("prefix") String prefix);

    @Modifying
    @Query(value = """
            INSERT INTO estimate_report (estimate_id, report_no, status)
            VALUES (:estimateId, :reportNo, 'QUEUED')
            """, nativeQuery = true)
    int insertQueued(@Param("estimateId") Long estimateId, @Param("reportNo") String reportNo);

    // ------------------------------------------------------------------ 조회

    /** 가장 최근 요청. 재생성하면 행이 쌓이므로(견적 1 : 리포트 N) 최신 한 건이 상태다. */
    @Query(value = """
            SELECT report_id AS reportId, report_no AS reportNo, status AS status,
                   retry_count AS retryCount, failure_reason AS failureReason, s3_key_pdf AS storageKey,
                   created_at AS createdAt, completed_at AS completedAt
              FROM estimate_report
             WHERE estimate_id = :estimateId
             ORDER BY created_at DESC, report_id DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<ReportView> findLatest(@Param("estimateId") Long estimateId);

    /** 내려받을 파일. 재생성 중이어도 이전 완료본은 받을 수 있다. */
    @Query(value = """
            SELECT report_id AS reportId, report_no AS reportNo, status AS status,
                   retry_count AS retryCount, failure_reason AS failureReason, s3_key_pdf AS storageKey,
                   created_at AS createdAt, completed_at AS completedAt
              FROM estimate_report
             WHERE estimate_id = :estimateId AND status = 'COMPLETED'
             ORDER BY completed_at DESC, report_id DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<ReportView> findLatestCompleted(@Param("estimateId") Long estimateId);

    @Query(value = """
            SELECT estimate_id AS estimateId, report_no AS reportNo
              FROM estimate_report WHERE report_id = :reportId
            """, nativeQuery = true)
    Optional<JobView> findJob(@Param("reportId") Long reportId);

    // ------------------------------------------------------------------ 워커

    @Query(value = """
            SELECT report_id FROM estimate_report
             WHERE status = 'QUEUED' AND retry_count < :maxRetry
             ORDER BY created_at, report_id
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findQueuedIds(@Param("maxRetry") short maxRetry, @Param("limit") int limit);

    /** 재시도 상한에 닿은 채 큐에 남은 건. 선점 조건에 걸려 아무도 집지 못하므로 따로 종결한다. */
    @Query(value = """
            SELECT report_id FROM estimate_report
             WHERE status = 'QUEUED' AND retry_count >= :maxRetry
             ORDER BY created_at
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findExhaustedIds(@Param("maxRetry") short maxRetry, @Param("limit") int limit);

    /**
     * 오래 {@code PROCESSING} 인 고아 — 처리 도중 프로세스가 사라진 건.
     * 상태가 바뀐 시각을 담는 열이 없어 {@code created_at} 으로 본다(검증 PDF 와 같은 한계).
     */
    @Query(value = """
            SELECT report_id FROM estimate_report
             WHERE status = 'PROCESSING' AND created_at < :threshold
             ORDER BY created_at
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findStaleProcessingIds(@Param("threshold") Instant threshold, @Param("limit") int limit);

    /** QUEUED → PROCESSING. 선점이 곧 시도라 여기서 횟수를 올린다. 상한 조건을 함께 건다. */
    @Modifying
    @Query(value = """
            UPDATE estimate_report
               SET status = 'PROCESSING', retry_count = retry_count + 1, failure_reason = NULL
             WHERE report_id = :reportId AND status = 'QUEUED' AND retry_count < :maxRetry
            """, nativeQuery = true)
    int claim(@Param("reportId") Long reportId, @Param("maxRetry") short maxRetry);

    /** PROCESSING → COMPLETED. 키 없이 완료로 두지 않는다 — 다운로드가 받을 파일을 못 찾는다. */
    @Modifying
    @Query(value = """
            UPDATE estimate_report
               SET status = 'COMPLETED', s3_key_pdf = :storageKey, failure_reason = NULL, completed_at = now()
             WHERE report_id = :reportId AND status = 'PROCESSING'
            """, nativeQuery = true)
    int complete(@Param("reportId") Long reportId, @Param("storageKey") String storageKey);

    /** PROCESSING → QUEUED. 재시도 여지가 있을 때만. */
    @Modifying
    @Query(value = """
            UPDATE estimate_report SET status = 'QUEUED'
             WHERE report_id = :reportId AND status = 'PROCESSING' AND retry_count < :maxRetry
            """, nativeQuery = true)
    int returnToQueue(@Param("reportId") Long reportId, @Param("maxRetry") short maxRetry);

    /** PROCESSING → FAILED. */
    @Modifying
    @Query(value = """
            UPDATE estimate_report
               SET status = 'FAILED', failure_reason = :reason, completed_at = now()
             WHERE report_id = :reportId AND status = 'PROCESSING'
            """, nativeQuery = true)
    int fail(@Param("reportId") Long reportId, @Param("reason") String reason);

    /** QUEUED → FAILED. 상한에 닿은 건만. PROCESSING 을 거치면 retry_count 가 4 가 되어 CHECK 위반이다. */
    @Modifying
    @Query(value = """
            UPDATE estimate_report
               SET status = 'FAILED', failure_reason = :reason, completed_at = now()
             WHERE report_id = :reportId AND status = 'QUEUED' AND retry_count >= :maxRetry
            """, nativeQuery = true)
    int abandon(@Param("reportId") Long reportId, @Param("maxRetry") short maxRetry,
                @Param("reason") String reason);

    interface ReportView {
        Long getReportId();

        String getReportNo();

        String getStatus();

        Short getRetryCount();

        String getFailureReason();

        /** 응답으로 내보내지 않는다. 다운로드 서명에만 쓴다. */
        String getStorageKey();

        /** 드라이버마다 타입이 달라 {@code Object} 다 — {@code NativeTimestamps} 참고. */
        Object getCreatedAt();

        Object getCompletedAt();
    }

    interface JobView {
        Long getEstimateId();

        String getReportNo();
    }
}
