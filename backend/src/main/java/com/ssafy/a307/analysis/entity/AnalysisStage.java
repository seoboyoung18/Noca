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
 * <h2>누가 이 행을 쓰는가 (S15P21A307-382 · -383)</h2>
 *
 * <p>분석 결과 callback 이 커밋된 뒤 {@code AnalysisStageRecorder} 가 쓴다.
 * <b>백엔드가 아는 만큼만 쓴다.</b> {@code AnalysisCallbackRequest} 에 단계를 나타내는 필드가
 * 하나도 없고, AI 서버의 {@code /analyze} 는 2026-09-15 기준 501 스텁이라 <b>진행 콜백 자체가
 * 없다.</b> 그래서 알 수 있는 사실은 "끝났다" 하나뿐이고, 네 단계를 한꺼번에
 * {@code DONE}(실패면 {@code FAILED})으로 옮긴다.
 *
 * <p>즉 <b>중간 진행은 보이지 않는다.</b> 화면이 볼 수 있는 것은 "접수됨 → 완료" 두 상태다.
 * 그래도 빈 배열만 주던 이전보다는 낫다 — 끝난 작업이 {@code doneStages=0} 으로 나가던 것이
 * 이것 때문이었다.
 *
 * <p><b>{@code RUNNING} 으로 옮기는 메서드를 두지 않았다.</b> 부를 곳이 없어서다. 나중에
 * {@code /analyze} 와 진행 콜백이 생기면 {@code uk_as (job_id, stage)} 위에서 같은 행을
 * 갱신하면 되므로, 지금 쓰는 값이 그때 걸림돌이 되지 않는다.
 *
 * <h2>전이는 이 클래스에만 있다</h2>
 *
 * <p>{@code status}·{@code started_at}·{@code finished_at} 을 서비스가 따로 만지지 않는다.
 * {@code ck_as_status} 가 네 값을 강제하고 시각 두 개는 상태와 짝이 맞아야 하는데(DDL 에
 * CHECK 가 없어 <b>코드가 지켜야 한다</b>), 두 곳에서 옮기면 한쪽만 고쳐져 어긋난다 —
 * {@link AnalysisJob}·{@code AccidentReview} 와 같은 방식이다.
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

    // ── 상태 전이 (S15P21A307-382 · -383) ──────────────────────────────────
    //
    // 두 메서드가 전부다. 시각 처리 규칙도 여기 한 곳에만 있다.
    //
    //   · 끝난 단계는 finished_at 이 반드시 있다
    //   · started_at 이 비어 있으면 now 로 채운다 — 단계를 깔아 두는 접수 경로가 아직
    //     없어서(S15P21A307-155·156 미구현) PENDING 상태로 곧장 끝나는 일이 정상이다.
    //     "끝났는데 시작한 적이 없다" 는 행을 남기지 않는다

    /**
     * 단계를 끝냈다. {@code → DONE}.
     *
     * <p>콜백이 단계를 구분해 주지 않으므로 <b>네 단계가 같은 시각으로 끝난다.</b> 단계별
     * 소요 시간을 여기서 지어내지 않는다 — 없는 값을 만들면 화면이 그것을 사실로 읽는다.
     */
    public void complete(Instant now) {
        finishAt(AnalysisStageStatus.DONE, now);
    }

    /**
     * 단계가 실패했다. {@code → FAILED}.
     *
     * <p><b>어느 단계에서 실패했는지는 알 수 없다.</b> 콜백의 {@code error.code} 를 단계에
     * 대응시키는 표가 {@code Docs/Api/AI 서버 오류·재시도 처리 명세.md} 에 없다. 하나를 골라
     * 적으면 그것은 지어낸 값이므로, 부르는 쪽이 네 단계 전부에 이것을 적용한다.
     */
    public void fail(Instant now) {
        finishAt(AnalysisStageStatus.FAILED, now);
    }

    private void finishAt(AnalysisStageStatus finished, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        this.status = finished;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.finishedAt = now;
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
