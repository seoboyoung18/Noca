package com.ssafy.a307.guide.controller;

import com.ssafy.a307.guide.dto.ChecklistResponse;
import com.ssafy.a307.guide.dto.ChecklistStep;
import com.ssafy.a307.guide.service.ChecklistService;
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
 * 그것은 {@code ChecklistPublicAccessTest} 가 필터를 켠 채로 한다.
 */
@WebMvcTest(ChecklistController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ChecklistController")
class ChecklistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChecklistService checklistService;

    @Test
    @DisplayName("GET /api/guides/checklist — steps 객체로 감싸고 단계 → 항목 2단 구조로 내려간다")
    void getChecklist() throws Exception {
        given(checklistService.getChecklist()).willReturn(new ChecklistResponse(List.of(
                new ChecklistStep(1, "안전 확보", List.of("비상등을 켰습니다", "안전 삼각대를 설치했습니다")),
                new ChecklistStep(2, "상대 차량 확인", List.of("상대 차량 번호판을 촬영했습니다")))));

        mockMvc.perform(get("/api/guides/checklist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps").isArray())
                .andExpect(jsonPath("$.data.steps[0].order").value(1))
                .andExpect(jsonPath("$.data.steps[0].title").value("안전 확보"))
                .andExpect(jsonPath("$.data.steps[0].items[0]").value("비상등을 켰습니다"))
                .andExpect(jsonPath("$.data.steps[1].order").value(2));
    }
}
