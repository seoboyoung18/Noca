package com.ssafy.a307.analysis.request;

import com.ssafy.a307.analysis.dto.AnalysisProgressResponse;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.auth.handler.OAuth2FailureHandler;
import com.ssafy.a307.auth.handler.OAuth2SuccessHandler;
import com.ssafy.a307.auth.handler.RestAccessDeniedHandler;
import com.ssafy.a307.auth.service.CustomOAuth2UserService;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 부위 확정 재분석 API 계약 (S15P21A307-570). 판정은 서비스가 하고
 * {@code AnalysisRequestServiceTest} 가 본다 — 여기서는 경로·인증·본문 검증·상태 코드만 본다.
 */
@WebMvcTest(ResolvePartController.class)
@Import(SecurityConfig.class)
@DisplayName("ResolvePartController (S15P21A307-570)")
class ResolvePartControllerTest {

    private static final String BODY = "{\"partCode\":\"FRONT_FENDER_L\"}";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private AnalysisRequestService analysisRequestService;
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    /* SecurityConfig 가 소셜 로그인 연동으로 요구하는 빈들. AnalysisRequestControllerTest 와 같다. */
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean
    private OAuth2SuccessHandler oAuth2SuccessHandler;
    @MockitoBean
    private OAuth2FailureHandler oAuth2FailureHandler;
    @MockitoBean
    private RestAccessDeniedHandler restAccessDeniedHandler;

    @Test
    @DisplayName("비로그인 요청은 401 이고 서비스까지 가지 않는다")
    void unauthenticatedIsUnauthorized() throws Exception {
        mockMvc.perform(resolvePart(BODY))
                .andExpect(status().isUnauthorized());

        then(analysisRequestService).should(never()).resolvePart(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("접수되면 202 이고, 새 작업의 진행 상태를 재시도 횟수와 함께 준다")
    void acceptedReturnsQueuedProgress() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisRequestService.resolvePart(1L, 30L, "FRONT_FENDER_L")).willReturn(new AnalysisProgressResponse(
                44L, AnalysisJobStatus.QUEUED, null, 1, null, null, 4, 0, null, List.of(), List.of(), false));

        mockMvc.perform(resolvePart(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(44))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.retryCount").value(1));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("부위가 비었거나 없으면 400 이고 서비스까지 가지 않는다")
    void blankPartCodeIsBadRequest() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);

        mockMvc.perform(resolvePart("{\"partCode\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(resolvePart("{}"))
                .andExpect(status().isBadRequest());

        then(analysisRequestService).should(never()).resolvePart(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("다시 분석할 수 없는 견적이면 409 다")
    void notResolvableIsConflict() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisRequestService.resolvePart(1L, 30L, "FRONT_FENDER_L"))
                .willThrow(new BusinessException(ErrorCode.CONFLICT, "부위를 고를 수 있는 견적이 아닙니다."));

        mockMvc.perform(resolvePart(BODY))
                .andExpect(status().isConflict());
    }

    private static MockHttpServletRequestBuilder resolvePart(String body) {
        return post("/api/estimates/30/resolve-part")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
