package com.ssafy.a307.guide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>비인증 접근을 실제로 검증한다.</b>
 *
 * <p>{@code addFilters = false} 를 쓰지 않는다. 그것은 시큐리티 필터를 아예 빼 버리는 것이라
 * "인증 없이 통과했다" 를 증명하지 못한다. 여기서는 필터 체인을 켠 채로 인증 정보 없이 호출한다.
 *
 * <p>대조군으로 인증이 필요한 API 를 같이 호출해, 필터가 실제로 동작 중임을 보인다.
 * 체크리스트가 200 인 것이 "필터가 꺼져 있어서" 가 아니라 "permitAll 이라서" 임을 이 대조가 보증한다.
 *
 * <p><b>Redis 세션 자동설정을 뺀 이유</b> — 기본값(Redis)으로 두면
 * 401 을 만드는 과정에서 시큐리티가 요청을 저장하려고 세션을 만들고, Redis 가 떠 있지 않은
 * 환경에서 {@code RedisConnectionFailureException} 으로 죽는다(실측함).
 * 세션 <b>저장소</b>만 바꾼 것이라 필터 체인과 인가 규칙은 그대로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("체크리스트 API 비인증 접근 (시큐리티 필터 켬)")
class ChecklistPublicAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/guides/checklist — 인증 없이 200 이고 문안 12항목이 그대로 나간다")
    void checklistIsPublic() throws Exception {
        mockMvc.perform(get("/api/guides/checklist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps.length()").value(5))
                .andExpect(jsonPath("$.data.steps[0].title").value("안전 확보"))
                .andExpect(jsonPath("$.data.steps[0].items[0]").value("비상등을 켰습니다"))
                .andExpect(jsonPath("$.data.steps[4].title").value("보험사 접수"));
    }

    /**
     * 대조군. 이 테스트가 401 을 받아야만 위의 200 이 의미를 갖는다.
     *
     * <p>덤으로 401 <b>본문</b>이 공통 규약을 지키는지 관찰한다.
     * {@code SecurityConfig} 의 진입점이 {@code res.sendError(401)} 이라
     * {@code GlobalExceptionHandler} 를 거치지 않는다 — 즉
     * {@code { "error": { "code": "UNAUTHORIZED" } }} 가 나오지 않는다.
     * <b>이 Task 에서 고치지 않는다.</b> 인증 담당에게 전달할 관찰 결과다.
     */
    @Test
    @DisplayName("대조군 — 인증 필요 API 는 401 이고, 그 본문은 공통 에러 규약을 지키지 않는다")
    void authenticatedApiIsStillBlocked() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/vehicles/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // 공통 규약이라면 { "error": { "code": "UNAUTHORIZED", ... } } 여야 한다. 아니다.
        assertThat(body).doesNotContain("UNAUTHORIZED");
        // 실측: MockMvc 기준 본문은 비어 있고 errorMessage 도 null 이다.
        assertThat(body).isEmpty();
        assertThat(result.getResponse().getErrorMessage()).isNull();
    }
}
