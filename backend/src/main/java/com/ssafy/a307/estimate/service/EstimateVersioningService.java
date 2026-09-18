package com.ssafy.a307.estimate.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.entity.ConfidenceGrade;
import com.ssafy.a307.estimate.entity.Estimate;
import com.ssafy.a307.estimate.narrative.EstimateNarrative;
import com.ssafy.a307.estimate.narrative.EstimateNarrativeRepository;
import com.ssafy.a307.estimate.repository.EstimateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 견적 버전 채번. 재산정할 때마다 행을 새로 만든다.
 *
 * <p><b>왜 덮어쓰지 않는가</b> — 사용자에게 이미 보여 준 견적을 고치면 근거가 사라진다.
 * "어제 120만원이라더니 오늘 90만원"이 됐을 때 무엇이 바뀌었는지 설명할 수 없다.
 * 버전을 쌓으면 이력이 남고, 화면은 최신만 보여 주면 된다.
 *
 * <p><b>산정 로직은 여기 없다.</b> 금액을 어떻게 구하는지는 <b>AI 서버</b>가 정한다 —
 * 2026-09-12 AI 연동 계약이 유사 사례 검색과 견적 산정을 AI 담당으로 옮겼다. 이 클래스는
 * 이미 구해진 값을 몇 번 버전으로 앉힐지만 정한다.
 *
 * <p>부르는 곳은 {@code AnalysisResultPersister} 다(S15P21A307-157). callback 이 결과를
 * 실어 오면 그 트랜잭션 안에서 버전을 쌓는다.
 */
@Service
@RequiredArgsConstructor
public class EstimateVersioningService {

    private final EstimateRepository estimateRepository;

    /**
     * 리포트 요약 큐 (S15P21A307-537). <b>여기서 LLM 을 부르지 않는다</b> — 접수만 한다.
     */
    private final EstimateNarrativeRepository narrativeRepository;

    /**
     * 산정된 견적을 다음 버전으로 저장한다. 첫 산정이면 버전 1이다.
     *
     * @param unresolvedParts 총액에서 빠진 부위의 JSON 배열(S15P21A307-534). 없으면 {@code null}
     */
    @Transactional
    public Estimate append(Long jobId, Estimate.Amounts amounts, ConfidenceGrade confidenceGrade,
                           String unresolvedParts) {
        short version = nextVersion(jobId);
        return save(Estimate.estimated(jobId, version, amounts, confidenceGrade, unresolvedParts));
    }

    /**
     * 산정하지 못한 견적을 다음 버전으로 저장한다.
     * <p>
     * 행을 만들지 않으면 화면이 "분석 중"과 "분석은 끝났는데 산정이 안 됨"을 구분하지 못한다.
     *
     * @param unresolvedParts 산정하지 못한 부위의 JSON 배열(S15P21A307-534). 없으면 {@code null}
     */
    @Transactional
    public Estimate appendNonEstimable(Long jobId, String reason, String unresolvedParts) {
        short version = nextVersion(jobId);
        return save(Estimate.nonEstimable(jobId, version, reason, unresolvedParts));
    }

    @Transactional(readOnly = true)
    public Optional<Estimate> findLatest(Long jobId) {
        return estimateRepository.findFirstByJobIdOrderByVersionDesc(jobId);
    }

    /**
     * 다음 버전 번호. 조회와 저장 사이가 벌어져 있어 <b>이 값만으로는 유일성을 보장하지 못한다</b> —
     * 최종 방어선은 {@code UNIQUE(job_id, version)} 이고, {@link #save} 가 그 위반을 받는다.
     */
    private short nextVersion(Long jobId) {
        short last = estimateRepository.findMaxVersionByJobId(jobId)
                .orElse((short) (Estimate.FIRST_VERSION - 1));
        if (last >= Short.MAX_VALUE) {
            throw new BusinessException(ErrorCode.CONFLICT, "견적 버전이 한계에 도달했습니다.");
        }
        return (short) (last + 1);
    }

    /**
     * <b>재산정 요청이 동시에 두 번 오면 409 로 끊는다.</b> 둘 다 같은 다음 버전을 노리므로
     * 하나는 {@code uk_est} 를 위반한다. 이때 버전을 하나 더 올려 재시도하면 같은 분석 결과로
     * 견적이 두 개 생긴다 — 사용자에게는 의미 없는 중복이고, 어느 쪽이 맞는지도 알 수 없다.
     * <p>
     * {@code estimate_report} 가 {@code ux_er_inflight} 로 동시 생성을 1건만 허용하는 것과 같은 판단이다.
     */
    private Estimate save(Estimate estimate) {
        Estimate saved;
        try {
            saved = estimateRepository.saveAndFlush(estimate);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "이미 재산정이 진행 중입니다. 잠시 후 다시 시도해 주세요.");
        }
        queueNarrative(saved);
        return saved;
    }

    /**
     * 리포트 요약 생성을 접수한다 (S15P21A307-537).
     *
     * <p><b>견적 저장과 같은 트랜잭션이다.</b> 견적이 롤백되면 접수도 함께 사라지고, 견적이
     * 남으면 반드시 큐에 들어가 있다 — 나중에 이벤트로 접수하면 "견적은 있는데 요약은 영영
     * 안 만들어진" 건이 생긴다.
     *
     * <p><b>산정 불가 견적도 접수한다.</b> 금액이 없을수록 "왜 못 냈고 다음에 무엇을 하면
     * 되는지" 를 설명할 문장이 필요하다.
     *
     * <p>생성은 워커가 한다. 여기서 부르면 AI 결과 수신 스레드가 LLM 을 기다리게 되고,
     * AI 서버는 타임아웃으로 같은 결과를 세 번 더 보낸다.
     */
    private void queueNarrative(Estimate estimate) {
        // saveAndFlush 다. 이 행은 PK 를 직접 넣는(견적 id) 엔티티라 JPA 가 INSERT 를 커밋
        // 시점까지 미룰 수 있는데, 그러면 같은 트랜잭션에서 SQL 로 큐를 보는 쪽이 빈 결과를 본다.
        narrativeRepository.saveAndFlush(EstimateNarrative.queued(estimate.getEstimateId(), Instant.now()));
    }
}
