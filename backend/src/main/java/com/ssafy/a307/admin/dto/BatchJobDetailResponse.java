package com.ssafy.a307.admin.dto;

import java.util.List;

/**
 * 배치 실행 상세 (S15P21A307-355).
 *
 * <p>실행 한 건과 <b>그 실행이 격리한 건</b>을 함께 준다. {@code -354} 본문 비고의
 * "데이터 검증 격리 건 확인 포함" 이 이것이다.
 *
 * <h2>격리 건의 목록을 내리지 않는다</h2>
 *
 * <p>개수와 {@code error_type} 별 분포만 준다. 한 배치가 수천 건을 격리할 수 있어 목록을
 * 그대로 내리면 응답이 통째로 커진다. <b>상세 목록이 필요하면 별도 티켓이다.</b>
 *
 * @param validationErrorCount 격리 건수. 없으면 0
 * @param validationErrorTypes 사유별 건수. 많은 순. 없으면 빈 목록
 */
public record BatchJobDetailResponse(
        BatchJobExecutionResponse execution,
        long validationErrorCount,
        List<ValidationErrorTypeCount> validationErrorTypes) {

    /**
     * 격리 사유 하나와 그 건수.
     *
     * @param errorType {@code data_validation_error.error_type} 원문
     */
    public record ValidationErrorTypeCount(String errorType, long count) {
    }
}
