package com.ssafy.a307.analysis.service;

import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.analysis.dto.AnalysisResultResponse;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.analysis.repository.AnalysisResultQueryRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.domain.RepairMethodDisplay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * 분석 결과 조회 (S15P21A307-203). <b>조회만 한다.</b>
 *
 * <p>소유자 조건은 자바 비교가 아니라 <b>쿼리 조건</b>에 있다. 없는 사고와 남의 사고를
 * 구분하지 않고 둘 다 404 다 — 403 은 "그 사고는 존재한다" 는 사실을 알려 준다.
 * {@link AnalysisProgressService} 와 같은 판단이고, 두 API 가 서로 다른 판정을 내리면
 * 한쪽으로 새어 나간다.
 *
 * <p>사고는 있는데 작업이 없으면 <b>빈 상태 200</b> 이다. 진행 상태 API 와 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisResultService {

    private final AccidentRepository accidentRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisResultQueryRepository resultRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public AnalysisResultResponse result(Long memberId, Long accidentId) {
        if (!accidentRepository.existsByAccidentIdAndMemberId(accidentId, memberId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다.");
        }

        Optional<AnalysisJob> latest = analysisJobRepository
                .findByAccidentIdAndMemberId(accidentId, memberId).stream()
                .findFirst();
        if (latest.isEmpty()) {
            return AnalysisResultResponse.notRequested();
        }

        AnalysisJob job = latest.get();
        return new AnalysisResultResponse(
                job.getJobId(), job.getStatus(), job.getFailureReason(),
                parts(job.getJobId()), images(job.getJobId()));
    }

    private List<AnalysisResultResponse.Part> parts(Long jobId) {
        return resultRepository.findParts(jobId).stream()
                .map(view -> new AnalysisResultResponse.Part(
                        view.getPartCode(), view.getPartNameKo(), view.getLayoutZone(),
                        view.getDamageType(), view.getRepairMethod(),
                        // 방식이 미정이면 표시명도 없다. 없는 값을 "미정" 같은 한글로 지어내지
                        // 않는다 — 화면 문구는 FE 가 정한다.
                        view.getRepairMethod() == null
                                ? null
                                : RepairMethodDisplay.displayNameOf(view.getRepairMethod()),
                        view.getConfidence()))
                .toList();
    }

    private List<AnalysisResultResponse.Image> images(Long jobId) {
        return resultRepository.findImages(jobId).stream()
                .map(view -> new AnalysisResultResponse.Image(
                        view.getImageId(), view.getAngleCode(),
                        view.getWidth(), view.getHeight(),
                        view.getExcluded(), view.getExclusionReason(),
                        detections(view.getImageId(), view.getDetections())))
                .toList();
    }

    /**
     * 저장된 원문을 그대로 통과시킨다.
     *
     * <p><b>읽지 못해도 그 사진만 비운다.</b> 검출 하나가 깨졌다고 분석 결과 전체를 500 으로
     * 끊으면 사용자는 부위 목록도 못 본다 — {@code RefConditionReader} 가 근거를 다루는 방식과
     * 같은 판단이다. 원문을 로그에 남기지 않는다. 좌표는 개인 사진의 내용이고 로그는 화면보다
     * 오래 남는다.
     */
    private JsonNode detections(Long imageId, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (RuntimeException e) {
            log.warn("검출 좌표를 읽지 못해 비운다. imageId={} (길이 {})", imageId, raw.length(), e);
            return null;
        }
    }
}
