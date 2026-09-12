package com.ssafy.a307.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 분석 작업 한 건의 단계 하나. {@code uk_as UNIQUE (job_id, stage)} 라 <b>작업당 단계 1행</b>이다.
 *
 * <p>정본 DDL 이 이 테이블 위에 <b>"화면의 4단계 체크리스트"</b> 라고 적어 두었다
 * ({@code Docs/Erd/A307_ddl_final.sql:185}). 분석 중 화면이 보여 주는 "N/4" 가 이 행들이다.
 *
 * <h2>이 작업(prompt58)이 하지 <b>않는</b> 것 — 쓰기</h2>
 *
 * <p><b>행을 만들거나 상태를 옮기는 코드를 두지 않았다.</b> 단계를 진행시키는 것은 비동기 분석
 * 파이프라인({@code S15P21A307-155})의 몫이고 이 저장소에 그 코드가 없다.
 * {@link AnalysisJob} 이 같은 이유로 전이 메서드를 두지 않은 것과 같은 판단이다 —
 * 두 곳에서 상태를 옮기면 어느 쪽이 옳은지 알 수 없게 된다.
 *
 * <p>그래서 <b>정적 팩터리도 최소한만 둔다.</b> {@link #pending}·{@link #of} 는 테스트 픽스처와
 * 나중에 붙을 파이프라인이 쓸 입구이며, 지금 운영 경로에서 부르는 곳은 없다.
 *
 * <h2>{@code detail} 을 서버가 만들지 않는다</h2>
 *
 * <p>{@code detail} 은 정본에 있는 컬럼이라 그대로 통과시키지만 <b>값을 지어내지 않는다.</b>
 * 화면 문구("부품을 연결하고 있어요")는 FE 가 {@link AnalysisStageType} 코드로 정한다 —
 * 서버가 한글을 내려보내면 문구를 바꿀 때마다 배포가 필요하다.
 */
@Entity
@Table(name = "analysis_stage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisStage {

    /** {@code detail VARCHAR(100)}. 넘치면 INSERT 가 깨진다. */
    public static final int MAX_DETAIL_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stage_id")
    private Long stageId;

    /**
     * {@code analysis_stage} 에는 회원이 없다. 소유자 판정은
     * {@code analysis_stage → analysis_job → accident → vehicle → member} 경로이며
     * 이 엔티티는 그 판정을 하지 않는다 — {@link DamagedPart} 와 같다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private AnalysisJob job;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 20)
    private AnalysisStageType stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisStageStatus status;

    /** 단계별 부가 설명. 서버가 만들지 않는다 — 파이프라인이 넣은 값을 그대로 통과시킨다. */
    @Column(name = "detail", length = MAX_DETAIL_LENGTH)
    private String detail;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    private AnalysisStage(AnalysisJob job, AnalysisStageType stage, AnalysisStageStatus status,
                          String detail, Instant startedAt, Instant finishedAt) {
        this.job = job;
        this.stage = stage;
        this.status = status;
        this.detail = truncate(detail);
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /** 아직 시작하지 않은 단계. 정본 DDL 의 DEFAULT 와 같은 {@code PENDING} 이다. */
    public static AnalysisStage pending(AnalysisJob job, AnalysisStageType stage) {
        return of(job, stage, AnalysisStageStatus.PENDING, null, null, null);
    }

    public static AnalysisStage of(AnalysisJob job, AnalysisStageType stage,
                                   AnalysisStageStatus status, String detail,
                                   Instant startedAt, Instant finishedAt) {
        if (job == null) {
            throw new IllegalArgumentException("job 은 필수입니다.");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage 는 필수입니다.");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 는 필수입니다.");
        }
        return new AnalysisStage(job, stage, status, detail, startedAt, finishedAt);
    }

    public boolean isDone() {
        return status == AnalysisStageStatus.DONE;
    }

    public boolean isRunning() {
        return status == AnalysisStageStatus.RUNNING;
    }

    /** {@code detail} 이 VARCHAR(100) 이라 넘치면 자른다 — 길이 때문에 조회가 깨지지 않게. */
    private static String truncate(String detail) {
        if (detail == null || detail.length() <= MAX_DETAIL_LENGTH) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_LENGTH);
    }
}
