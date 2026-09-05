package com.ssafy.a307.guide.controller;

import com.ssafy.a307.guide.dto.OverlaySetMapping;
import com.ssafy.a307.guide.dto.ShootingGuideResponse;
import com.ssafy.a307.guide.dto.ShootingShot;
import com.ssafy.a307.guide.service.ShootingGuideService;
import com.ssafy.a307.vehicle.entity.VehicleType;
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
 * HTTP 계약만 본다. 필터를 꺼 두었으므로 <b>비인증 접근 검증이 아니다</b> —
 * 그것은 {@code ShootingGuidePublicAccessTest} 가 필터를 켠 채로 한다.
 */
@WebMvcTest(ShootingGuideController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ShootingGuideController")
class ShootingGuideControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShootingGuideService shootingGuideService;

    @Test
    @DisplayName("GET /api/guides/shooting — recommendedCount·overlaySets·shots 를 한 번에 내려준다")
    void getShootingGuide() throws Exception {
        given(shootingGuideService.getShootingGuide()).willReturn(new ShootingGuideResponse(
                10,
                List.of(new OverlaySetMapping(VehicleType.SEDAN, "SEDAN"),
                        new OverlaySetMapping(VehicleType.TRUCK, "SUV")),
                List.of(new ShootingShot(1, "FRONT", "전면", "차량 정면 전체가 화면에 들어오도록 촬영합니다", false),
                        new ShootingShot(9, "DAMAGE_CLOSE", "정면 근접", "파손 부위가 화면의 절반 이상을 차지하도록 가까이서 촬영합니다", true))));

        mockMvc.perform(get("/api/guides/shooting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendedCount").value(10))
                .andExpect(jsonPath("$.data.shots").isArray())
                .andExpect(jsonPath("$.data.shots[0].angleCode").value("FRONT"))
                .andExpect(jsonPath("$.data.shots[0].closeUp").value(false))
                .andExpect(jsonPath("$.data.shots[1].closeUp").value(true))
                // 오버레이 이미지 URL 은 서버가 주지 않는다 — 이미지는 FE 번들에 있다
                .andExpect(jsonPath("$.data.shots[0].overlayImageUrl").doesNotExist())
                // VAN·TRUCK 은 SUV 실루엣으로 폴백한다
                .andExpect(jsonPath("$.data.overlaySets[1].vehicleType").value("TRUCK"))
                .andExpect(jsonPath("$.data.overlaySets[1].overlaySet").value("SUV"));
    }
}
