package com.ssafy.a307.analysis.callback;

import com.ssafy.a307.analysis.callback.AnalysisCallbackController.AnalysisCallbackResponse;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * 분석 결과 callback 처리 (S15P21A307-157).
 *
 * <p>정본은 {@code Docs/Api/AI 서버 오류·재시도 처리 명세.md} 의 "중복 방지" 다. 세 가지를
 * 차례로 본 뒤에만 저장한다.
 *
 * <pre>
 *   1. 헤더 X-Request-Id 와 본문 requestId 가 같은가
 *   2. 본문 jobId 가 경로의 작업과 같은가
 *   3. 이미 처리한 요청인가 → 저장을 생략하고 200
 * </pre>
 *
 * <h2>왜 멱등성이 가장 중요한가</h2>
 *
 * <p>AI 서버는 callback 전송이 실패하면 1초·5초·20초 간격으로 <b>최대 3회</b> 재시도한다. 막지
 * 않으면 사용자는 분석을 한 번 했는데 견적 버전이 네 개가 된다. 화면은 최신 하나만 보여 주므로
 * 사용자는 모르지만, 이력을 열면 같은 값이 네 줄 쌓여 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisCallbackService {

    private final AnalysisJobRepository jobRepository;

    /**
     * 결과를 받는다.
     *
     * @param jobId           경로변수
     * @param requestIdHeader {@code X-Request-Id}
     * @return 처리 결과. 중복이면 {@code duplicate=true} 이고 아무것도 저장하지 않았다
     */
    @Transactional
    public AnalysisCallbackResponse receive(Long jobId, String requestIdHeader,
                                            AnalysisCallbackRequest request) {

        // 헤더와 본문이 다르면 어느 쪽을 멱등 키로 쓸지 알 수 없다. 하나를 골라 진행하면
        // 다음 재시도에서 다른 쪽이 키가 되어 중복 방지가 무너진다.
        if (!Objects.equals(requestIdHeader, request.requestId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "X-Request-Id 와 본문 requestId 가 다릅니다.");
        }
        if (!Objects.equals(jobId, request.jobId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "경로의 jobId 와 본문 jobId 가 다릅니다.");
        }

        // 없는 작업도 404 다. 토큰을 통과한 뒤이므로 존재 여부를 알려도 되지만, 굳이
        // 다른 문구를 주어 jobId 를 훑을 단서를 남기지 않는다.
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "존재하지 않는 경로입니다."));

        String stored = job.getRequestId();

        // 이 작업이 기다리던 요청이 아니다. 재분석으로 새 requestId 가 발급된 뒤 이전 시도의
        // callback 이 늦게 도착한 경우가 대표적이다. 철 지난 결과로 최신 견적을 덮지 않는다.
        //
        // stored 가 null 이면 받아들인다 — 분석 요청(S15P21A307-156)이 아직 없어 requestId 를
        // 심어 주는 경로가 없기 때문이다. 156 이 붙으면 이 분기는 자연히 닫힌다.
        if (stored != null && !stored.equals(request.requestId())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "이 작업이 기다리는 요청이 아닙니다.");
        }

        // 같은 요청을 이미 끝냈다. 저장하지 않고 200 — 재시도를 멈추게 하는 것이 목적이다.
        if (stored != null && job.finished()) {
            log.info("분석 결과 중복 수신 — 저장 생략. jobId={} requestId={} status={}",
                    jobId, request.requestId(), job.getStatus());
            return new AnalysisCallbackResponse(jobId, job.getStatus().name(), true);
        }

        Instant now = Instant.now();
        if (stored == null) {
            job.markProcessing(request.requestId(), now);
        }

        if (request.failed()) {
            job.markFailed(request.error().code(), request.modelVersion(),
                    request.pipelineVersionId(), now);
            log.warn("분석 실패 수신. jobId={} code={} retryable={}",
                    jobId, request.error().code(), request.error().retryable());
        } else {
            // TODO(S15P21A307-157 2/2): 결과 저장(damaged_part · estimate · estimate_item ·
            //   analysis_image_result)을 여기에 붙인다. 지금은 작업 상태만 옮긴다 —
            //   상태 전이와 저장을 한 커밋에 섞으면 어느 쪽이 깨졌는지 가려내기 어렵다.
            job.markCompleted(request.modelVersion(), request.pipelineVersionId(), now);
            log.info("분석 결과 수신. jobId={} estimable={} items={} images={}",
                    jobId, request.estimable(), request.items().size(),
                    request.imageResults().size());
        }

        return new AnalysisCallbackResponse(jobId, job.getStatus().name(), false);
    }
}
