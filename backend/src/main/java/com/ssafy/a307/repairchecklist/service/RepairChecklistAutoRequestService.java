package com.ssafy.a307.repairchecklist.service;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석이 끝난 작업의 사고에 체크리스트 생성을 요청한다 (S15P21A307-542).
 *
 * <h2>왜 리스너와 클래스를 나눴나</h2>
 *
 * <p>둘을 한 클래스에 두면 리스너가 자기 메서드를 직접 부르게 되어 <b>프록시를 타지 않는다</b> —
 * {@code @Transactional} 이 통째로 무시된다. 그리고 예외를 잡을 자리가 트랜잭션 <b>안</b>이
 * 되는데, 이미 rollback-only 가 된 트랜잭션은 커밋 시점에 다시 던진다
 * ({@link com.ssafy.a307.analysis.service.AnalysisJobFinishedListener} 가 같은 이유로 나뉘어 있다).
 *
 * <h2>왜 {@code REQUIRES_NEW} 인가</h2>
 *
 * <p>부르는 쪽이 커밋 뒤에 도는 이벤트 리스너라 <b>활성 트랜잭션이 없다.</b> 기본 전파
 * ({@code REQUIRED})로 두면 Spring 이 트랜잭션을 만들어 주기는 하지만 {@code AFTER_COMMIT}
 * 안에서는 그 트랜잭션이 커밋되지 않아 <b>INSERT 가 조용히 사라진다.</b>
 * {@link com.ssafy.a307.analysis.service.AnalysisStageRecorder} 가 먼저 겪고
 * {@code REQUIRES_NEW} 로 고정해 둔 자리다.
 *
 * <h2>왜 사고에서 회원을 되찾아 {@code request()} 를 부르나</h2>
 *
 * <p>자동 경로에는 로그인 사용자가 없다. 그렇다고 소유자 검사를 건너뛰는 메서드를 새로 만들면
 * <b>"사고 주인만 요청할 수 있다" 는 규칙이 두 벌</b>이 된다. 작업 → 사고 → 차량으로 주인을
 * 되찾아 {@link RepairChecklistRequestService#request} 라는 <b>같은 문</b>으로 들어간다.
 * 재시도·409·503 처리를 여기서 베끼지 않는 것도 같은 이유다.
 *
 * <h2>부위를 골라 다시 분석했으면 다시 만든다 (S15P21A307-570)</h2>
 *
 * <p>부품을 찾지 못해 산정하지 못한 분석도 {@code COMPLETED} 라 체크리스트가 이미 만들어져 있다 —
 * 부위가 빈 분석으로 만든 것이다. 그 사고에 사용자가 부위를 골라 다시 분석하면 완성된 체크리스트를
 * 재생성({@code S15P21A307-486})으로 다시 만든다. 사용자가 적은 항목은 재생성이 남긴다.
 *
 * <p><b>409 를 잡아 재생성으로 넘기지 않고 상태를 먼저 본다.</b> {@code request()} 가 던지는 순간
 * 이 트랜잭션은 rollback-only 가 되어, 뒤이어 부른 재생성도 커밋되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairChecklistAutoRequestService {

    private final AnalysisJobRepository jobRepository;
    private final RepairChecklistRequestService requestService;
    private final RepairChecklistRegenerateService regenerateService;
    private final RepairChecklistRepository checklistRepository;

    /**
     * 작업이 속한 사고의 체크리스트를 큐에 올린다.
     *
     * <p>작업이 사라졌으면 아무것도 하지 않는다. 사고 삭제가 {@code ON DELETE CASCADE} 로
     * 작업까지 지우므로 커밋과 이 호출 사이에 정상적으로 생길 수 있는 일이다.
     *
     * @throws com.ssafy.a307.common.exception.BusinessException 이미 완성됨(409)·키 없음(503) 등.
     *         <b>여기서 잡지 않는다</b> — 트랜잭션 경계 밖인 리스너가 잡는다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestFor(Long jobId) {
        AnalysisJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.info("분석 작업이 없어 체크리스트 자동 요청을 건너뛴다. jobId={}", jobId);
            return;
        }

        Accident accident = job.getAccident();
        Long memberId = accident.getVehicle().getMemberId();
        Long accidentId = accident.getAccidentId();

        if (job.getSelectedPartCode() != null && hasCompletedChecklist(accidentId, memberId)) {
            regenerateService.regenerate(memberId, accidentId);
            log.info("부위를 골라 다시 분석해 체크리스트를 다시 만든다. jobId={} accidentId={} partCode={}",
                    jobId, accidentId, job.getSelectedPartCode());
            return;
        }

        requestService.request(memberId, accidentId);
        log.info("체크리스트 자동 요청 접수. jobId={} accidentId={}", jobId, accidentId);
    }

    private boolean hasCompletedChecklist(Long accidentId, Long memberId) {
        return checklistRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .filter(checklist -> checklist.getStatus() == RepairChecklistStatus.COMPLETED)
                .isPresent();
    }
}
