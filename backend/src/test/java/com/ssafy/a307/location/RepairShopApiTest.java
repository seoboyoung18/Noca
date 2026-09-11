package com.ssafy.a307.location;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.kakao.KakaoLocalException;
import com.ssafy.a307.common.kakao.KakaoLocalPort;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/repair-shops} 의 HTTP 계약.
 *
 * <p>카카오는 목으로 바꿔 넣는다 — 실제 API 를 부르지 않는다. 요청 조립·거리 계산의 세부는
 * {@code RepairShopSearchServiceTest} 가 보고, 여기서는 <b>상태 코드·{@code error.code}·응답 모양·인가</b>
 * 만 본다. 설정이 {@code LocationKakaoFailureMappingTest} 와 같아 스프링 컨텍스트를 함께 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("주변 정비소 API")
class RepairShopApiTest {

    private static final long USER_ID = 92_301L;
    private static final String PATH = "/api/repair-shops";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private KakaoLocalPort kakaoLocalPort;

    private Authentication user;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'repair-shop-user', '정비소검색', 'USER', 'ACTIVE')
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
    @DisplayName("비인증은 401 이다 — 쿼터가 걸린 외부 자원을 비인증에 열지 않는다")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH).param("latitude", "37.5665").param("longitude", "126.9780"))
                .andExpect(status().isUnauthorized());
        then(kakaoLocalPort).should(never()).searchPlacesByKeyword(any());
    }

    @Test
    @DisplayName("가까운 순 목록이 {data} 봉투로 나가고 필드가 latitude·longitude 다")
    void returnsNearestFirst() throws Exception {
        given(kakaoLocalPort.searchPlacesByKeyword(any())).willReturn(new KakaoLocalPort.Paged<>(List.of(
                shop("far", 1_500), shop("near", 200)), 1, 15, 2, 2, true));

        mockMvc.perform(get(PATH).param("latitude", "37.5665").param("longitude", "126.9780")
                        .with(authentication(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].placeId").value("near"))
                .andExpect(jsonPath("$.data.content[0].distanceMeters").value(200))
                .andExpect(jsonPath("$.data.content[1].placeId").value("far"))
                .andExpect(jsonPath("$.data.content[0].latitude").value(37.5665))
                .andExpect(jsonPath("$.data.content[0].longitude").value(126.978))
                .andExpect(jsonPath("$.data.content[0].x").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        ArgumentCaptor<KakaoLocalPort.KeywordQuery> sent =
                ArgumentCaptor.forClass(KakaoLocalPort.KeywordQuery.class);
        then(kakaoLocalPort).should().searchPlacesByKeyword(sent.capture());
        assertThat(sent.getValue().sort()).isEqualTo(KakaoLocalPort.PlaceSort.DISTANCE);
        assertThat(sent.getValue().center().latitude()).isEqualByComparingTo("37.5665");
    }

    @Test
    @DisplayName("요청의 query 는 무시된다 — 검색어는 서버가 정한다")
    void clientQueryIsIgnored() throws Exception {
        given(kakaoLocalPort.searchPlacesByKeyword(any()))
                .willReturn(new KakaoLocalPort.Paged<>(List.of(), 1, 15, 0, 0, true));

        mockMvc.perform(get(PATH).param("latitude", "37.5665").param("longitude", "126.9780")
                        .param("query", "편의점").with(authentication(user)))
                .andExpect(status().isOk());

        ArgumentCaptor<KakaoLocalPort.KeywordQuery> sent =
                ArgumentCaptor.forClass(KakaoLocalPort.KeywordQuery.class);
        then(kakaoLocalPort).should().searchPlacesByKeyword(sent.capture());
        assertThat(sent.getValue().query()).isNotEqualTo("편의점");
    }

    @ParameterizedTest(name = "latitude={0}, longitude={1}")
    @CsvSource(value = {"null, null", "37.5665, null", "null, 126.9780"}, nullValues = "null")
    @DisplayName("현재 위치가 없으면 500 이 아니라 400 INVALID_REQUEST 다")
    void missingLocationIsBadRequest(String latitude, String longitude) throws Exception {
        var request = get(PATH).with(authentication(user));
        if (latitude != null) request.param("latitude", latitude);
        if (longitude != null) request.param("longitude", longitude);

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        then(kakaoLocalPort).should(never()).searchPlacesByKeyword(any());
    }

    @Test
    @DisplayName("위도·경도를 바꿔 보내면 400 이고 카카오를 부르지 않는다")
    void swappedAxesAreBadRequest() throws Exception {
        mockMvc.perform(get(PATH).param("latitude", "126.9780").param("longitude", "37.5665")
                        .with(authentication(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        then(kakaoLocalPort).should(never()).searchPlacesByKeyword(any());
    }

    @Test
    @DisplayName("숫자가 아닌 좌표·반경은 400 이다")
    void nonNumericIsBadRequest() throws Exception {
        mockMvc.perform(get(PATH).param("latitude", "abc").param("longitude", "126.9780")
                        .with(authentication(user)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("latitude", "37.5665").param("longitude", "126.9780")
                        .param("radius", "far").with(authentication(user)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카카오 쿼터 초과는 429 TOO_MANY_REQUESTS 다")
    void quotaIsTooManyRequests() throws Exception {
        given(kakaoLocalPort.searchPlacesByKeyword(any())).willThrow(new KakaoLocalException(
                ErrorCode.TOO_MANY_REQUESTS, false, "위치 정보 조회 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."));

        mockMvc.perform(get(PATH).param("latitude", "37.5665").param("longitude", "126.9780")
                        .with(authentication(user)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
    }

    private static KakaoLocalPort.Place shop(String id, int distance) {
        return new KakaoLocalPort.Place(id, "정비소-" + id, "자동차 > 자동차정비", null, null,
                "서울 중구 태평로1가 31", null,
                new KakaoLocalPort.Coordinate(new BigDecimal("37.5665"), new BigDecimal("126.9780")),
                "http://place.map.kakao.com/" + id, distance);
    }
}
