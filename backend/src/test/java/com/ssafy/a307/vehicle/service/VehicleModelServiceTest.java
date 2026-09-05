package com.ssafy.a307.vehicle.service;

import com.ssafy.a307.vehicle.dto.VehicleModelResponse;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@DisplayName("VehicleModelService")
class VehicleModelServiceTest {

    @Autowired
    private VehicleModelService vehicleModelService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        insertModel("현대", "쏘나타", "SEDAN", "Mid-size", true);
        insertModel("기아", "카니발", "VAN", "Full-size", true);
        insertModel("기아", "K5", "SEDAN", "Mid-size", true);
        insertModel("기아", "프라이드", "SEDAN", "Compact", false);
    }

    @Test
    @DisplayName("is_active = false 인 모델은 빠지고 manufacturer, model_name 순으로 정렬된다")
    void returnsActiveModelsSorted() {
        List<VehicleModelResponse> models = vehicleModelService.findAllActive();

        assertThat(models)
                .extracting(VehicleModelResponse::modelName)
                .containsExactly("K5", "카니발", "쏘나타");
    }

    @Test
    @DisplayName("모델의 유형·차급을 코드값 그대로 담는다")
    void mapsCodeValues() {
        VehicleModelResponse carnival = vehicleModelService.findAllActive().stream()
                .filter(model -> model.modelName().equals("카니발"))
                .findFirst()
                .orElseThrow();

        assertThat(carnival.manufacturer()).isEqualTo("기아");
        assertThat(carnival.vehicleType()).isEqualTo(VehicleType.VAN);
        assertThat(carnival.carClass()).isEqualTo(CarClass.FULL_SIZE);
        assertThat(carnival.carClass().getCode()).isEqualTo("Full-size");
    }

    private void insertModel(String manufacturer, String modelName,
                             String vehicleType, String carClass, boolean active) {
        jdbcTemplate.update(
                "insert into vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)"
                        + " values (?, ?, ?, ?, ?)",
                manufacturer, modelName, vehicleType, carClass, active);
    }
}
