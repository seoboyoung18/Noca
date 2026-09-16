package com.ssafy.a307.batch.repository;

import com.ssafy.a307.batch.entity.BatchJobExecution;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 배치 실행 기록 조회 (S15P21A307-355).
 *
 * <p><b>쓰기 메서드를 두지 않는다.</b> 이 표를 채우는 것은 파이프라인이고 백엔드는 읽기만
 * 한다 — {@link BatchJobExecution} 머리말 참조.
 */
public interface BatchJobExecutionRepository extends JpaRepository<BatchJobExecution, Long> {

    /**
     * job 별 <b>가장 최근 실행</b> 한 건씩.
     *
     * <p>화면의 "배치 목록" 이 이것이다. {@code batch_job_execution} 은 실행 기록이라
     * <b>한 번도 안 돈 job 은 여기 나오지 않는다</b> — 그 한계는 answer 에 적었다.
     *
     * <p>같은 {@code job_name} 안에서 {@code started_at} 이 같은 행이 둘이면
     * {@code batch_job_execution_id} 가 큰 쪽을 고른다. 시각만으로는 순서가 갈리지 않는다.
     */
    @Query("""
            select e from BatchJobExecution e
             where e.batchJobExecutionId = (
                   select max(x.batchJobExecutionId) from BatchJobExecution x
                    where x.jobName = e.jobName
                      and x.startedAt = (select max(y.startedAt) from BatchJobExecution y
                                          where y.jobName = e.jobName))
             order by e.startedAt desc
            """)
    List<BatchJobExecution> findLatestPerJob();

    /** 한 job 의 실행 이력. 최신순. */
    Page<BatchJobExecution> findByJobNameOrderByStartedAtDesc(String jobName, Pageable pageable);

    /** 전체 실행 이력. 최신순. */
    Page<BatchJobExecution> findAllByOrderByStartedAtDesc(Pageable pageable);

    /**
     * 지금 돌고 있는 같은 이름의 실행.
     *
     * <p>중복 실행 차단(S15P21A307-356)이 이것을 본다. <b>DB 에 유일 제약이 없어</b>
     * 동시 요청 둘이 함께 통과할 수 있다 — answer 에 한계로 적었다.
     */
    @Query("""
            select e from BatchJobExecution e
             where e.jobName = :jobName and e.status = com.ssafy.a307.batch.entity.BatchJobStatus.RUNNING
             order by e.startedAt desc
            """)
    List<BatchJobExecution> findRunning(@Param("jobName") String jobName);

    /** 실행 한 건과 그 job 이름. */
    Optional<BatchJobExecution> findByBatchJobExecutionId(Long batchJobExecutionId);
}
