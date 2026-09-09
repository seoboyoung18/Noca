package com.ssafy.a307.estimate.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.entity.ConfidenceGrade;
import com.ssafy.a307.estimate.entity.Estimate;
import com.ssafy.a307.estimate.repository.EstimateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 견적 버전 채번. 재산정할 때마다 행을 새로 만든다.
 *
 * <p><b>왜 덮어쓰지 않는가</b> — 사용자에게 이미 보여 준 견적을 고치면 근거가 사라진다.
 * "어제 120만원이라더니 오늘 90만원"이 됐을 때 무엇이 바뀌었는지 설명할 수 없다.
 * 버전을 쌓으면 이력이 남고, 화면은 최신만 보여 주면 된다.
 *
 * <p><b>산정 로직은 여기 없다.</b> 금액을 어떻게 구하는지는 S15P21A307-256·257 이고,
 * 이 클래스는 이미 구해진 값을 몇 번 버전으로 앉힐지만 정한다.
 */
@Service
@RequiredArgsConstructor
public class EstimateVersioningService {

    private final EstimateRepository estimateRepository;

    /**
     * 산정된 견적을 다음 버전으로 저장한다. 첫 산정이면 버전 1이다.
     */
    @Transactional
    public Estimate append(Long jobId, Estimate.Amounts amounts, ConfidenceGrade confidenceGrade) {
        short version = nextVersion(jobId);
        return save(Estimate.estimated(jobId, version, amounts, confidenceGrade));
    }

    /**
     * 산정하지 못한 견적을 다음 버전으로 저장한다.
     * <p>
     * 행을 만들지 않으면 화면이 "분석 중"과 "분석은 끝났는데 산정이 안 됨"을 구분하지 못한다.
     */
    @Transactional
    public Estimate appendNonEstimable(Long jobId, String reason) {
        short version = nextVersion(jobId);
        return save(Estimate.nonEstimable(jobId, version, reason));
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
        try {
            return estimateRepository.saveAndFlush(estimate);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "이미 재산정이 진행 중입니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
