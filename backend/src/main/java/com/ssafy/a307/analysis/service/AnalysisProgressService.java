package com.ssafy.a307.analysis.service;

import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.analysis.dto.AnalysisProgressResponse;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.repository.AnalysisImageResultRepository;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.analysis.repository.AnalysisStageRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 분석 진행 상태 조회. <b>조회만 한다</b> — 단계 행을 만들거나 상태를 옮기는 코드는 없다
 * ({@code AnalysisStage} Javadoc).
 *
 * <h2>404 를 두 경우에 똑같이 낸다</h2>
 *
 * <p>없는 사고와 남의 사고를 구분하지 않는다. 403 은 "그 사고는 존재한다" 는 사실을 알려 준다 —
 * {@code AccidentService#findOne} · {@code EstimateQueryService} 와 같은 판단이다.
 * 소유자 조건은 자바 비교가 아니라 <b>쿼리 조건</b>에 있다.
 *
 * <h2>사고는 있는데 작업이 없으면 200 이다</h2>
 *
 * <p>아직 분석을 요청하지 않은 것이지 오류가 아니다. 빈 상태를 404 로 내면 화면이
 * "없는 사고" 와 "분석 전" 을 구분하지 못한다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisProgressService {

    private final AccidentRepository accidentRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisStageRepository analysisStageRepository;
    private final AnalysisImageResultRepository analysisImageResultRepository;

    @Transactional(readOnly = true)
    public AnalysisProgressResponse progress(Long memberId, Long accidentId) {
        if (!accidentRepository.existsByAccidentIdAndMemberId(accidentId, memberId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다.");
        }

        Optional<AnalysisJob> latest = analysisJobRepository
                .findByAccidentIdAndMemberId(accidentId, memberId).stream()
                .findFirst();

        // 제외된 사진은 작업이 있을 때만 조회한다. 작업이 없으면 결과 행도 없으므로 빈 쿼리를
        // 한 번 더 날릴 이유가 없다.
        return latest
                .map(job -> AnalysisProgressResponse.of(
                        job,
                        analysisStageRepository.findByJobId(job.getJobId()),
                        analysisImageResultRepository.findByJobIdAndExcludedTrue(job.getJobId())))
                .orElseGet(AnalysisProgressResponse::notRequested);
    }
}
