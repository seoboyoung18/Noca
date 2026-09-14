package com.ssafy.a307.analysis.repository;

import com.ssafy.a307.analysis.entity.AnalysisImageResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 이미지 단위 분석 결과 쓰기 (S15P21A307-157).
 *
 * <p><b>쓰기 전용이다.</b> 조회는 {@code EstimateReportRepository} 가 리포트 조립용 네이티브
 * 쿼리로 이미 하고 있다. 같은 테이블에 조회 경로를 둘로 만들면 어느 쪽이 정본인지 흐려진다.
 */
public interface AnalysisImageResultRepository extends JpaRepository<AnalysisImageResult, Long> {

    /** 재수신 대비. 멱등성이 앞에서 막지만, 막지 못한 경우에도 행이 겹치지 않게 한다. */
    List<AnalysisImageResult> findByJobId(Long jobId);
}
