package com.ssafy.a307.admin.dto;

import com.ssafy.a307.batch.entity.BatchJobExecution;
import com.ssafy.a307.batch.entity.BatchJobStatus;
import java.time.Duration;
import java.time.Instant;

/**
 * 배치 실행 한 건 (S15P21A307-355).
 *
 * <h2>소요 시간을 서버가 계산한다</h2>
 *
 * <p>{@code completed_at − started_at} 이다. <b>컬럼으로 두지 않았다</b> — 두 값과 어긋날
 * 수 있고, 어긋나면 어느 쪽이 맞는지 알 수 없다.
 *
 * <p>{@code RUNNING} 이면 {@code completedAt} 이 없으므로 {@code durationSeconds} 가
 * {@code null} 이다. 0 이 아니다 — 0 은 "즉시 끝났다" 로 읽힌다.
 *
 * <h2>{@code summary} 를 해석하지 않는다</h2>
 *
 * <p>JSON 원문을 그대로 내린다. job 마다 담는 것이 달라 자바가 구조를 정할 수 없다.
 * <b>처리 건수가 그 안에 있을 수 있으나 키 이름이 job 마다 다를 수 있어 꺼내지 않는다.</b>
 *
 * @param durationSeconds 소요 시간(초). {@code RUNNING} 이면 {@code null}
 * @param summary         job 이 남긴 JSON 원문. {@code null} 일 수 있다
 */
public record BatchJobExecutionResponse(
        Long executionId,
        String jobName,
        String jobVersion,
        BatchJobStatus status,
        String inputRef,
        Instant startedAt,
        Instant completedAt,
        Long durationSeconds,
        String summary,
        String errorMessage) {

    public static BatchJobExecutionResponse from(BatchJobExecution e) {
        return new BatchJobExecutionResponse(
                e.getBatchJobExecutionId(),
                e.getJobName(),
                e.getJobVersion(),
                e.getStatus(),
                e.getInputRef(),
                e.getStartedAt(),
                e.getCompletedAt(),
                durationSeconds(e.getStartedAt(), e.getCompletedAt()),
                e.getSummary(),
                e.getErrorMessage());
    }

    /**
     * 끝나지 않았으면 {@code null}.
     *
     * <p>{@code completed_at} 이 {@code started_at} 보다 앞서는 행이 생겨도 음수를 그대로
     * 내린다. 0 으로 감추면 데이터가 잘못됐다는 사실이 화면에서 사라진다.
     */
    private static Long durationSeconds(Instant started, Instant completed) {
        if (started == null || completed == null) {
            return null;
        }
        return Duration.between(started, completed).toSeconds();
    }
}
