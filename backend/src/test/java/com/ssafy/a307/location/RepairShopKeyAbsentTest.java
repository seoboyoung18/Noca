package com.ssafy.a307.location;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카카오 키가 없는 실제 테스트 프로파일에서 정비소 API 만 503 인가.
 *
 * <p>{@code LocationApiTest} 와 설정이 같아 같은 컨텍스트(포트 빈 없음)를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("주변 정비소 API — 키 미설정")
class RepairShopKeyAbsentTest {

    private static final long USER_ID = 92_302L;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Authentication user;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'repair-shop-nokey', '정비소키없음', 'USER', 'ACTIVE')
                """, USER_ID);
        Member member = memberRepository.findById(USER_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(member);
        user = new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from member where member_id = ?", USER_ID);
    }

    @Test
    @DisplayName("키가 없으면 정비소 API 만 503 이고 다른 API 는 살아 있다")
    void onlyRepairShopApiIsUnavailable() throws Exception {
        mockMvc.perform(get("/api/repair-shops").param("latitude", "37.5665").param("longitude", "126.9780")
                        .with(authentication(user)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));

        mockMvc.perform(get("/api/vehicle-models").with(authentication(user)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("검증은 키 확인보다 앞이다 — 좌표가 없으면 키가 없어도 400")
    void validationComesFirst() throws Exception {
        mockMvc.perform(get("/api/repair-shops").with(authentication(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
