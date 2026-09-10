package com.ssafy.a307.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프리플라이트를 실제로 보낸다. {@link CorsPropertiesTest} 가 값 검증을 본다면 여기는
 * <b>그 값이 실제 응답 헤더로 이어지는지</b> 를 본다.
 *
 * <p>설정한 오리진만 통과하고 나머지는 막히는 것, 그리고 {@code Allow-Credentials} 가
 * 유지되는 것 — 이 둘이 깨지면 배포 후 전 화면이 죽는다.
 *
 * <p>{@code ChecklistPublicAccessTest} 와 같은 이유로 Redis 세션 자동설정을 뺀다.
 * CORS 필터는 시큐리티 체인 앞이라 세션 저장소와 무관하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
        "app.cors.allowed-origins=https://a307.example.com,http://localhost:5173"
})
@DisplayName("CORS 프리플라이트")
class CorsPreflightTest {

    private static final String ALLOWED = "https://a307.example.com";
    private static final String ALSO_ALLOWED = "http://localhost:5173";
    private static final String NOT_ALLOWED = "https://evil.example.com";

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("설정한 오리진은 통과하고 자격증명 허용이 함께 나간다")
    void allowsConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/members/me")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                // 세션 ID 를 쿠키로 주고받으므로 이게 빠지면 모든 보호 API 가 401 이 된다.
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    @DisplayName("목록의 두 번째 오리진도 통과한다 — 로컬과 배포를 동시에 열 수 있다")
    void allowsSecondConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/members/me")
                        .header("Origin", ALSO_ALLOWED)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALSO_ALLOWED));
    }

    @Test
    @DisplayName("설정하지 않은 오리진은 막는다")
    void rejectsUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/members/me")
                        .header("Origin", NOT_ALLOWED)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("공개 경로도 같은 규칙을 받는다 — CORS 는 인가보다 앞이다")
    void appliesToPublicPathsToo() throws Exception {
        mockMvc.perform(options("/api/guides/checklist")
                        .header("Origin", NOT_ALLOWED)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("명세에 있는 메서드가 모두 허용된다")
    void allowsDeclaredMethods() throws Exception {
        for (String method : new String[]{"GET", "POST", "PUT", "PATCH", "DELETE"}) {
            mockMvc.perform(options("/api/members/me")
                            .header("Origin", ALLOWED)
                            .header("Access-Control-Request-Method", method))
                    .andExpect(status().isOk());
        }
    }
}
