package com.ssafy.a307.vehicle.service;

import com.ssafy.a307.vehicle.dto.VehicleModelResponse;
import com.ssafy.a307.vehicle.repository.VehicleModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleModelService {

    private final VehicleModelRepository vehicleModelRepository;

    /** 등록 화면 드롭다운용. 마스터는 수백 행 수준이라 전체를 한 번에 내려준다. */
    @Transactional(readOnly = true)
    public List<VehicleModelResponse> findAllActive() {
        return vehicleModelRepository.findByActiveTrueOrderByManufacturerAscModelNameAsc().stream()
                .map(VehicleModelResponse::from)
                .toList();
    }
}
