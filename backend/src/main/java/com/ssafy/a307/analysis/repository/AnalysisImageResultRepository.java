package com.ssafy.a307.analysis.repository;

import com.ssafy.a307.analysis.entity.AnalysisImageResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 이미지 단위 분석 결과 (S15P21A307-157).
 *
 * <p>견적 리포트 조립은 {@code EstimateReportRepository} 가 네이티브 쿼리로 한다. 여기에 리포트용
 * 조회를 다시 만들지 않는다 — 같은 용도의 경로가 둘이면 어느 쪽이 정본인지 흐려진다.
 * 아래 {@link #findByJobIdAndExcludedTrue} 는 <b>제외 사유 안내</b>라는 다른 용도다.
 */
public interface AnalysisImageResultRepository extends JpaRepository<AnalysisImageResult, Long> {

    /** 재수신 대비. 멱등성이 앞에서 막지만, 막지 못한 경우에도 행이 겹치지 않게 한다. */
    List<AnalysisImageResult> findByJobId(Long jobId);

    /**
     * 분석에서 제외된 사진만 (S15P21A307-186).
     *
     * <p>사용자에게 "이 사진이 왜 빠졌는지" 를 알려 주기 위한 조회다. 제외되지 않은 사진은 이
     * 목록에 필요 없으므로 DB 에서 걸러 온다 — 전부 가져와 자바에서 거르면 사진이 많은 사고에서
     * 쓰지도 않을 {@code detections} JSONB 까지 통째로 읽어 온다.
     */
    List<AnalysisImageResult> findByJobIdAndExcludedTrue(Long jobId);
}
