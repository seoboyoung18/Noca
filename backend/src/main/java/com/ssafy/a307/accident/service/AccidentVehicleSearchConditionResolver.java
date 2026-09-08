package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.AccidentVehicleSearchCondition;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 향후 유사 사례 검색 서비스가 직접 주입받을 사고 차량 조건 경계. */
@Service
@RequiredArgsConstructor
public class AccidentVehicleSearchConditionResolver {

    private final AccidentRepository accidentRepository;

    @Transactional(readOnly = true)
    public AccidentVehicleSearchCondition resolve(Long memberId, Long accidentId) {
        return accidentRepository.findVehicleSearchCondition(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));
    }
}
