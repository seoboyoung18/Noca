package com.ssafy.a307.admin;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 API 인가. <b>시큐리티 필터를 켠 채로</b> 본다.
 *
 * <p>{@code addFilters = false} 로 컨트롤러만 부르면 "관리자만 들어온다" 를 증명할 수 없다 —
 * 그 설정은 인가를 통째로 빼므로 테스트는 초록인데 실제로는 아무나 마스터 코드를 고칠 수 있다.
 * {@code AccidentImageSecurityTest} 와 같은 방식이고, Redis 세션 자동설정을 빼는 이유도 같다.
 *
 * <p><b>엔드포인트를 하나씩 나열한다.</b> {@code /api/admin/**} 패턴 하나만 믿으면 새 관리자
 * API 가 다른 접두사로 생겼을 때 구멍을 놓친다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("관리자 API 인가 (시큐리티 필터 켬)")
class AdminSecurityTest {

    private static final long ADMIN_ID = 95_001L;
    private static final long USER_ID = 95_002L;

    private static final List<String> ADMIN_ENDPOINTS = List.of(
            "/api/admin/vehicle-models",
            "/api/admin/part-codes",
            "/api/admin/part-name-mappings",
            "/api/admin/repair-codes",
            "/api/admin/repair-method-rules",
            "/api/admin/estimate-validation-rules/current",
            "/api/admin/estimate-validation-rules/history",
            "/api/admin/rules/overview",
            "/api/admin/rules/history",
            "/api/admin/audit-logs");

    static Stream<String> endpoints() {
        return ADMIN_ENDPOINTS.stream();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedMembers() {
        insertMember(ADMIN_ID, "ADMIN");
        insertMember(USER_ID, "USER");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from member where member_id in (?, ?)", ADMIN_ID, USER_ID);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("비로그인은 401 이다")
    void anonymousIsUnauthorized(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("일반 USER 는 403 이고 공통 오류 봉투로 나간다")
    void normalUserIsForbidden(String path) throws Exception {
        mockMvc.perform(get(path).with(authentication(principal(USER_ID))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("ADMIN 은 통과한다")
    void adminPasses(String path) throws Exception {
        mockMvc.perform(get(path).with(authentication(principal(ADMIN_ID))))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("가입 대기 세션은 403 SIGNUP_REQUIRED 다 — 소셜 인증만 끝난 상태로 마스터를 볼 수 없다")
    void pendingSignupIsForbidden(String path) throws Exception {
        mockMvc.perform(get(path).with(authentication(pendingSignup())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_REQUIRED"));
    }

    @Test
    @DisplayName("쓰기 경로도 USER 를 막는다 — 읽기만 확인하면 변경 구멍을 놓친다")
    void writeIsForbiddenForUser() throws Exception {
        mockMvc.perform(post("/api/admin/vehicle-models")
                        .with(authentication(principal(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "manufacturer": "기아", "modelName": "테스트",
                                  "vehicleType": "SEDAN", "carClass": "Mid-size" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("요청 본문의 가짜 actor 필드는 무시된다 — 받는 필드 자체가 없다")
    void bodyCannotForgeActor() throws Exception {
        mockMvc.perform(post("/api/admin/vehicle-models")
                        .with(authentication(principal(ADMIN_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "manufacturer": "위조테스트", "modelName": "가짜actor",
                                  "vehicleType": "SEDAN", "carClass": "Mid-size",
                                  "actorMemberId": 99999, "changedBy": 99999 }
                                """))
                .andExpect(status().isCreated());

        // 이력의 행위자는 세션에서 온 값이어야 한다. 본문에 적은 99999 가 아니다.
        Long actor = jdbcTemplate.queryForObject("""
                select actor_member_id from audit_log
                where target_type = 'VEHICLE_MODEL' order by audit_log_id desc limit 1
                """, Long.class);
        assertThat(actor).isEqualTo(ADMIN_ID);

        jdbcTemplate.update("delete from audit_log where actor_member_id = ?", ADMIN_ID);
        jdbcTemplate.update("delete from vehicle_model where manufacturer = '위조테스트'");
    }

    @Test
    @DisplayName("확인 대상 엔드포인트가 10개다 — 새 관리자 API 를 추가하면 여기도 늘려야 한다")
    void endpointListIsComplete() {
        assertThat(ADMIN_ENDPOINTS).hasSize(10);
    }

    /**
     * 소셜 인증은 끝났지만 아직 회원이 아닌 세션. 권한이 {@code ROLE_SIGNUP_PENDING} 하나뿐이라
     * {@code hasRole("ADMIN")} 을 통과할 수 없고, {@code RestAccessDeniedHandler} 가
     * {@code SIGNUP_REQUIRED} 로 내보낸다. DB 에 member 행이 없는 상태라는 점이 핵심이다.
     */
    private Authentication pendingSignup() {
        UserPrincipal pending = UserPrincipal.ofPendingSignup(
                Provider.KAKAO, "sec-pending", "가입대기자");
        return new UsernamePasswordAuthenticationToken(pending, "n/a", pending.getAuthorities());
    }

    private Authentication principal(long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow();
        UserPrincipal userPrincipal = UserPrincipal.ofMember(member);
        return new UsernamePasswordAuthenticationToken(
                userPrincipal, "n/a", userPrincipal.getAuthorities());
    }

    private void insertMember(long memberId, String role) {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', ?, ?, ?, 'ACTIVE')
                """, memberId, "sec-" + memberId, "관리자테스트" + memberId, role);
    }
}
