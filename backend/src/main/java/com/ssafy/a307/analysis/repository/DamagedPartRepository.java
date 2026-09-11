package com.ssafy.a307.analysis.repository;

import com.ssafy.a307.analysis.entity.DamagedPart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DamagedPartRepository extends JpaRepository<DamagedPart, Long> {

    /** {@code uk_dp (job_id, part_code)} 라 작업당 부품 1행이다. 정렬을 고정해 응답이 흔들리지 않게 한다. */
    List<DamagedPart> findByJob_JobIdOrderByPartCodeAsc(Long jobId);

    boolean existsByJob_JobIdAndPartCode(Long jobId, String partCode);
}
