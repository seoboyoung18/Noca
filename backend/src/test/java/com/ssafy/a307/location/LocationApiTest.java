package com.ssafy.a307.location;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.kakao.KakaoLocalPort;
import com.ssafy.a307.location.domain.CategoryGroupCode;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 위치 API 의 HTTP 계약·인가·파라미터 검증.
 *
 * <h2>이 테스트는 카카오를 부르지 않는다 — 그리고 그것이 요점이다</h2>
 * 테스트 프로파일은 {@code app.kakao.local.rest-api-key} 를 비워 두므로
 * {@code KakaoKeyPresentCondition} 이 걸려 <b>{@code KakaoLocalPort} 빈이 뜨지 않는다.</b>
 * 그래서 여기서 검증하는 것은 두 가지다.
 *
 * <ol>
 *   <li><b>키가 없어도 컨텍스트가 정상 기동하고</b> 위치 API 만 503 을 낸다 —
 *       지도 키가 없다고 차량·사고 API 까지 죽으면 안 된다</li>
 *   <li><b>파라미터 검증이 카카오 호출 전에 끝난다</b> — 잘못된 요청은 포트가 없어도
 *       400 이 나온다. 503 이 나오면 검증이 카카오 호출 뒤에 있다는 뜻이고, 그러면
 *       쿼터를 태운 뒤에 거절하는 것이 된다</li>
 * </ol>
 *
 * <p>실제 응답 해석은 {@code KakaoLocalClientTest} 가 {@code MockRestServiceServer} 로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("위치 API")
class LocationApiTest {

    private static final long USER_ID = 92_101L;
    private static final String GEOCODE = "/api/locations/geocode";
    private static final String REVERSE = "/api/locations/reverse-geocode";
    private static final String PLACES = "/api/locations/places";
    private static final String CATEGORY = "/api/locations/places/category";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Optional<KakaoLocalPort> kakaoLocalPort;

