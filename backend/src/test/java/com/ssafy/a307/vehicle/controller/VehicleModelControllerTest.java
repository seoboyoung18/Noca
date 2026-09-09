package com.ssafy.a307.vehicle.controller;

import com.ssafy.a307.vehicle.dto.VehicleModelResponse;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import com.ssafy.a307.vehicle.service.VehicleModelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 계약만 본다. 필터를 꺼 두었으므로 <b>인가 검증이 아니다</b> —
 * 비로그인 401 은 {@code VehicleSecurityTest} 가 필터를 켠 채로 본다.
 */
@WebMvcTest(VehicleModelController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VehicleModelController")
class VehicleModelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleModelService vehicleModelService;

    @Test
    @DisplayName("GET /api/vehicle-models — vehicleModels 객체로 감싸고 isActive 는 넣지 않는다")
    void findAll() throws Exception {
        given(vehicleModelService.findAllActive()).willReturn(List.of(
                new VehicleModelResponse(1L, "현대", "아반떼", VehicleType.SEDAN, CarClass.COMPACT),
                new VehicleModelResponse(2L, "기아", "K5", VehicleType.SEDAN, CarClass.MID_SIZE)));

        mockMvc.perform(get("/api/vehicle-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleModels").isArray())
                .andExpect(jsonPath("$.data.vehicleModels[0].modelId").value(1))
                .andExpect(jsonPath("$.data.vehicleModels[0].carClass").value("Compact"))
                .andExpect(jsonPath("$.data.vehicleModels[1].carClass").value("Mid-size"))
                .andExpect(jsonPath("$.data.vehicleModels[0].isActive").doesNotExist());
    }
}
