package com.ssafy.a307.vehicle.repository;

import com.ssafy.a307.vehicle.entity.VehicleModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VehicleModelRepository extends JpaRepository<VehicleModel, Long> {

    List<VehicleModel> findByActiveTrueOrderByManufacturerAscModelNameAsc();

    Optional<VehicleModel> findByModelIdAndActiveTrue(Long modelId);

    Optional<VehicleModel> findByManufacturerAndModelNameAndActiveTrue(
            String manufacturer, String modelName);
}
