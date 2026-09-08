package com.ssafy.a307.accident.controller;

import com.ssafy.a307.accident.service.AccidentService;
import com.ssafy.a307.auth.handler.OAuth2FailureHandler;
import com.ssafy.a307.auth.handler.OAuth2SuccessHandler;
import com.ssafy.a307.auth.handler.RestAccessDeniedHandler;
import com.ssafy.a307.auth.service.CustomOAuth2UserService;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccidentController.class)
@Import(SecurityConfig.class)
@DisplayName("AccidentController 인증")
class AccidentSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private AccidentService accidentService;
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    /*
     * SecurityConfig 가 소셜 로그인 연동으로 이 네 빈을 요구한다. @WebMvcTest 슬라이스에는
     * 그 빈들이 없으므로 대역으로 넣어 준다 — 없으면 컨텍스트가 뜨지 않아 401 단언에 닿지 못한다.
     * 인가 규칙만 보는 테스트라 동작하는 구현이 필요하지 않다.
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
    @DisplayName("POST /api/accidents — 비로그인 요청은 401이다")
    void unauthenticatedRequestIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/accidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"vehicleId\": 7 }"))
                .andExpect(status().isUnauthorized());

        then(accidentService).should(never()).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
