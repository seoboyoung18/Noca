package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
import com.ssafy.a307.accident.dto.AccidentPageResponse;
import com.ssafy.a307.accident.dto.AccidentResponse;
import com.ssafy.a307.accident.dto.DirectVehicleInput;
import com.ssafy.a307.accident.dto.ActualRepairCostRequest;
import com.ssafy.a307.accident.dto.ActualRepairCostResponse;
import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.entity.VehicleInputType;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.vehicle.entity.Vehicle;
import com.ssafy.a307.vehicle.repository.VehicleRepository;
import com.ssafy.a307.vehicle.service.VehicleRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 차량 도메인과 같은 방식이다 — memberId 를 첫 파라미터로 받고,
 * 소유자 검사는 Repository 쿼리 조건에 넣는다.
 */
@Service
@RequiredArgsConstructor
public class AccidentService {

    private final AccidentRepository accidentRepository;
    private final VehicleRepository vehicleRepository;
    private final VehicleRegistrationService vehicleRegistrationService;

    /**
     * 같은 차량으로 사고를 여러 건 접수할 수 있다. 막을 이유가 없어 검사하지 않는다.
     * 남의 차량·없는 차량·삭제된 차량은 모두 404 — 403 은 그 차량이 존재한다는 사실을 알려준다.
     */
    @Transactional
    public AccidentResponse create(Long memberId, AccidentCreateRequest request) {
        if (!request.isVehicleInputValid()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "vehicleId와 directVehicle 중 정확히 하나가 필요합니다.");
        }

        Vehicle vehicle;
        VehicleInputType inputType;
        if (request.vehicleId() != null) {
            vehicle = vehicleRepository
                    .findActiveByVehicleIdAndMemberId(request.vehicleId(), memberId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NOT_FOUND, "차량을 찾을 수 없습니다."));
            inputType = VehicleInputType.REGISTERED;
        } else {
            DirectVehicleInput direct = request.directVehicle();
            vehicle = vehicleRegistrationService.registerByExactModel(
                    memberId, direct.manufacturer(), direct.modelName(), direct.modelYear());
            inputType = VehicleInputType.DIRECT;
        }

        return AccidentResponse.from(accidentRepository.save(Accident.open(vehicle, inputType)));
    }

    /**
     * 없는 사고·남의 사고·다른 회원 차량의 사고를 구분하지 않고 전부 404 다 —
     * 403 은 그 사고가 존재한다는 사실을 알려준다. {@code create} 의 차량 조회와 같은 판단이다.
     *
     * <p>소유자 검사는 Repository 쿼리 조건에 있고, 차량이 소프트 삭제되어도
     * 소유자에게서 사고를 숨기지 않는다. 차량 필드는 접수 당시 스냅샷이다.
     */
    @Transactional(readOnly = true)
    public AccidentResponse findOne(Long memberId, Long accidentId) {
        return accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .map(AccidentResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));
    }

    /** 사고가 한 건도 없으면 404 가 아니라 빈 목록이다. 폐차한 차량의 사고도 남는다. */
    @Transactional(readOnly = true)
    public List<AccidentResponse> findMine(Long memberId) {
        return accidentRepository.findAllByMemberId(memberId).stream()
                .map(AccidentResponse::from)
                .toList();
    }

    /** 페이지 파라미터 기본값. 명세서에 값이 없어 잠정치이며 컨트롤러와 이 상수만 고치면 된다. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 한 번에 내보낼 수 있는 상한. 초과 요청은 거절하지 않고 이 값으로 줄인다. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 페이지 단위 이력 조회.
     *
     * <p>범위를 넘는 {@code page} 는 404 가 아니라 <b>빈 목록 + 200</b> 이다. 목록 API 가
     * 비어 있는 것은 오류가 아니라는 {@link #findMine} 의 판단을 그대로 따른다.
     *
     * <p>음수 {@code page} 와 1 미만 {@code size} 는 400 이 아니라 각각 0·기본값으로 보정한다.
     * 잘못된 페이지 파라미터로 화면이 깨지는 것보다 첫 페이지를 보여주는 편이 낫고,
     * 명세서가 이 경우의 오류 코드를 정하지 않았다.
     */
    @Transactional(readOnly = true)
    public AccidentPageResponse findMinePaged(Long memberId, Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return AccidentPageResponse.from(
                accidentRepository.findPageByMemberId(memberId, PageRequest.of(safePage, safeSize)));
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
