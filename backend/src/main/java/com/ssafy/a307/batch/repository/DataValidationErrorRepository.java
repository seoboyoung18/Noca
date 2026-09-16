package com.ssafy.a307.batch.repository;

import com.ssafy.a307.batch.entity.BatchJobExecution;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 데이터 검증 격리 건 집계 (S15P21A307-355).
 *
 * <p>{@code -354} 본문 비고가 "데이터 검증 격리 건 확인 포함" 이라고 적었다. 그 자리다.
 *
 * <h2>엔티티를 만들지 않았다</h2>
 *
 * <p>{@code data_validation_error} 는 <b>개수와 분포만 필요하다.</b> 행 자체를 화면에
 * 내리지 않으므로 엔티티를 둘 이유가 없다. 한 배치에 수천 건이 쌓일 수 있어 목록을 내리는 것은
 * 별도 티켓이다.
 *
 * <p>그래서 {@link BatchJobExecution} 을 얹어 네이티브로만 센다. 이 인터페이스는
 * Spring Data 가 요구하는 자리 표시일 뿐이고 {@code BatchJobExecution} 을 다시 쓰지 않는다.
 */
public interface DataValidationErrorRepository extends JpaRepository<BatchJobExecution, Long> {

    /** 그 실행이 격리한 건수. 없으면 0. */
    @Query(value = """
            select count(*) from data_validation_error
             where batch_job_execution_id = :executionId
            """, nativeQuery = true)
    long countByExecution(@Param("executionId") Long executionId);

    /**
     * {@code error_type} 별 건수. 많은 순.
     *
     * <p>{@code Object[]} 로 받는다 — {@code [0]} 이 {@code error_type}, {@code [1]} 이 건수다.
     * 인터페이스 프로젝션을 쓰지 않은 것은 두 열뿐이라 그것이 더 무겁기 때문이다.
     */
    @Query(value = """
            select error_type, count(*) as cnt
              from data_validation_error
             where batch_job_execution_id = :executionId
             group by error_type
             order by cnt desc, error_type
            """, nativeQuery = true)
    List<Object[]> countByErrorType(@Param("executionId") Long executionId);
}
