package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.BatchJobDetailResponse;
import com.ssafy.a307.admin.dto.BatchJobExecutionResponse;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.batch.entity.BatchJobExecution;
import com.ssafy.a307.batch.repository.BatchJobExecutionRepository;
import com.ssafy.a307.batch.repository.DataValidationErrorRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치 실행 조회 (S15P21A307-355).
 *
 * <h2>조회는 {@code audit_log} 를 남기지 않는다</h2>
 *
 * <p>조회는 행위가 아니다. {@code AdminAccidentReviewService.findDetail} 과 같은 판단이다.
 * 남기는 것은 승인·반려·수동 실행처럼 <b>상태를 바꾸는 것</b>뿐이다.
 *
 * <h2>배치 목록의 출처 — 실행 기록에서 뽑는다</h2>
 *
 * <p>{@code batch_job_execution} 의 {@code job_name} 별 최신 행이다. 백엔드에 job 이름을
 * 상수로 두는 방법도 있었으나 <b>그러면 파이프라인에 job 이 늘 때마다 백엔드를 고쳐야 한다.</b>
 *
 * <p>대가가 하나 있다 — <b>한 번도 안 돈 job 은 목록에 나오지 않는다.</b> 2026-09-16 기준
 * {@code pipeline/jobs} 에 job 이 17개인데 기록을 남기는 것은 다섯뿐이라, 나머지 열둘은
 * 한 번 돌기 전까지 보이지 않는다. 그 한계는 answer79 에 적었다.
 */
@Service
@RequiredArgsConstructor
public class AdminBatchJobService {

    private final BatchJobExecutionRepository executionRepository;
    private final DataValidationErrorRepository validationErrorRepository;

    /**
     * job 별 최신 실행 한 건씩.
     *
     * <p>기록이 하나도 없으면 <b>빈 목록</b>이다. 404 가 아니다 — 아직 아무 배치도 안 돈
     * 것은 정상이고, 화면은 "실행 기록 없음" 을 그릴 수 있어야 한다.
     */
    @Transactional(readOnly = true)
    public List<BatchJobExecutionResponse> findLatestPerJob() {
        return executionRepository.findLatestPerJob().stream()
                .map(BatchJobExecutionResponse::from)
                .toList();
    }

    /**
     * 실행 이력. {@code jobName} 을 주면 그 job 만.
     *
     * @param jobName 비어 있으면 전체
     */
    @Transactional(readOnly = true)
    public AdminPageResponse<BatchJobExecutionResponse> findHistory(String jobName, Pageable pageable) {
        if (jobName == null || jobName.isBlank()) {
            return AdminPageResponse.of(
                    executionRepository.findAllByOrderByStartedAtDesc(pageable),
                    BatchJobExecutionResponse::from);
        }
        return AdminPageResponse.of(
                executionRepository.findByJobNameOrderByStartedAtDesc(jobName.strip(), pageable),
                BatchJobExecutionResponse::from);
    }

    /**
     * 실행 한 건과 격리 건 집계.
     *
     * <p>없는 {@code executionId} 는 <b>404</b> 다. 새 오류 코드를 만들지 않고 기존
     * {@code ADMIN_TARGET_NOT_FOUND} 를 쓴다.
     */
    @Transactional(readOnly = true)
    public BatchJobDetailResponse findDetail(Long executionId) {
        BatchJobExecution execution = executionRepository.findByBatchJobExecutionId(executionId)
                .orElseThrow(() -> AdminOperationException.notFound("배치 실행을 찾을 수 없습니다."));

        long errorCount = validationErrorRepository.countByExecution(executionId);
        List<BatchJobDetailResponse.ValidationErrorTypeCount> byType =
                validationErrorRepository.countByErrorType(executionId).stream()
                        .map(row -> new BatchJobDetailResponse.ValidationErrorTypeCount(
                                (String) row[0], ((Number) row[1]).longValue()))
                        .toList();

        return new BatchJobDetailResponse(
                BatchJobExecutionResponse.from(execution), errorCount, byType);
    }
}
