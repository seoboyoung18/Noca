package com.ssafy.a307.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 배치 한 번의 실행 기록 (S15P21A307-355).
 *
 * <h2>백엔드는 읽기만 한다</h2>
 *
 * <p><b>이 행을 만드는 것은 {@code pipeline/} 의 파이썬 job 이다.</b> 2026-09-16 기준
 * 다섯 job 이 기록한다 — {@code build_search_sample_sql} · {@code validate_category_id_integrity} ·
 * {@code validate_search_readiness} · {@code flag_estimate_outliers} · {@code load_estimate_raw}.
 *
 * <p>그래서 이 엔티티에 상태를 바꾸는 메서드가 없다. 백엔드가 쓰기 시작하면 파이프라인과
 * 같은 행을 두 곳에서 고치게 되고, 그때는 누가 정본인지 정해야 한다.
 *
 * <h2>{@code summary} 를 {@code String} 으로 든다</h2>
 *
 * <p>{@code jsonb} 이고 <b>DDL 이 구조를 강제하지 않는다.</b> job 마다 담는 것이 다르므로
 * 자바 타입으로 묶을 수 없다. {@link JdbcTypeCode}{@code (SqlTypes.JSON)} 으로 컬럼 타입만
 * 맞추고 해석은 화면에 맡긴다 — {@code AnalysisImageResult.detections} 와 같은 판단이다.
 *
 * <p><b>처리 건수가 여기 들어 있을 수 있다.</b> 다만 키 이름이 job 마다 다를 수 있어
 * 백엔드가 꺼내 주지 않는다.
 *
 * <h2>소요 시간은 컬럼이 아니다</h2>
 *
 * <p>{@code completed_at} 에서 {@code started_at} 을 빼면 나온다. 열로 두면 두 값과
 * 어긋날 수 있다. {@code RUNNING} 이면 {@code completed_at} 이 없어 계산되지 않는다.
 */
@Entity
@Table(name = "batch_job_execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatchJobExecution {

    public static final int MAX_JOB_NAME_LENGTH = 100;
    public static final int MAX_JOB_VERSION_LENGTH = 100;
    public static final int MAX_INPUT_REF_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "batch_job_execution_id")
    private Long batchJobExecutionId;

    /** 어떤 배치인가. 파이썬 job 이 자기 이름을 넣는다. */
    @Column(name = "job_name", nullable = false, length = MAX_JOB_NAME_LENGTH)
    private String jobName;

    /** 그 job 의 판본. 없을 수 있다. */
    @Column(name = "job_version", length = MAX_JOB_VERSION_LENGTH)
    private String jobVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchJobStatus status;

    /** 무엇을 읽었나. 경로나 조건 문자열이다. */
    @Column(name = "input_ref", length = MAX_INPUT_REF_LENGTH)
    private String inputRef;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** {@code RUNNING} 이면 비어 있다. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** job 마다 구조가 다른 JSON 원문. 해석하지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary")
    private String summary;

    /** 실패 사유. {@code TEXT} 라 길이 제한이 없다. */
    @Column(name = "error_message")
    private String errorMessage;

    /** 아직 돌고 있는가. */
    public boolean running() {
        return status == BatchJobStatus.RUNNING;
    }
}
