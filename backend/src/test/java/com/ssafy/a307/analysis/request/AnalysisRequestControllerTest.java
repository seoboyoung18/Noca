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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisRequestController.class)
@Import(SecurityConfig.class)
@DisplayName("AnalysisRequestController (S15P21A307-156)")
class AnalysisRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private AnalysisRequestService analysisRequestService;
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    /* SecurityConfig 가 소셜 로그인 연동으로 요구하는 빈들. AnalysisProgressControllerTest 와 같다. */
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean
    private OAuth2SuccessHandler oAuth2SuccessHandler;
    @MockitoBean
    private OAuth2FailureHandler oAuth2FailureHandler;
    @MockitoBean
    private RestAccessDeniedHandler restAccessDeniedHandler;

    @Test
    @DisplayName("비로그인 요청은 401이고 서비스까지 가지 않는다")
    void unauthenticatedIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/accidents/7/analysis"))
                .andExpect(status().isUnauthorized());

        then(analysisRequestService).should(never()).request(any(), any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("접수되면 202 이고, 진행 상태 조회와 같은 모양으로 QUEUED 를 준다")
    void acceptedReturnsQueuedProgress() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisRequestService.request(1L, 7L)).willReturn(new AnalysisProgressResponse(
                42L, AnalysisJobStatus.QUEUED, null, 0, null, null, 4, 0, null, List.of(), List.of(), false));

        mockMvc.perform(post("/api/accidents/7/analysis"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(42))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.totalStages").value(4))
                // 예상 소요 시간은 근거(S15P21A307-159)가 없어 주지 않는다. 생기면 여기서 먼저 깨진다.
                .andExpect(jsonPath("$.data.estimatedSeconds").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("이미 작업이 있으면 409 다")
    void existingJobIsConflict() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisRequestService.request(1L, 7L))
                .willThrow(new BusinessException(ErrorCode.CONFLICT, "이미 분석이 진행 중입니다."));

        mockMvc.perform(post("/api/accidents/7/analysis"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("남의 사고·없는 사고는 404 다")
    void notOwnedIsNotFound() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisRequestService.request(1L, 7L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));

        mockMvc.perform(post("/api/accidents/7/analysis"))
                .andExpect(status().isNotFound());
    }

    // ── 재시도 (S15P21A307-161) ─────────────────────────────────────────────

    @Test
    @DisplayName("재시도도 비로그인이면 401 이고 서비스까지 가지 않는다")
    void retryUnauthenticatedIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/accidents/7/analysis/retry"))
                .andExpect(status().isUnauthorized());

        then(analysisRequestService).should(never()).retry(any(), any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("재시도가 접수되면 202 이고, 새 작업의 진행 상태를 재시도 횟수와 함께 준다")
    void retryAcceptedReturnsQueuedProgress() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisRequestService.retry(1L, 7L)).willReturn(new AnalysisProgressResponse(
                43L, AnalysisJobStatus.QUEUED, null, 1, null, null, 4, 0, null, List.of(), List.of(), false));

        mockMvc.perform(post("/api/accidents/7/analysis/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(43))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.retryCount").value(1));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("재시도할 수 없는 상태면 409 다")
    void retryConflict() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisRequestService.retry(1L, 7L))
                .willThrow(new BusinessException(ErrorCode.CONFLICT, "재시도 횟수를 모두 사용했습니다."));

        mockMvc.perform(post("/api/accidents/7/analysis/retry"))
                .andExpect(status().isConflict());
    }
}
