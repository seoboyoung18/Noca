package com.ssafy.a307.analysis.service;

import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.AnalysisStage;
import com.ssafy.a307.analysis.entity.AnalysisStageType;
import com.ssafy.a307.analysis.event.AnalysisJobFinishedEvent;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.analysis.repository.AnalysisStageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@code analysis_stage} 를 채우는 유일한 곳 (S15P21A307-382 · -383).
 *
 * <h2>왜 네 단계를 한꺼번에 옮기는가</h2>
 *
 * <p>백엔드가 단계 진행을 알 방법이 없다. 결과 callback 에는 단계 필드가 없고
 * ({@code AnalysisCallbackRequest} 전체를 봐도 없다), AI 서버의 {@code /analyze} 는
 * 2026-09-15 기준 {@code 501 ANALYSIS_ORCHESTRATOR_NOT_IMPLEMENTED} 스텁이라
 * <b>진행 콜백을 보내는 코드가 아예 없다.</b>
 *
 * <p>그래서 <b>아는 만큼만 쓴다</b> — "끝났다" 하나다. 중간 진행은 보이지 않지만,
 * 빈 배열만 주던 것보다 낫다. 끝난 작업이 {@code doneStages=0 / totalStages=4} 로 나가던
 * 모순이 이것 때문이었다({@code AnalysisProgressResponse#of} 가 {@code doneStages} 를
 * 단계 행에서 세기 때문이다).
 *
 * <h2>{@code uk_as} 위에서 갱신한다</h2>
 *
 * <p>{@code uk_as UNIQUE (job_id, stage)} 가 있으므로 같은 단계를 두 번 넣으면 예외다.
 * 그래서 <b>있으면 갱신하고 없으면 만든다.</b> AI 는 callback 전송이 실패하면 최대 3회
 * 재시도하므로 같은 작업에 같은 사실이 네 번까지 도착할 수 있다.
 *
 * <p>덕분에 <b>나중에 진행 콜백이 생겨도 이 코드가 걸림돌이 되지 않는다.</b> 접수 시점에
 * {@code PENDING} 을 깔든 중간에 {@code RUNNING} 으로 옮기든, 끝날 때 이 코드가 같은 행을
 * 갱신한다.
 *
 * <h2>트랜잭션 — 결과가 본질이고 단계는 표시다</h2>
 *
 * <p>{@code REQUIRES_NEW} 다. 부르는 쪽이 커밋 뒤에 도는 이벤트 리스너라 활성 트랜잭션이
 * 없지만, 명시해 두면 <b>어디서 불러도 남의 트랜잭션을 더럽히지 않는다</b>는 뜻이 남는다.
 * 이 쓰기가 깨져도 이미 커밋된 분석 결과는 되돌아가지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisStageRecorder {

    private final AnalysisJobRepository jobRepository;
    private final AnalysisStageRepository stageRepository;

    /**
     * 작업의 네 단계를 종료 상태로 옮긴다.
     *
     * <p>작업이 사라졌으면 아무것도 하지 않는다. 사고 삭제가
     * {@code ON DELETE CASCADE} 로 작업까지 지우므로, 커밋과 이 호출 사이에 사고가 지워지면
     * 정상적으로 생길 수 있는 일이다. 없는 작업에 단계를 만들면 FK 위반으로 깨진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFinished(AnalysisJobFinishedEvent event) {
        AnalysisJob job = jobRepository.findById(event.jobId()).orElse(null);
        if (job == null) {
            log.info("분석 작업이 없어 단계 기록을 건너뛴다. jobId={}", event.jobId());
            return;
        }

        Map<AnalysisStageType, AnalysisStage> existing = new EnumMap<>(AnalysisStageType.class);
        for (AnalysisStage stage : stageRepository.findByJobId(event.jobId())) {
            existing.put(stage.getStage(), stage);
        }

        List<AnalysisStage> rows = new ArrayList<>(AnalysisStageType.TOTAL);
        for (AnalysisStageType type : AnalysisStageType.values()) {
            AnalysisStage stage = existing.get(type);
            if (stage == null) {
                stage = AnalysisStage.pending(job, type);
            }
            if (event.failed()) {
                stage.fail(event.finishedAt());
            } else {
                stage.complete(event.finishedAt());
            }
            rows.add(stage);
        }
        stageRepository.saveAll(rows);
    }
}
