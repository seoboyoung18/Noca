package com.ssafy.a307.analysis.dto;

import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.analysis.entity.AnalysisStage;
import com.ssafy.a307.analysis.entity.AnalysisStageStatus;
import com.ssafy.a307.analysis.entity.AnalysisStageType;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * {@code GET /api/accidents/{accidentId}/analysis} 응답 — 분석 진행 상태.
 *
 * <h2>⚠️ 진행률 퍼센트와 남은 시간을 주지 않는다</h2>
 *
 * <p>화면 시안의 <b>68%</b> 와 <b>"약 10초 남음"</b> 은 <b>서버가 줄 수 없는 값이다.</b>
 * 기다리지 말 것.
 *
 * <ul>
 *   <li>퍼센트를 담을 열이 정본 DDL 어디에도 없다</li>
 *   <li>남은 시간을 추정할 근거(단계별 평균 소요 시간)를 저장하는 곳이 없다</li>
 * </ul>
 *
 * <p>서버가 아는 사실의 전부는 <b>단계 수</b>다. {@link #doneStages} / {@link #totalStages} 로
 * FE 가 환산하면 4단계 기준 <b>0 · 25 · 50 · 75 · 100%</b> 만 나온다.
 * 시안의 68% 같은 중간값은 나오지 않으며, <b>없는 값을 서버가 만들어 내지 않는다.</b>
 *
 * <h2>소요 시간도 서버가 계산하지 않는다</h2>
 *
 * <p>{@link #startedAt}·{@link #finishedAt} 두 시각을 그대로 준다. 리포트 화면의 "분석 소요 27초"
 * 는 FE 가 두 값의 차로 만든다. 초 단위 정수로 서버가 내려보내지 않는 이유는 <b>반올림·표기가
 * 표시 결정</b>이기 때문이다 — "27초" 로 보일지 "0분 27초" 로 보일지 바꿀 때마다 배포가 필요해진다.
 * 한글 라벨을 내려보내지 않는 것과 같은 판단이다.
 *
 * <h2>빈 상태</h2>
 *
 * <p>아직 분석을 요청하지 않은 사고는 <b>오류가 아니라 빈 상태 200</b> 이다
 * ({@link #notRequested()}). {@code jobId}·{@code status} 가 {@code null} 이고
 * {@code stages} 가 빈 배열이며 {@code doneStages} 는 0 이다.
 * {@code totalStages} 는 그때도 4다 — 단계 종류 수는 작업 유무와 무관하다.
 *
 * @param status       작업 전체 상태 {@code QUEUED · PROCESSING · COMPLETED · FAILED}.
 *                     단계별 상태({@link StageProgress#status})와 값 집합이 다르다
 * @param currentStage {@code RUNNING} 인 단계. 없으면 {@code null} — 대기 중이거나 이미 끝났다는 뜻이다.
 *                     한글 라벨이 아니라 <b>코드</b>다. 화면 문구는 FE 가 정한다
 * @param doneStages   {@code DONE} 인 단계 수. {@code FAILED} 는 세지 않는다
 * @param stages       진행 순서({@link AnalysisStageType} 선언 순서)로 정렬돼 있다.
 *                     <b>아직 행이 없는 단계는 들어 있지 않다</b> — 파이프라인이 만들지 않았을 뿐이라
 *                     비어 있는 것을 서버가 지어내지 않는다
 */
public record AnalysisProgressResponse(
        Long jobId,
        AnalysisJobStatus status,
        String failureReason,
        Instant startedAt,
        Instant finishedAt,
        int totalStages,
        int doneStages,
        AnalysisStageType currentStage,
        List<StageProgress> stages) {

    /**
     * 단계 하나.
     *
     * @param detail 파이프라인이 넣은 부가 설명. <b>서버가 만들지 않는다</b> — 대개 {@code null} 이다
     */
    public record StageProgress(
            AnalysisStageType stage,
            AnalysisStageStatus status,
            String detail,
            Instant startedAt,
            Instant finishedAt) {

        static StageProgress from(AnalysisStage stage) {
            return new StageProgress(
                    stage.getStage(),
                    stage.getStatus(),
                    stage.getDetail(),
                    stage.getStartedAt(),
                    stage.getFinishedAt());
        }
    }

    /** 분석을 아직 요청하지 않은 사고. 오류가 아니다. */
    public static AnalysisProgressResponse notRequested() {
        return new AnalysisProgressResponse(
                null, null, null, null, null, AnalysisStageType.TOTAL, 0, null, List.of());
    }

    public static AnalysisProgressResponse of(AnalysisJob job, List<AnalysisStage> stages) {
        List<AnalysisStage> ordered = stages.stream()
                .sorted(Comparator.comparing(stage -> stage.getStage().ordinal()))
                .toList();

        return new AnalysisProgressResponse(
                job.getJobId(),
                job.getStatus(),
                job.getFailureReason(),
                job.getStartedAt(),
                job.getFinishedAt(),
                AnalysisStageType.TOTAL,
                (int) ordered.stream().filter(AnalysisStage::isDone).count(),
                ordered.stream()
                        .filter(AnalysisStage::isRunning)
                        .map(AnalysisStage::getStage)
                        .findFirst()
                        .orElse(null),
                ordered.stream().map(StageProgress::from).toList());
    }
}
