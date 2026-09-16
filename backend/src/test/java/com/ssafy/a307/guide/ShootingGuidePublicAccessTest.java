package com.ssafy.a307.guide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>비인증 접근을 실제로 검증한다.</b> {@code addFilters = false} 를 쓰지 않는다 —
 * 필터를 빼 버리면 "인증 없이 통과했다" 를 증명하지 못한다.
 *
 * <p>대조군(인증 필요 API 가 401 인지)은 {@code ChecklistPublicAccessTest} 가 확인한다.
 *
 * <p>Redis 세션 자동설정을 뺀 이유도 그 클래스에 적어 두었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("촬영 가이드 API 비인증 접근 (시큐리티 필터 켬)")
class ShootingGuidePublicAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/guides/shooting — 인증 없이 200 이고 권장 1장이 나간다")
    void shootingGuideIsPublic() throws Exception {
        mockMvc.perform(get("/api/guides/shooting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendedCount").value(1))
                .andExpect(jsonPath("$.data.shots.length()").value(1))
                .andExpect(jsonPath("$.data.shots[0].angleCode").value("DAMAGE_CLOSE"))
                // 가이드는 1컷이어도 받아 주는 어휘는 9종 그대로다
                .andExpect(jsonPath("$.data.acceptedAngleCodes.length()").value(9));
    }
}
