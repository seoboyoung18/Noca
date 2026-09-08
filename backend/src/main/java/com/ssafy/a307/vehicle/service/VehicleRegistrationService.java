package com.ssafy.a307.vehicle.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.vehicle.entity.Vehicle;
import com.ssafy.a307.vehicle.entity.VehicleModel;
import com.ssafy.a307.vehicle.repository.VehicleModelRepository;
import com.ssafy.a307.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 차량 등록 API와 사고 즉시 입력이 공유하는 차량 생성 경계. */
@Service
@RequiredArgsConstructor
public class VehicleRegistrationService {

    private final VehicleRepository vehicleRepository;
    private final VehicleModelRepository vehicleModelRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Vehicle registerByModelId(Long memberId, Long modelId, int modelYear) {
        VehicleModel model = vehicleModelRepository.findByModelIdAndActiveTrue(modelId)
                .orElseThrow(this::unsupportedModel);
        return register(memberId, model, modelYear);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Vehicle registerByExactModel(
            Long memberId, String manufacturer, String modelName, int modelYear) {
        VehicleModel model = vehicleModelRepository
                .findByManufacturerAndModelNameAndActiveTrue(manufacturer, modelName)
                .orElseThrow(this::unsupportedModel);
        return register(memberId, model, modelYear);
    }

    private Vehicle register(Long memberId, VehicleModel model, int modelYear) {
        return vehicleRepository.save(Vehicle.register(memberId, model, (short) modelYear));
    }

    private BusinessException unsupportedModel() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "등록할 수 없는 차량 모델입니다.");
    }
}
