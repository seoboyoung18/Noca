package com.ssafy.a307.estimate.repository;

import com.ssafy.a307.estimate.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EstimateRepository extends JpaRepository<Estimate, Long> {

    /**
     * 이 분석 작업의 마지막 버전 번호. 다음 버전을 채번할 때 쓴다.
     * <p>
     * 행 전체를 읽지 않고 번호만 읽는다 — 채번에 필요한 건 숫자 하나뿐이고,
     * 재산정을 반복하면 버전이 쌓이기 때문이다.
     *
     * @return 견적이 하나도 없으면 비어 있다
     */
    @Query("select max(e.version) from Estimate e where e.jobId = :jobId")
    Optional<Short> findMaxVersionByJobId(@Param("jobId") Long jobId);

    /**
     * 최신 견적. 화면이 기본으로 보여 주는 것이며, 버전을 지정하지 않은 조회가 이걸 쓴다.
     */
    Optional<Estimate> findFirstByJobIdOrderByVersionDesc(Long jobId);

    boolean existsByJobIdAndVersion(Long jobId, short version);
}
