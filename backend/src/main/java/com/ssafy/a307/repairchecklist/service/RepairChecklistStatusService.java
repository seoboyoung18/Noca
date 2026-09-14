package com.ssafy.a307.repairchecklist.service;

import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체크리스트 생성 상태 조회 (S15P21A307-461). <b>조회만 한다</b> — 상태를 옮기는 코드가 없다.
 *
 * <p>없는 사고와 남의 사고는 <b>모두 404</b>, 사고는 있는데 아직 요청하지 않았으면
 * <b>빈 상태 200</b> 이다. {@code AnalysisProgressService} 와 같은 형태다 — 빈 상태를 404 로 내면
 * 화면이 "없는 사고" 와 "생성 전" 을 구분하지 못한다.
 */
@Service
@RequiredArgsConstructor
public class RepairChecklistStatusService {

    private final AccidentRepository accidentRepository;
    private final RepairChecklistRepository checklistRepository;

    @Transactional(readOnly = true)
    public RepairChecklistStatusResponse status(Long memberId, Long accidentId) {
        if (!accidentRepository.existsByAccidentIdAndMemberId(accidentId, memberId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다.");
        }

        return checklistRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .map(RepairChecklistStatusResponse::from)
                .orElseGet(RepairChecklistStatusResponse::notRequested);
    }
}
