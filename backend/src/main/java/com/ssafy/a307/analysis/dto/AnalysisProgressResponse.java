package com.ssafy.a307.analysis.dto;

import com.ssafy.a307.analysis.entity.AnalysisImageResult;
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
 * @param retryCount   이 작업이 몇 번째 재시도인가 (S15P21A307-161). 처음 요청은 0, 최대 3.
 *                     {@code FAILED} 이고 3 미만이면 화면이 "다시 시도" 를 보여 줄 수 있다
 * @param currentStage {@code RUNNING} 인 단계. 없으면 {@code null} — 대기 중이거나 이미 끝났다는 뜻이다.
 *                     한글 라벨이 아니라 <b>코드</b>다. 화면 문구는 FE 가 정한다
 * @param doneStages   {@code DONE} 인 단계 수. {@code FAILED} 는 세지 않는다
 * @param stages       진행 순서({@link AnalysisStageType} 선언 순서)로 정렬돼 있다.
 *                     <b>아직 행이 없는 단계는 들어 있지 않다</b> — 파이프라인이 만들지 않았을 뿐이라
 *                     비어 있는 것을 서버가 지어내지 않는다
 * @param excludedImages 분석에서 제외된 사진 (S15P21A307-186). 없으면 빈 배열.
 *                       제외 판정은 <b>AI 서버가 한다</b> — 백엔드는 받은 값을 전달만 한다
 * @param partSelectionAvailable 사용자가 부위를 직접 골라 이어서 분석할 수 있는가 (S15P21A307-568).
 *                       파손은 검출됐는데 부품을 못 찾아 끝난 분석이면 {@code true} — 화면은
 *                       "다시 찍기" 대신 "부위 직접 고르기" 를 보여 준다. 재시도와 합친 횟수를
 *                       다 썼거나 분석 중이면 {@code false}. 판정은 {@code PartSelectionRule}
 */
public record AnalysisProgressResponse(
        Long jobId,
        AnalysisJobStatus status,
        String failureReason,
        int retryCount,
        Instant startedAt,
        Instant finishedAt,
        int totalStages,
        int doneStages,
        AnalysisStageType currentStage,
        List<StageProgress> stages,
        List<ExcludedImage> excludedImages,
        boolean partSelectionAvailable) {

    /**
     * 분석에서 빠진 사진 한 장 (S15P21A307-186).
     *
     * <p>차량이 아니거나 화면 속 차량 비율이 기준 미만이라 AI 가 제외한 것이다. 사용자가
     * "왜 이 사진은 반영이 안 됐나" 를 알 수 있어야 하므로 목록으로 내려보낸다.
     *
     * <p><b>{@code reason} 은 코드다. 한글 문구가 아니다.</b> {@code NOT_VEHICLE} ·
     * {@code RATIO_BELOW_THRESHOLD} 같은 값이 온다. 화면 문안은 FE 가 정한다 — 문구를 서버가
     * 박으면 표현을 바꿀 때마다 배포해야 하고, 이 저장소는 한글 라벨을 서버가 만들지 않는다.
     *
     * <p>열거형으로 고정하지 않은 이유는 {@code CallbackError#code} 와 같다. AI 가 사유를 하나
     * 더 만들었을 때 <b>역직렬화가 깨지면 제외 사실 자체를 잃는다.</b> 문자열로 받아 그대로 보존한다.
     *
     * @param reason 제외 사유 코드. AI 가 보낸 값 그대로다. 이론상 {@code null} 일 수 있다 —
     *               컬럼이 nullable 이고 계약이 필수로 걸지 않았다
     */
    public record ExcludedImage(Long imageId, String reason) {

        static ExcludedImage from(AnalysisImageResult result) {
            return new ExcludedImage(result.getImageId(), result.getExclusionReason());
        }
    }

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
                null, null, null, 0, null, null, AnalysisStageType.TOTAL, 0, null,
                List.of(), List.of(), false);
    }

    public static AnalysisProgressResponse of(AnalysisJob job, List<AnalysisStage> stages,
                                              List<AnalysisImageResult> excluded,
                                              boolean partSelectionAvailable) {
        List<AnalysisStage> ordered = stages.stream()
                .sorted(Comparator.comparing(stage -> stage.getStage().ordinal()))
                .toList();

        return new AnalysisProgressResponse(
                job.getJobId(),
                job.getStatus(),
                job.getFailureReason(),
                job.getRetryCount(),
                job.getStartedAt(),
                job.getFinishedAt(),
                AnalysisStageType.TOTAL,
                (int) ordered.stream().filter(AnalysisStage::isDone).count(),
                ordered.stream()
                        .filter(AnalysisStage::isRunning)
                        .map(AnalysisStage::getStage)
                        .findFirst()
                        .orElse(null),
                ordered.stream().map(StageProgress::from).toList(),
                // 이미지 순서로 고정한다. DB 가 돌려주는 순서에 기대면 화면의 목록 순서가
                // 요청마다 달라 보일 수 있다.
                excluded.stream()
                        .sorted(Comparator.comparing(AnalysisImageResult::getImageId))
                        .map(ExcludedImage::from)
                        .toList(),
                partSelectionAvailable);
    }
}
