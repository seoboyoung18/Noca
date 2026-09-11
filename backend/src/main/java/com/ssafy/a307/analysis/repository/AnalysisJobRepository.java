package com.ssafy.a307.analysis.repository;

import com.ssafy.a307.analysis.entity.AnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    /**
     * 소유자의 작업만 찾는다.
     *
     * <p><b>소유자 조건을 쿼리에 넣는다.</b> 조회한 뒤 자바에서 비교하면 비교를 잊는 실수가
     * 조용히 통과한다. {@code analysis_job} 에는 회원이 없으므로
     * {@code analysis_job → accident → vehicle → member} 경로로 판정한다.
     *
     * <p>없는 작업과 남의 작업이 <b>모두 빈 값</b>으로 돌아온다. 호출자는 둘을 구분하지 말고
     * 404 로 응답해야 한다 — 403 은 그 작업이 존재한다는 사실을 알려준다.
     */
    @Query("""
            select j from AnalysisJob j
            join j.accident a
            join a.vehicle v
            where j.jobId = :jobId and v.memberId = :memberId
            """)
    Optional<AnalysisJob> findByJobIdAndMemberId(@Param("jobId") Long jobId,
                                                 @Param("memberId") Long memberId);
}