    private Authentication user;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'loc-user', '위치사용자', 'USER', 'ACTIVE')
                """, USER_ID);
        Member member = memberRepository.findById(USER_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(member);
        user = new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from member where member_id = ?", USER_ID);
    }

    // ─────────────────────────────────────────────── 키 미설정 기동

    @Nested
    @DisplayName("키 미설정")
    class KeyAbsent {

        @Test
        @DisplayName("컨텍스트가 정상 기동하고 전송 계층 빈만 뜨지 않는다")
        void contextStartsWithoutKey() {
            assertThat(kakaoLocalPort)
                    .as("키가 없으면 KakaoKeyPresentCondition 이 빈 생성을 막는다")
                    .isEmpty();
        }

        @Test
        @DisplayName("위치 API 만 503 이고 다른 API 는 영향받지 않는다")
        void onlyLocationApiIsUnavailable() throws Exception {
            mockMvc.perform(get(GEOCODE).param("query", "서울시청").with(authentication(user)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));

            // 같은 컨텍스트에서 다른 도메인 API 는 살아 있다.
            mockMvc.perform(get("/api/vehicle-models").with(authentication(user)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("503 메시지에 키가 없다")
        void unavailableMessageHasNoKey() throws Exception {
            String body = mockMvc.perform(
                            get(GEOCODE).param("query", "서울시청").with(authentication(user)))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("KakaoAK").doesNotContain("rest-api-key");
        }
    }

    // ─────────────────────────────────────────────── 인가

    @Nested
    @DisplayName("인가 — 쿼터가 걸린 외부 자원을 비인증에 열지 않는다")
    class Authorization {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {GEOCODE, REVERSE, PLACES, CATEGORY,
                "/api/locations/category-groups"})
        @DisplayName("비인증은 401 이다")
        void anonymousIsUnauthorized(String path) throws Exception {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {GEOCODE, PLACES, "/api/locations/category-groups"})
        @DisplayName("가입 대기 세션은 403 SIGNUP_REQUIRED 다 — 소셜 인증만 끝난 상태로 쿼터를 태울 수 없다")
        void pendingSignupIsForbidden(String path) throws Exception {
            UserPrincipal pending = UserPrincipal.ofPendingSignup(
                    com.ssafy.a307.member.entity.Provider.KAKAO, "loc-pending", "가입대기자");
            Authentication session = new UsernamePasswordAuthenticationToken(
                    pending, "n/a", pending.getAuthorities());

            mockMvc.perform(get(path).with(authentication(session)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SIGNUP_REQUIRED"));
        }

        @Test
        @DisplayName("로그인 USER 는 통과한다 — 카테고리 목록은 카카오를 부르지 않아 200 이다")
        void loggedInUserPasses() throws Exception {
            mockMvc.perform(get("/api/locations/category-groups").with(authentication(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(18));
        }
    }

    // ─────────────────────────────────────────────── 축·범위 검증

    @Nested
    @DisplayName("좌표 범위 — 카카오 호출 전에 거절한다")
    class CoordinateValidation {

        /**
         * <b>축을 바꿔 넣으면 여기서 걸린다.</b> 서울은 위도 37.5·경도 127.0 인데 바꿔 넣으면
         * 위도 127 이 되어 대한민국 범위를 벗어난다. 카카오는 그것을 오류로 보지 않고
         * "결과 없음" 을 주므로, 서버가 먼저 끊지 않으면 <b>버그가 빈 결과로 위장된다.</b>
         */
        @Test
        @DisplayName("위도·경도를 바꿔 넣으면 400 이다 — 503 이 아니라는 점이 중요하다")
        void swappedAxisIsRejectedBeforeCallingKakao() throws Exception {
            mockMvc.perform(get(REVERSE)
                            .param("latitude", "127.1086228")   // 바꿔 넣었다
                            .param("longitude", "37.4012191")
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.message")
                            .value(org.hamcrest.Matchers.containsString("바꿔 보내지 않았는지")));
        }

        @ParameterizedTest(name = "lat={0}, lon={1}")
        @CsvSource({
                "0, 0",                 // 적도·본초자오선
                "37.5, 0",              // 경도만 범위 밖
                "0, 127.0",             // 위도만 범위 밖
                "35.6762, 139.6503",    // 도쿄
                "-33.8688, 151.2093",   // 시드니
                "39.0001, 127.0",       // 위도 상한 바로 밖
                "37.5, 132.0001"        // 경도 상한 바로 밖
        })
        @DisplayName("대한민국 범위를 벗어난 좌표는 400 이다")
        void outOfKoreaIsRejected(String latitude, String longitude) throws Exception {
            mockMvc.perform(get(REVERSE)
                            .param("latitude", latitude)
                            .param("longitude", longitude)
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest());
        }

        @ParameterizedTest(name = "lat={0}, lon={1}")
        @CsvSource({
                "37.4012191, 127.1086228",  // 서울
                "33.0, 124.0",              // 남서 경계
                "39.0, 132.0",              // 북동 경계
                "33.1, 126.5"               // 마라도 근처
        })
        @DisplayName("범위 안 좌표는 검증을 통과해 카카오까지 간다 — 키가 없어 503 이다")
        void withinKoreaReachesTransport(String latitude, String longitude) throws Exception {
            mockMvc.perform(get(REVERSE)
                            .param("latitude", latitude)
                            .param("longitude", longitude)
                            .with(authentication(user)))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test
        @DisplayName("좌표를 하나만 보내면 400 이다 — 조용히 무시하지 않는다")
        void halfCoordinateIsRejected() throws Exception {
            mockMvc.perform(get(PLACES)
                            .param("query", "정비소")
                            .param("latitude", "37.5")
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.message")
                            .value(org.hamcrest.Matchers.containsString("함께 보내야")));
        }

        @Test
        @DisplayName("숫자가 아닌 좌표는 400 이다")
        void nonNumericCoordinateIsRejected() throws Exception {
            mockMvc.perform(get(REVERSE)
                            .param("latitude", "서울")
                            .param("longitude", "127.0")
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("좌표 파라미터가 없으면 400 이다")
        void missingCoordinateIsRejected() throws Exception {
            mockMvc.perform(get(REVERSE).with(authentication(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    // ─────────────────────────────────────────────── 파라미터 조합

    @Nested
    @DisplayName("파라미터 조합 — 성립하지 않는 요청은 카카오 호출 전에 거절한다")
    class ParameterCombination {

        @Test
        @DisplayName("sort=distance 인데 좌표가 없으면 400 이다")
        void distanceSortWithoutCenterIsRejected() throws Exception {
            mockMvc.perform(get(PLACES)
                            .param("query", "정비소")
                            .param("sort", "DISTANCE")
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.message")
                            .value(org.hamcrest.Matchers.containsString("거리순 정렬")));
        }

        @Test
        @DisplayName("radius 만 있고 좌표가 없으면 400 이다")
        void radiusWithoutCenterIsRejected() throws Exception {
            mockMvc.perform(get(PLACES)
                            .param("query", "정비소")
                            .param("radius", "1000")
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.message")
                            .value(org.hamcrest.Matchers.containsString("radius")));
        }

        @Test
        @DisplayName("radius 가 음수면 400 이다")
        void negativeRadiusIsRejected() throws Exception {
            mockMvc.perform(get(PLACES)
                            .param("query", "정비소")
                            .param("latitude", "37.5").param("longitude", "127.0")
                            .param("radius", "-1")
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest());
        }

        /**
         * 상한 초과는 <b>거절이 아니라 절단</b>이다(S15P21A307-225 정책).
         * 검증을 통과해 전송 계층까지 가므로 키가 없는 이 테스트에서는 503 이 된다 —
         * 400 이 나오면 정책이 어긋난 것이다.
         */
        @ParameterizedTest(name = "radius={0}")
        @ValueSource(strings = {"20000", "20001", "999999"})
        @DisplayName("radius 상한 초과는 절단한다 — 400 이 아니다")
        void oversizedRadiusIsClamped(String radius) throws Exception {
            mockMvc.perform(get(PLACES)
                            .param("query", "정비소")
                            .param("latitude", "37.5").param("longitude", "127.0")
                            .param("radius", radius)
                            .with(authentication(user)))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test
        @DisplayName("카테고리 검색에 좌표가 없으면 400 이다")
        void categorySearchWithoutCenterIsRejected() throws Exception {
            mockMvc.perform(get(CATEGORY)
                            .param("categoryGroupCode", "PM9")
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("정의되지 않은 categoryGroupCode 는 400 이다 — 문자열을 카카오에 넘기지 않는다")
        void unknownCategoryCodeIsRejected() throws Exception {
            mockMvc.perform(get(CATEGORY)
                            .param("categoryGroupCode", "CAR9")   // 정비소용으로 지어낸 코드
                            .param("latitude", "37.5").param("longitude", "127.0")
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("query 가 비면 400 이다")
        void blankQueryIsRejected() throws Exception {
            mockMvc.perform(get(GEOCODE).param("query", "   ").with(authentication(user)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(GEOCODE).with(authentication(user)))
                    .andExpect(status().isBadRequest());
        }

        @ParameterizedTest(name = "{0}={1}")
        @CsvSource({"page,abc", "size,abc"})
        @DisplayName("숫자가 아닌 page·size 는 400 이다")
        void nonNumericPagingIsRejected(String name, String value) throws Exception {
            mockMvc.perform(get(GEOCODE)
                            .param("query", "서울시청")
                            .param(name, value)
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("숫자가 아닌 radius 는 400 이다")
        void nonNumericRadiusIsRejected() throws Exception {
            // radius 는 장소 검색에만 있는 파라미터다. geocode 에 붙이면 그냥 무시된다.
            mockMvc.perform(get(PLACES)
                            .param("query", "정비소")
                            .param("latitude", "37.5").param("longitude", "127.0")
                            .param("radius", "abc")
                            .with(authentication(user)))
                    .andExpect(status().isBadRequest());
        }

        @ParameterizedTest(name = "{0}={1}")
        @CsvSource({"page,999", "page,-5", "size,9999", "size,0"})
        @DisplayName("page·size 상한·하한 초과는 절단한다 — 400 이 아니다")
        void oversizedPagingIsClamped(String name, String value) throws Exception {
            mockMvc.perform(get(GEOCODE)
                            .param("query", "서울시청")
                            .param(name, value)
                            .with(authentication(user)))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    // ─────────────────────────────────────────────── 카테고리 목록

    @Nested
    @DisplayName("카테고리 그룹 코드")
    class CategoryGroups {

        @Test
        @DisplayName("18종이고 정비소·카센터 코드는 없다")
        void eighteenCodesWithoutRepairShop() {
            assertThat(CategoryGroupCode.values()).hasSize(18);
            assertThat(CategoryGroupCode.values())
                    .extracting(CategoryGroupCode::displayName)
                    .as("정비 업종이 없다. 정비소는 키워드 검색으로 찾아야 한다")
                    .noneMatch(name -> name.contains("정비") || name.contains("카센터")
                            || name.contains("공업사"));
        }

        @Test
        @DisplayName("enum 이름이 곧 카카오 코드다")
        void nameIsTheCode() {
            for (CategoryGroupCode code : CategoryGroupCode.values()) {
                assertThat(code.code()).isEqualTo(code.name());
            }
            assertThat(CategoryGroupCode.PM9.displayName()).isEqualTo("약국");
        }
    }
}
