package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.BatchJobDetailResponse;
import com.ssafy.a307.admin.dto.BatchJobExecutionResponse;
import com.ssafy.a307.admin.service.AdminBatchJobService;
import com.ssafy.a307.common.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배치 작업 조회 (S15P21A307-355).
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 403 이다. 이 컨트롤러가 권한을 따로 검사하지 않는 이유다.
 *
 * <h2>🔴 수동 실행 API 가 없다 (S15P21A307-356 미구현)</h2>
 *
 * <p>배치는 {@code pipeline/} 의 <b>파이썬 스크립트</b>이고 백엔드는 자바다. 2026-09-16 기준
 * {@code backend/src/main/java} 전체에 {@code ProcessBuilder} 도 {@code Runtime.getRuntime} 도
 * <b>한 번도 나오지 않는다.</b> 즉 <b>백엔드가 배치를 띄울 수단이 없다.</b>
 *
 * <p>수단을 새로 만드는 것은 배포 구조·실행 권한·보안이 걸린 결정이라 이 티켓(2pt)의 범위를
 * 넘는다. 그래서 <b>만들지 않고 보고했다</b> — answer79 참조.
 *
 * <p>덧붙여 중복 실행 차단도 지금은 온전할 수 없다. {@code batch_job_execution} 에
 * {@code job_name} 유일 제약이 없어 <b>동시 요청 둘이 함께 통과</b>한다. 막으려면 부분 유일
 * 인덱스가 필요한데 그것은 스키마 변경이다.
 */
@RestController
@RequestMapping("/api/admin/batch-jobs")
@RequiredArgsConstructor
public class AdminBatchJobController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminBatchJobService service;

    /**
     * 배치 목록 — job 별 최신 실행 한 건씩.
     *
     * <p>실행 기록이 하나도 없으면 <b>빈 배열</b>이다. 404 가 아니다.
     *
     * <p><b>한 번도 안 돈 job 은 나오지 않는다.</b> 이 표가 실행 기록이기 때문이다.
     */
    @GetMapping
    public ApiResponse<List<BatchJobExecutionResponse>> list() {
        return ApiResponse.of(service.findLatestPerJob());
    }

    /**
     * 실행 이력. 최신순.
     *
     * @param jobName 주면 그 job 만. 생략하면 전체
     * @param size    100 을 넘기면 100 으로 자른다
     */
    @GetMapping("/history")
    public ApiResponse<AdminPageResponse<BatchJobExecutionResponse>> history(
            @RequestParam(required = false) String jobName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ApiResponse.of(service.findHistory(jobName, pageable));
    }

    /**
     * 실행 한 건과 <b>데이터 검증 격리 건</b> 집계.
     *
     * <p>없는 실행은 404 다.
     */
    @GetMapping("/{executionId}")
    public ApiResponse<BatchJobDetailResponse> detail(@PathVariable Long executionId) {
        return ApiResponse.of(service.findDetail(executionId));
    }
}
