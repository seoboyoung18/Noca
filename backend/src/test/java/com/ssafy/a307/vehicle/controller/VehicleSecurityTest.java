package com.ssafy.a307.vehicle.controller;

import com.ssafy.a307.auth.handler.OAuth2FailureHandler;
import com.ssafy.a307.auth.handler.OAuth2SuccessHandler;
import com.ssafy.a307.auth.handler.RestAccessDeniedHandler;
import com.ssafy.a307.auth.service.CustomOAuth2UserService;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.config.SecurityConfig;
import com.ssafy.a307.vehicle.service.VehicleModelService;
import com.ssafy.a307.vehicle.service.VehicleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 차량 API 5개의 인가. {@code AccidentSecurityTest} 와 같은 방식이다 —
 * <b>{@code addFilters = false} 를 쓰지 않는다.</b> 그것을 쓰면 시큐리티 필터가 통째로 빠져
 * 검증 대상 자체가 사라진다.
 *
 * <p>{@code VehicleControllerTest}·{@code VehicleModelControllerTest} 는 필터를 뺀 채
 * HTTP 계약(상태 코드·응답 봉투·Bean Validation)만 본다. <b>인가는 이 클래스가 본다.</b>
 *
 * <p>{@code GET /api/vehicle-models} 도 인증이 필요하다는 점을 함께 고정한다 —
 * {@code SecurityConfig.PUBLIC_PATHS} 에 없으므로 공개 API 가 아니다. 모델 목록을
 * 로그인 전 화면에서 부르려는 시도를 막는다.
 */
@WebMvcTest({VehicleController.class, VehicleModelController.class})
@Import(SecurityConfig.class)
@DisplayName("차량 API 인가")
class VehicleSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private VehicleService vehicleService;
    @MockitoBean
    private VehicleModelService vehicleModelService;
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    /*
     * SecurityConfig 가 소셜 로그인 연동으로 이 네 빈을 요구한다. @WebMvcTest 슬라이스에는
     * 그 빈들이 없으므로 대역으로 넣어 준다 — 없으면 컨텍스트가 뜨지 않아 401 단언에 닿지 못한다.
     */
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean
    private OAuth2SuccessHandler oAuth2SuccessHandler;
    @MockitoBean
    private OAuth2FailureHandler oAuth2FailureHandler;
    @MockitoBean
    private RestAccessDeniedHandler restAccessDeniedHandler;

    @Test
    @DisplayName("GET /api/vehicle-models — 비로그인 요청은 401이다")
    void unauthenticatedModelListIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/vehicle-models"))
                .andExpect(status().isUnauthorized());

        then(vehicleModelService).should(never()).findAllActive();
    }

    @Test
    @DisplayName("POST /api/vehicles — 비로그인 요청은 401이다")
    void unauthenticatedCreateIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"modelId\": 14, \"modelYear\": 2021 }"))
                .andExpect(status().isUnauthorized());

        then(vehicleService).should(never()).create(any(), any());
    }

    @Test
    @DisplayName("GET /api/vehicles/me — 비로그인 요청은 401이다")
    void unauthenticatedFindMineIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/vehicles/me"))
                .andExpect(status().isUnauthorized());

        then(vehicleService).should(never()).findMine(any());
    }

    @Test
    @DisplayName("PATCH /api/vehicles/{id} — 비로그인 요청은 401이다")
    void unauthenticatedUpdateIsUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/vehicles/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"modelYear\": 2022 }"))
                .andExpect(status().isUnauthorized());

        then(vehicleService).should(never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("DELETE /api/vehicles/{id} — 비로그인 요청은 401이다")
    void unauthenticatedDeleteIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/vehicles/1"))
                .andExpect(status().isUnauthorized());

        then(vehicleService).should(never()).delete(any(), any());
    }

    @Test
    @DisplayName("비로그인 요청은 CurrentMemberProvider 에 닿기 전에 끊긴다")
    void filterChainStopsBeforeMemberLookup() throws Exception {
        mockMvc.perform(get("/api/vehicles/me"))
                .andExpect(status().isUnauthorized());

        then(currentMemberProvider).should(never()).currentMemberId();
    }
}
