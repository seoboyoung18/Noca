package com.ssafy.a307.vehicle.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.vehicle.dto.VehicleCreateRequest;
import com.ssafy.a307.vehicle.dto.VehicleResponse;
import com.ssafy.a307.vehicle.dto.VehicleUpdateRequest;
import com.ssafy.a307.vehicle.entity.Vehicle;
import com.ssafy.a307.vehicle.entity.VehicleModel;
import com.ssafy.a307.vehicle.repository.VehicleModelRepository;
import com.ssafy.a307.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * memberId 를 첫 파라미터로 받는다. 회원을 어디서 얻었는지(세션·토큰)는 Controller 만 안다.
 */
@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleModelRepository vehicleModelRepository;

    /**
     * 같은 회원이 같은 모델·연식을 여러 번 등록할 수 있다 (실제로 같은 차종 2대를 보유할 수 있다).
     * 중복은 검사하지 않는다.
     */
    @Transactional
    public VehicleResponse create(Long memberId, VehicleCreateRequest request) {
        VehicleModel model = vehicleModelRepository.findByModelIdAndActiveTrue(request.modelId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "등록할 수 없는 차량 모델입니다."));

        Vehicle vehicle = vehicleRepository.save(
                Vehicle.register(memberId, model, request.modelYear().shortValue()));

        // 유효성 검사로 이미 읽은 model 을 그대로 쓴다. 추가 조회 없음.
        return VehicleResponse.from(vehicle);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> findMine(Long memberId) {
        return vehicleRepository.findAllActiveByMemberId(memberId).stream()
                .map(VehicleResponse::from)
                .toList();
    }

    @Transactional
    public VehicleResponse update(Long memberId, Long vehicleId, VehicleUpdateRequest request) {
        Vehicle vehicle = vehicleRepository.findActiveByVehicleIdAndMemberId(vehicleId, memberId)
                .orElseThrow(this::vehicleNotFound);

        vehicle.changeModelYear(request.modelYear().shortValue());
        return VehicleResponse.from(vehicle);
    }

    /** 소프트 삭제. 사고 이력이 있어도 막지 않고, 이미 삭제된 차량도 그대로 성공시킨다. */
    @Transactional
    public void delete(Long memberId, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findByVehicleIdAndMemberId(vehicleId, memberId)
                .orElseThrow(this::vehicleNotFound);

        vehicle.softDelete(Instant.now());
    }

    /** 남의 차량도 없는 차량과 똑같이 404 — 403 은 그 차량이 존재한다는 사실을 알려준다. */
    private BusinessException vehicleNotFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "차량을 찾을 수 없습니다.");
    }
}
