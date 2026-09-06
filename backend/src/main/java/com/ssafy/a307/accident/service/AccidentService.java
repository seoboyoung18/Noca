package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
import com.ssafy.a307.accident.dto.AccidentResponse;
import com.ssafy.a307.accident.dto.ActualRepairCostRequest;
import com.ssafy.a307.accident.dto.ActualRepairCostResponse;
import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.vehicle.entity.Vehicle;
import com.ssafy.a307.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 차량 도메인과 같은 방식이다 — memberId 를 첫 파라미터로 받고,
 * 소유자 검사는 Repository 쿼리 조건에 넣는다.
 */
@Service
@RequiredArgsConstructor
public class AccidentService {

    private final AccidentRepository accidentRepository;
    private final VehicleRepository vehicleRepository;

    /**
     * 같은 차량으로 사고를 여러 건 접수할 수 있다. 막을 이유가 없어 검사하지 않는다.
     * 남의 차량·없는 차량·삭제된 차량은 모두 404 — 403 은 그 차량이 존재한다는 사실을 알려준다.
     */
    @Transactional
    public AccidentResponse create(Long memberId, AccidentCreateRequest request) {
        Vehicle vehicle = vehicleRepository
                .findActiveByVehicleIdAndMemberId(request.vehicleId(), memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "차량을 찾을 수 없습니다."));

        return AccidentResponse.from(accidentRepository.save(Accident.open(vehicle)));
    }

    @Transactional
    public ActualRepairCostResponse recordActualRepairCost(
            Long memberId, Long accidentId, ActualRepairCostRequest request) {
        Accident accident = accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));

        accident.recordActualRepair(
                request.actualRepairCost(),
                request.repairCompletedDate(),
                request.repairShopName().strip(),
                Instant.now());
        return ActualRepairCostResponse.from(accident);
    }
}
