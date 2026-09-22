package com.ssafy.a307.estimatevalidation.controller;

import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
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
 * 부위 목록 API 계약 (S15P21A307-568). 인가는 보지 않는다 — {@code /api/admin/**} 가 아니라
 * 로그인 회원 규칙이 걸린다.
 */
@WebMvcTest(PartCodeController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PartCodeController")
class PartCodeControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PartCodeRepository partCodeRepository;

    /** 관리 화면의 관심사(범위·버전·활성)는 사용자 응답에 나가지 않는다. */
    @Test
    @DisplayName("GET /api/part-codes — 코드·이름·위치만 준다")
    void listsSelectableParts() throws Exception {
        given(partCodeRepository.findByActiveTrueAndCodeScopeOrderByDisplayOrderAscPartCodeAsc(
                PartCodeScope.AI_LABEL)).willReturn(List.of(
                PartCode.register("FRONT_BUMPER", "앞 범퍼", "FRONT", (short) 1, PartCodeScope.AI_LABEL),
                PartCode.register("HEAD_LAMP_L", "헤드램프(좌)", "FRONT", (short) 2, PartCodeScope.AI_LABEL)));

        mockMvc.perform(get("/api/part-codes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].partCode").value("FRONT_BUMPER"))
                .andExpect(jsonPath("$.data[0].nameKo").value("앞 범퍼"))
                .andExpect(jsonPath("$.data[0].layoutZone").value("FRONT"))
                .andExpect(jsonPath("$.data[0].codeScope").doesNotExist())
                .andExpect(jsonPath("$.data[0].active").doesNotExist())
                .andExpect(jsonPath("$.data[0].version").doesNotExist());
    }
}
