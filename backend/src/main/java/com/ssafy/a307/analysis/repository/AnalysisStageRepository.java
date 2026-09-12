package com.ssafy.a307.analysis.repository;

import com.ssafy.a307.analysis.entity.AnalysisStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnalysisStageRepository extends JpaRepository<AnalysisStage, Long> {

    /**
     * 한 작업의 단계 전부.
     *
     * <p><b>정렬을 쿼리에 두지 않는다.</b> {@code stage} 는 문자열 컬럼이라 DB 정렬은 알파벳순
     * ({@code DETECT · ESTIMATE · MATCH · PREPROCESS})이 되어 진행 순서와 어긋난다.
     * 진행 순서의 정본은 {@code AnalysisStageType} 의 선언 순서이므로 서비스가 자바에서 정렬한다.
     *
     * <p>소유자 검사를 여기서 하지 않는다 — 호출 전에 사고 소유권을 이미 확인했고,
     * 이 조회는 그렇게 얻은 {@code jobId} 로만 들어온다.
     */
    @Query("select s from AnalysisStage s where s.job.jobId = :jobId")
    List<AnalysisStage> findByJobId(@Param("jobId") Long jobId);
}
