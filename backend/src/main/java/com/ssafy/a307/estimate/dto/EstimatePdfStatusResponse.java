package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimate.pdf.EstimatePdfRepository.ReportView;
import com.ssafy.a307.estimate.repository.NativeTimestamps;

import java.time.Instant;

/**
 * 견적 PDF 생성 상태 (S15P21A307-341).
 *
 * <p>저장소 키는 담지 않는다. 파일은 다운로드 API 가 서명한 URL 로만 받는다.
 *
 * @param status        QUEUED · PROCESSING · COMPLETED · FAILED
 * @param retryCount    지금까지 시도한 횟수(최대 3)
 * @param failureReason 사용자에게 보여도 되는 분류 문구. 내부 예외 메시지가 아니다
 */
public record EstimatePdfStatusResponse(
        String reportNo,
        String status,
        int retryCount,
        String failureReason,
        Instant createdAt,
        Instant completedAt) {

    public static EstimatePdfStatusResponse from(ReportView view) {
        return new EstimatePdfStatusResponse(
                view.getReportNo(),
                view.getStatus(),
                view.getRetryCount() == null ? 0 : view.getRetryCount(),
                view.getFailureReason(),
                NativeTimestamps.toInstant(view.getCreatedAt()),
                NativeTimestamps.toInstant(view.getCompletedAt()));
    }
}
