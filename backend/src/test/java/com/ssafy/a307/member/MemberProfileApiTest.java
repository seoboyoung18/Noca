package com.ssafy.a307.member;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.MemberStatus;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마이페이지 3종({@code /api/members/me})을 필터 체인을 켠 채로 검증한다.
 * <p>
 * 로그인 세션은 실제 가입 흐름으로 만든다 — {@code POST /api/auth/signup} 이 가입 대기
 * 세션을 로그인 세션으로 승격시키므로, 세션을 손으로 조립하는 것보다 실제와 가깝다.
 *
 * <p>차량·사고는 SQL 로 직접 넣는다. {@code VehicleModel} 에 공개 생성자가 없어
 * 엔티티로는 만들 수 없고, 건수 정책(폐차 차량 제외 / 그 차량의 사고는 포함)은
 * 실제 행이 있어야 검증되기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("마이페이지 — 내 정보·닉네임 수정·탈퇴")
class MemberProfileApiTest {

    private static final String KAKAO_ID = "3899112233";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("내 정보를 조회하면 프로필과 건수가 함께 온다")
    void returnsProfileWithCounts() throws Exception {
        MockHttpSession session = signedInSession("보영");

        mockMvc.perform(get("/api/members/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("보영"))
                .andExpect(jsonPath("$.data.provider").value("KAKAO"))
                .andExpect(jsonPath("$.data.vehicleCount").value(0))
                .andExpect(jsonPath("$.data.accidentCount").value(0))
                // 필드는 있고 값만 null 이어야 한다. 아예 빠지면 FE 가 옵셔널 체이닝 없이
                // 접근했을 때와 동작이 달라진다 — doesNotExist() 는 둘을 구분하지 못한다
                .andExpect(jsonPath("$.data.email").value(nullValue()))
                .andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));
    }

