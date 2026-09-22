package com.ssafy.a307.analysis;

import com.ssafy.a307.analysis.controller.AnalysisProgressController;
import com.ssafy.a307.analysis.dto.AnalysisProgressResponse;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.analysis.entity.AnalysisStageStatus;
import com.ssafy.a307.analysis.entity.AnalysisStageType;
import com.ssafy.a307.analysis.service.AnalysisProgressService;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisProgressController.class)
@Import(SecurityConfig.class)
@DisplayName("AnalysisProgressController")
class AnalysisProgressControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private AnalysisProgressService analysisProgressService;
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    /* SecurityConfig 가 소셜 로그인 연동으로 요구하는 빈들. AccidentSecurityTest 와 같은 이유다. */
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
        mockMvc.perform(get("/api/accidents/1/analysis"))
                .andExpect(status().isUnauthorized());

        then(analysisProgressService).should(never()).progress(any(), any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("단계와 현재 단계를 코드로 준다 — 한글 라벨을 내려보내지 않는다")
    void returnsStageCodes() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisProgressService.progress(1L, 7L)).willReturn(new AnalysisProgressResponse(
                42L, AnalysisJobStatus.PROCESSING, null, 0,
                Instant.parse("2026-09-05T01:00:00Z"), null,
                4, 2, AnalysisStageType.MATCH,
                List.of(new AnalysisProgressResponse.StageProgress(
                        AnalysisStageType.MATCH, AnalysisStageStatus.RUNNING, null,
                        Instant.parse("2026-09-05T01:00:10Z"), null)),
                List.of(), false));

        mockMvc.perform(get("/api/accidents/7/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(42))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.totalStages").value(4))
                .andExpect(jsonPath("$.data.doneStages").value(2))
                .andExpect(jsonPath("$.data.currentStage").value("MATCH"))
                .andExpect(jsonPath("$.data.stages[0].stage").value("MATCH"))
                .andExpect(jsonPath("$.data.stages[0].status").value("RUNNING"))
                // 진행률 퍼센트·남은 시간은 계약에 없다. 생기면 여기서 먼저 깨진다.
                .andExpect(jsonPath("$.data.progressPercent").doesNotExist())
                .andExpect(jsonPath("$.data.remainingSeconds").doesNotExist())
                .andExpect(jsonPath("$.data.elapsedSeconds").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("분석 전 사고는 빈 상태 200 이다 — 404 가 아니다")
    void notRequestedIsOk() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisProgressService.progress(1L, 7L))
                .willReturn(AnalysisProgressResponse.notRequested());

        mockMvc.perform(get("/api/accidents/7/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").doesNotExist())
                .andExpect(jsonPath("$.data.stages").isEmpty())
                .andExpect(jsonPath("$.data.doneStages").value(0))
                .andExpect(jsonPath("$.data.totalStages").value(4));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("남의 사고·없는 사고는 404 다")
    void notOwnedIsNotFound() throws Exception {
        given(currentMemberProvider.currentMemberId()).willReturn(1L);
        given(analysisProgressService.progress(1L, 7L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));

        mockMvc.perform(get("/api/accidents/7/analysis"))
                .andExpect(status().isNotFound());
    }
}
