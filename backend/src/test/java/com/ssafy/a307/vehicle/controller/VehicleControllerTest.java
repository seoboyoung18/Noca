package com.ssafy.a307.vehicle.controller;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.vehicle.dto.VehicleResponse;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import com.ssafy.a307.vehicle.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 계약 검증 — 상태 코드, {data}/{error} 응답 형태, Bean Validation.
 * CurrentMemberProvider 를 대역으로 바꿔 넣고 필터를 꺼 둔다 — 이 슬라이스는 계약만 본다.
 * <b>인가 검증이 아니다.</b> 비로그인 401 은 {@code VehicleSecurityTest} 가 필터를 켠 채로 본다.
 */
@WebMvcTest(VehicleController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VehicleController")
class VehicleControllerTest {

    private static final long ME = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleService vehicleService;
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    private final VehicleResponse avante2020 = new VehicleResponse(
            7L, 1L, "현대", "아반떼", VehicleType.SEDAN, CarClass.MID_SIZE, 2020);

    @BeforeEach
    void setUp() {
        given(currentMemberProvider.currentMemberId()).willReturn(ME);
    }

    @Test
    @DisplayName("POST /api/vehicles — 201 과 모델 정보를 포함한 data 를 준다")
    void create() throws Exception {
        given(vehicleService.create(eq(ME), any())).willReturn(avante2020);

        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1, 2020)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.vehicleId").value(7))
                .andExpect(jsonPath("$.data.modelId").value(1))
                .andExpect(jsonPath("$.data.manufacturer").value("현대"))
                .andExpect(jsonPath("$.data.modelName").value("아반떼"))
                .andExpect(jsonPath("$.data.vehicleType").value("SEDAN"))
                .andExpect(jsonPath("$.data.carClass").value("Mid-size"))
                .andExpect(jsonPath("$.data.modelYear").value(2020));
    }

    @Test
    @DisplayName("POST /api/vehicles — modelId 가 없으면 400 INVALID_REQUEST")
    void createWithoutModelId() throws Exception {
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "modelYear": 2020 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").doesNotExist());

        then(vehicleService).should(never()).create(any(), any());
    }

    @Test
    @DisplayName("POST /api/vehicles — 연식 1979·2101 은 400, 1980·2100 은 통과")
    void createYearBoundaries() throws Exception {
        given(vehicleService.create(eq(ME), any())).willReturn(avante2020);

        expectCreateStatus(1979, status().isBadRequest());
        expectCreateStatus(2101, status().isBadRequest());
        expectCreateStatus(1980, status().isCreated());
        expectCreateStatus(2100, status().isCreated());
    }

    @Test
    @DisplayName("POST /api/vehicles — 없는 모델이면 서비스의 400 INVALID_REQUEST 가 그대로 나간다")
    void createWithUnknownModel() throws Exception {
        given(vehicleService.create(eq(ME), any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_REQUEST, "등록할 수 없는 차량 모델입니다."));

        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(999999, 2020)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("등록할 수 없는 차량 모델입니다."));
    }

    @Test
    @DisplayName("GET /api/vehicles/me — 배열을 vehicles 객체로 감싸 준다")
    void findMine() throws Exception {
        given(vehicleService.findMine(ME)).willReturn(List.of(avante2020));

        mockMvc.perform(get("/api/vehicles/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicles").isArray())
                .andExpect(jsonPath("$.data.vehicles[0].vehicleId").value(7))
                .andExpect(jsonPath("$.data.vehicles[0].carClass").value("Mid-size"));
    }

    @Test
    @DisplayName("GET /api/vehicles/me — 차량이 없으면 404 가 아니라 빈 배열")
    void findMineEmpty() throws Exception {
        given(vehicleService.findMine(ME)).willReturn(List.of());

        mockMvc.perform(get("/api/vehicles/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicles").isEmpty());
    }

    @Test
    @DisplayName("PATCH /api/vehicles/{id} — 연식을 바꾸면 200 과 등록 응답과 같은 형태를 준다")
    void update() throws Exception {
        VehicleResponse updated = new VehicleResponse(
                7L, 1L, "현대", "아반떼", VehicleType.SEDAN, CarClass.MID_SIZE, 2021);
        given(vehicleService.update(eq(ME), eq(7L), any())).willReturn(updated);

        mockMvc.perform(patch("/api/vehicles/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(2021)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelYear").value(2021))
                .andExpect(jsonPath("$.data.modelName").value("아반떼"));
    }

    @Test
    @DisplayName("PATCH /api/vehicles/{id} — 연식 1979·2101 은 400, 1980·2100 은 통과")
    void updateYearBoundaries() throws Exception {
        given(vehicleService.update(eq(ME), eq(7L), any())).willReturn(avante2020);

        mockMvc.perform(patch("/api/vehicles/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(1979)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(patch("/api/vehicles/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(2101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        // 범위 밖 연식은 Service 까지 가지 않는다
        then(vehicleService).should(never()).update(any(), any(), any());

        mockMvc.perform(patch("/api/vehicles/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(1980)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/vehicles/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(2100)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /api/vehicles/{id} — modelId 가 들어오면 조용히 무시하지 않고 400")
    void updateRejectsModelId() throws Exception {
        mockMvc.perform(patch("/api/vehicles/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "modelYear": 2021, "modelId": 2 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("modelId")));

        then(vehicleService).should(never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /api/vehicles/{id} — 남의 차량은 403 이 아니라 404")
    void updateOtherMembersVehicle() throws Exception {
        given(vehicleService.update(eq(ME), eq(7L), any()))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "차량을 찾을 수 없습니다."));

        mockMvc.perform(patch("/api/vehicles/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(2021)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE /api/vehicles/{id} — 204, 본문 없음")
    void deleteVehicle() throws Exception {
        mockMvc.perform(delete("/api/vehicles/7"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        then(vehicleService).should().delete(ME, 7L);
    }

    @Test
    @DisplayName("DELETE /api/vehicles/{id} — 없는 차량은 404")
    void deleteUnknownVehicle() throws Exception {
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "차량을 찾을 수 없습니다."))
                .when(vehicleService).delete(ME, 7L);

        mockMvc.perform(delete("/api/vehicles/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("차량을 찾을 수 없습니다."));
    }

    private void expectCreateStatus(int modelYear,
                                    org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1, modelYear)))
                .andExpect(expected);
    }

    private String createBody(long modelId, int modelYear) {
        return "{ \"modelId\": %d, \"modelYear\": %d }".formatted(modelId, modelYear);
    }

    private String updateBody(int modelYear) {
        return "{ \"modelYear\": %d }".formatted(modelYear);
    }
}
