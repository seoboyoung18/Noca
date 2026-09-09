package com.ssafy.a307.estimatevalidation.controller;

import com.ssafy.a307.auth.handler.OAuth2FailureHandler;
import com.ssafy.a307.auth.handler.OAuth2SuccessHandler;
import com.ssafy.a307.auth.handler.RestAccessDeniedHandler;
import com.ssafy.a307.auth.service.CustomOAuth2UserService;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.config.SecurityConfig;
import com.ssafy.a307.estimatevalidation.service.CostComparisonService;
import com.ssafy.a307.estimatevalidation.service.EstimateFileValidationService;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 견적서 검증 API 8개와 사고 비용 비교 1개의 인가.
 * {@code AccidentSecurityTest} 와 같은 방식이며 <b>{@code addFilters = false} 를 쓰지 않는다.</b>
 *
 * <p>{@code EstimateValidationControllerTest} 는 필터를 뺀 채 HTTP 계약만 본다.
 * <b>인가는 이 클래스가 본다.</b>
 *
 * <p><b>같은 URL 의 두 진입점을 모두 고정한다</b> — {@code POST /api/estimate-validations} 는
 * {@code application/json}(직접 입력)과 {@code multipart/form-data}(파일 업로드)로 갈린다.
 * 둘 중 하나만 막히는 일이 없어야 한다.
 */
@WebMvcTest({EstimateValidationController.class, CostComparisonController.class})
@Import(SecurityConfig.class)
@DisplayName("견적서 검증 API 인가")
class EstimateValidationSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private EstimateValidationService validationService;
    @MockitoBean
    private EstimateFileValidationService fileValidationService;
    @MockitoBean
    private CostComparisonService costComparisonService;
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    /* SecurityConfig 가 요구하는 소셜 로그인 빈. 없으면 컨텍스트가 뜨지 않는다. */
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean
    private OAuth2SuccessHandler oAuth2SuccessHandler;
    @MockitoBean
    private OAuth2FailureHandler oAuth2FailureHandler;
    @MockitoBean
    private RestAccessDeniedHandler restAccessDeniedHandler;

    @Test
    @DisplayName("POST /api/estimate-validations (JSON) — 비로그인 요청은 401이다")
    void unauthenticatedManualRegisterIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/estimate-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accidentId":1,"fileType":"MANUAL","items":[
                                  {"lineNo":1,"rawItemName":"프론트 펜더","workType":"판금",
                                   "quantity":1,"partCost":120000,"laborCost":30000}]}
                                """))
                .andExpect(status().isUnauthorized());

        then(validationService).should(never()).registerManual(any(), any());
    }

    @Test
    @DisplayName("POST /api/estimate-validations (multipart) — 비로그인 요청은 401이다")
    void unauthenticatedFileRegisterIsUnauthorized() throws Exception {
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata", "metadata", MediaType.APPLICATION_JSON_VALUE,
                "{\"accidentId\":1}".getBytes());
        MockMultipartFile file = new MockMultipartFile(
                "file", "estimate.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4".getBytes());

        mockMvc.perform(multipart("/api/estimate-validations").file(metadata).file(file))
                .andExpect(status().isUnauthorized());

        then(fileValidationService).should(never()).registerFile(any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/estimate-validations/{id} — 비로그인 요청은 401이다")
    void unauthenticatedStatusIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/estimate-validations/1"))
                .andExpect(status().isUnauthorized());

        then(validationService).should(never()).status(any(), any());
    }

    @Test
    @DisplayName("GET /api/estimate-validations/{id}/result — 비로그인 요청은 401이다")
    void unauthenticatedResultIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/estimate-validations/1/result"))
                .andExpect(status().isUnauthorized());

        then(validationService).should(never()).result(any(), any());
    }

    @Test
    @DisplayName("GET /api/estimate-validations/{id}/questions — 비로그인 요청은 401이다")
    void unauthenticatedQuestionsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/estimate-validations/1/questions"))
                .andExpect(status().isUnauthorized());

        then(validationService).should(never()).questions(any(), any());
    }

    @Test
    @DisplayName("GET /api/estimate-validations/me — 비로그인 요청은 401이다")
    void unauthenticatedHistoryIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/estimate-validations/me"))
                .andExpect(status().isUnauthorized());

        then(validationService).should(never()).history(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("GET /api/estimate-validations/{id}/pdf — 비로그인 요청은 401이다")
    void unauthenticatedPdfIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/estimate-validations/1/pdf"))
                .andExpect(status().isUnauthorized());

        then(fileValidationService).should(never()).pdfDownload(any(), any());
    }

    @Test
    @DisplayName("DELETE /api/estimate-validations/{id} — 비로그인 요청은 401이다")
    void unauthenticatedDeleteIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/estimate-validations/1"))
                .andExpect(status().isUnauthorized());

        then(fileValidationService).should(never()).delete(any(), any());
    }

    @Test
    @DisplayName("GET /api/accidents/{id}/cost-comparison — 비로그인 요청은 401이다")
    void unauthenticatedCostComparisonIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/accidents/1/cost-comparison"))
                .andExpect(status().isUnauthorized());

        then(costComparisonService).should(never()).compare(any(), any());
    }

    @Test
    @DisplayName("비로그인 요청은 CurrentMemberProvider 에 닿기 전에 끊긴다")
    void filterChainStopsBeforeMemberLookup() throws Exception {
        mockMvc.perform(get("/api/estimate-validations/me"))
                .andExpect(status().isUnauthorized());

        then(currentMemberProvider).should(never()).currentMemberId();
    }
}