    @Test
    @DisplayName("등록 차량 수는 폐차·매각한 차량을 빼고, 사고 건수는 그 차량 것도 센다")
    void countsFollowEachDomainsDeletionPolicy() throws Exception {
        MockHttpSession session = signedInSession("보영");
        long memberId = currentMemberId();

        long modelId = insertVehicleModel();
        long activeVehicle = insertVehicle(memberId, modelId, false);
        long soldVehicle = insertVehicle(memberId, modelId, true);

        insertAccident(activeVehicle, modelId);
        insertAccident(soldVehicle, modelId);
        insertAccident(soldVehicle, modelId);

        mockMvc.perform(get("/api/members/me").session(session))
                .andExpect(status().isOk())
                // 폐차한 차량은 목록에서 빠지므로 숫자에서도 빠진다
                .andExpect(jsonPath("$.data.vehicleCount").value(1))
                // 사고 이력은 폐차해도 남는다 — 목록과 숫자가 어긋나면 안 된다
                .andExpect(jsonPath("$.data.accidentCount").value(3));
    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/members/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("가입 대기 세션으로는 403 SIGNUP_REQUIRED 다")
    void rejectsPendingSignup() throws Exception {
        mockMvc.perform(get("/api/members/me").session(pendingSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_REQUIRED"));
    }

    @Test
    @DisplayName("닉네임을 바꾸면 갱신된 프로필이 그대로 돌아온다")
    void changesNickname() throws Exception {
        MockHttpSession session = signedInSession("보영");

        mockMvc.perform(patch("/api/members/me")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"바뀐닉네임"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("바뀐닉네임"))
                // 조회와 같은 모양이라 프론트가 다시 조회하지 않아도 된다
                .andExpect(jsonPath("$.data.vehicleCount").value(0));

        assertThat(memberRepository.findById(currentMemberId()))
                .get()
                .extracting(Member::getNickname)
                .isEqualTo("바뀐닉네임");
    }

    @Test
    @DisplayName("닉네임 앞뒤 공백은 지우고 저장한다")
    void stripsSurroundingWhitespace() throws Exception {
        MockHttpSession session = signedInSession("보영");

        mockMvc.perform(patch("/api/members/me")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"  서보영  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("서보영"));
    }

    @Test
    @DisplayName("공백을 지우고 나서 2자 미만이면 거절한다")
    void rejectsTooShortNicknameAfterStripping() throws Exception {
        MockHttpSession session = signedInSession("보영");

        // 원문은 5자라 @Size 는 통과한다. 공백을 지운 뒤에야 규칙에 걸린다
        mockMvc.perform(patch("/api/members/me")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"  영  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /** 어떤 단어가 걸렸는지는 알려주지 않는다 — 목록을 역추적해 우회법을 학습시킨다. */
    @Test
    @DisplayName("금칙어가 든 닉네임은 거절한다 — 걸린 단어는 알려주지 않는다")
    void rejectsForbiddenNickname() throws Exception {
        MockHttpSession session = signedInSession("보영");

        mockMvc.perform(patch("/api/members/me")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"바른견적운영팀"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("사용할 수 없는 닉네임입니다."));
    }

    @Test
    @DisplayName("닉네임이 12자를 넘으면 거절한다")
    void rejectsTooLongNickname() throws Exception {
        MockHttpSession session = signedInSession("보영");

        mockMvc.perform(patch("/api/members/me")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"가나다라마바사아자차카타파"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /**
     * 테스트 설정은 버킷을 비워 둬서 S3 어댑터 빈이 만들어지지 않는다. 저장소가 없을 때
     * 500 이 아니라 503 이 나가야 "설정이 빠졌다"는 게 드러난다.
     */
    @Test
    @DisplayName("저장소가 구성되지 않으면 이미지 업로드는 503 이다")
    void profileImageNeedsStorage() throws Exception {
        mockMvc.perform(post("/api/members/me/profile-image/upload-url")
                        .session(signedInSession("보영"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","size":1024}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("탈퇴하면 204 이고 같은 세션으로는 더 이상 조회되지 않는다")
    void withdrawEndsTheSession() throws Exception {
        MockHttpSession session = signedInSession("보영");
        long memberId = currentMemberId();

        mockMvc.perform(delete("/api/members/me").session(session))
                .andExpect(status().isNoContent());

        // 세션을 끊지 않으면 탈퇴한 계정의 세션이 그대로 살아 있다
        mockMvc.perform(get("/api/members/me").session(session))
                .andExpect(status().isUnauthorized());

        assertThat(memberRepository.findById(memberId))
                .get()
                .extracting(Member::getStatus)
                .isEqualTo(MemberStatus.WITHDRAWN);
    }

    /** 소셜 인증 → 가입까지 마친 세션. {@code POST /api/auth/signup} 이 세션을 승격시킨다. */
    private MockHttpSession signedInSession(String nickname) throws Exception {
        MockHttpSession session = pendingSession();

        mockMvc.perform(post("/api/auth/signup")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"%s","agreedTerms":["SERVICE","PRIVACY"]}
                                """.formatted(nickname)))
                .andExpect(status().isCreated());

        return session;
    }

    private MockHttpSession pendingSession() {
        UserPrincipal principal =
                UserPrincipal.ofPendingSignup(Provider.KAKAO, KAKAO_ID, "카카오닉네임");
        Authentication authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), Provider.KAKAO.registrationId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private long currentMemberId() {
        return memberRepository.findByProviderAndProviderUserId(Provider.KAKAO, KAKAO_ID)
                .orElseThrow()
                .getMemberId();
    }

    private long insertVehicleModel() {
        jdbcTemplate.update("""
                insert into vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)
                values ('현대', '아반떼', 'SEDAN', 'Compact', true)
                """);
        return jdbcTemplate.queryForObject(
                "select model_id from vehicle_model where model_name = '아반떼'", Long.class);
    }

    private long insertVehicle(long memberId, long modelId, boolean sold) {
        jdbcTemplate.update("""
                insert into vehicle (member_id, model_id, model_year, deleted_at)
                values (?, ?, 2020, ?)
                """, memberId, modelId, sold ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        // 회원으로 범위를 좁힌다. 이 회원은 테스트마다 새로 만들어지므로 여기 걸리는 행은
        // 이 테스트가 넣은 것뿐이다
        return jdbcTemplate.queryForObject(
                "select max(vehicle_id) from vehicle where member_id = ?", Long.class, memberId);
    }

    private void insertAccident(long vehicleId, long modelId) {
        jdbcTemplate.update("""
                insert into accident (vehicle_id, vehicle_input_type, snapshot_model_id,
                    snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                    snapshot_car_class, snapshot_model_year)
                values (?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Compact', 2020)
                """, vehicleId, modelId);
    }
}
