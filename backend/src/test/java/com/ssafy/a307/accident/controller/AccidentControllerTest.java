package com.ssafy.a307.accident.controller;

import com.ssafy.a307.accident.dto.AccidentResponse;
import com.ssafy.a307.accident.dto.ActualRepairCostResponse;
import com.ssafy.a307.accident.entity.VehicleInputType;
import com.ssafy.a307.accident.service.AccidentService;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 계약 검증 — 상태 코드, {data}/{error} 응답 형태, Bean Validation.
 * 인증은 아직 없으므로 CurrentMemberProvider 를 대역으로 바꿔 넣는다.
 */
@WebMvcTest(AccidentController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AccidentController")
class AccidentControllerTest {

    private static final long ME = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccidentService accidentService;
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    private final AccidentResponse accident = new AccidentResponse(
            1L, 7L, VehicleInputType.REGISTERED, 14L, "현대", "아반떼",
            VehicleType.SEDAN, CarClass.MID_SIZE, 2020,
            Instant.parse("2026-09-04T12:00:00Z"));

    @BeforeEach
    void setUp() {
        given(currentMemberProvider.currentMemberId()).willReturn(ME);
    }

    @Test
    @DisplayName("POST /api/accidents — 201 과 차량 정보를 포함한 data 를 준다")
    void create() throws Exception {
        given(accidentService.create(eq(ME), any())).willReturn(accident);

        mockMvc.perform(post("/api/accidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "vehicleId": 7 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accidentId").value(1))
                .andExpect(jsonPath("$.data.vehicleId").value(7))
                .andExpect(jsonPath("$.data.vehicleInputType").value("REGISTERED"))
                .andExpect(jsonPath("$.data.modelId").value(14))
                .andExpect(jsonPath("$.data.manufacturer").value("현대"))
                .andExpect(jsonPath("$.data.modelName").value("아반떼"))
                .andExpect(jsonPath("$.data.vehicleType").value("SEDAN"))
                .andExpect(jsonPath("$.data.carClass").value("Mid-size"))
                .andExpect(jsonPath("$.data.modelYear").value(2020))
                .andExpect(jsonPath("$.data.vehicle").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/accidents — 즉시 입력은 201과 DIRECT 스냅샷을 반환한다")
    void createWithDirectVehicle() throws Exception {
        AccidentResponse direct = new AccidentResponse(
                2L, 8L, VehicleInputType.DIRECT, 14L, "현대", "아반떼",
                VehicleType.SEDAN, CarClass.MID_SIZE, 2020,
                Instant.parse("2026-09-04T12:00:00Z"));
        given(accidentService.create(eq(ME), any())).willReturn(direct);

        mockMvc.perform(post("/api/accidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "directVehicle": {
                                    "manufacturer": " 현대 ",
                                    "modelName": " 아반떼 ",
                                    "modelYear": 2020
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accidentId").value(2))
                .andExpect(jsonPath("$.data.vehicleId").value(8))
                .andExpect(jsonPath("$.data.vehicleInputType").value("DIRECT"))
                .andExpect(jsonPath("$.data.modelId").value(14))
                .andExpect(jsonPath("$.data.manufacturer").value("현대"))
                .andExpect(jsonPath("$.data.modelName").value("아반떼"))
                .andExpect(jsonPath("$.data.modelYear").value(2020));
    }

    @Test
    @DisplayName("POST /api/accidents — createdAt 은 ISO-8601 UTC 문자열이다")
    void createdAtIsIso8601() throws Exception {
        given(accidentService.create(eq(ME), any())).willReturn(accident);

        mockMvc.perform(post("/api/accidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "vehicleId": 7 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdAt").value("2026-09-04T12:00:00Z"));
    }

    @Test
    @DisplayName("POST /api/accidents — 두 입력 방식을 모두 생략하면 400 INVALID_REQUEST")
    void createWithoutVehicleInput() throws Exception {
        mockMvc.perform(post("/api/accidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").doesNotExist());

        then(accidentService).should(never()).create(any(), any());
    }

    @Test
    @DisplayName("POST /api/accidents — 두 입력 방식을 동시에 보내면 400 INVALID_REQUEST")
    void createWithBothVehicleInputs() throws Exception {
        mockMvc.perform(post("/api/accidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vehicleId": 7,
                                  "directVehicle": {
                                    "manufacturer": "현대",
                                    "modelName": "아반떼",
                                    "modelYear": 2020
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        then(accidentService).should(never()).create(any(), any());
    }

    @Test
    @DisplayName("POST /api/accidents — 즉시 입력 제조사·차량명 누락이나 공백은 400")
    void rejectsBlankDirectVehicleNames() throws Exception {
        expectDirectVehicleBadRequest("null", "\"아반떼\"", "2020");
        expectDirectVehicleBadRequest("\"   \"", "\"아반떼\"", "2020");
        expectDirectVehicleBadRequest("\"현대\"", "null", "2020");
        expectDirectVehicleBadRequest("\"현대\"", "\"   \"", "2020");

        then(accidentService).should(never()).create(any(), any());
    }

    @Test
    @DisplayName("POST /api/accidents — 즉시 입력 연식 누락·1979·2101은 400")
    void rejectsInvalidDirectVehicleYear() throws Exception {
        expectDirectVehicleBadRequest("\"현대\"", "\"아반떼\"", "null");
        expectDirectVehicleBadRequest("\"현대\"", "\"아반떼\"", "1979");
        expectDirectVehicleBadRequest("\"현대\"", "\"아반떼\"", "2101");

        then(accidentService).should(never()).create(any(), any());
    }

    @Test
    @DisplayName("POST /api/accidents — 남의 차량·없는 차량·삭제된 차량은 404 NOT_FOUND")
    void createWithInaccessibleVehicle() throws Exception {
        given(accidentService.create(eq(ME), any()))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "차량을 찾을 수 없습니다."));

        mockMvc.perform(post("/api/accidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "vehicleId": 7 }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("차량을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("PUT /api/accidents/{id}/actual-cost — 저장된 실제 수리 정보를 data 로 반환한다")
    void recordActualRepairCost() throws Exception {
        given(accidentService.recordActualRepairCost(eq(ME), eq(1L), any()))
                .willReturn(new ActualRepairCostResponse(
                        1L, 1_250_000, LocalDate.of(2026, 9, 1), "바른 정비소",
                        Instant.parse("2026-09-06T01:02:03Z")));

        mockMvc.perform(put("/api/accidents/1/actual-cost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actualRepairCost": 1250000,
                                  "repairCompletedDate": "2026-09-01",
                                  "repairShopName": "바른 정비소"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accidentId").value(1))
                .andExpect(jsonPath("$.data.actualRepairCost").value(1250000))
                .andExpect(jsonPath("$.data.repairCompletedDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.repairShopName").value("바른 정비소"))
                .andExpect(jsonPath("$.data.actualCostRecordedAt").value("2026-09-06T01:02:03Z"));
    }

    @Test
    @DisplayName("PUT actual-cost — 0 이하 금액, 미래 완료일, 공백 정비소명을 400으로 거부한다")
    void rejectsInvalidActualRepairInformation() throws Exception {
        mockMvc.perform(put("/api/accidents/1/actual-cost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actualRepairCost": 0,
                                  "repairCompletedDate": "2999-01-01",
                                  "repairShopName": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        then(accidentService).should(never()).recordActualRepairCost(any(), any(), any());
    }

    @Test
    @DisplayName("PUT actual-cost — 타인·없는 사고는 존재 여부를 숨기고 404를 반환한다")
    void inaccessibleAccidentForActualCostIsNotFound() throws Exception {
        given(accidentService.recordActualRepairCost(eq(ME), eq(1L), any()))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));

        mockMvc.perform(put("/api/accidents/1/actual-cost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actualRepairCost": 100000,
                                  "repairCompletedDate": "2026-09-01",
                                  "repairShopName": "정비소"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("사고를 찾을 수 없습니다."));
    }

    private void expectDirectVehicleBadRequest(
            String manufacturer, String modelName, String modelYear) throws Exception {
        mockMvc.perform(post("/api/accidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "directVehicle": {
                                    "manufacturer": %s,
                                    "modelName": %s,
                                    "modelYear": %s
                                  }
                                }
                                """.formatted(manufacturer, modelName, modelYear)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
