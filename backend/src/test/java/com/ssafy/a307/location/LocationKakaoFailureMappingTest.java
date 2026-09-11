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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카카오 호출 실패가 <b>HTTP 로 어떻게 나가는가.</b>
 *
 * <h2>왜 따로 두는가</h2>
 * {@code KakaoLocalClientTest} 는 카카오 본문 {@code code} 가 어떤 {@code ErrorCode} 로 분류되는지를
 * {@code KakaoLocalException} 수준에서 본다. {@code LocationApiTest} 는 키가 없는 컨텍스트라
 * 포트 자체가 없다. 그래서 <b>"분류된 예외가 실제 응답의 상태 코드와 {@code error.code} 가 되는가"</b>
 * 를 보는 테스트가 비어 있었고, 그 틈으로 쿼터 초과가 <b>500 INTERNAL_ERROR</b> 로 나가고 있었다 —
 * {@code KakaoLocalException} 은 {@code BusinessException} 이 아니고 {@code GlobalExceptionHandler}
 * 에 그 핸들러가 없어 catch-all 로 떨어졌다.
 *
 * <p>포트를 목으로 바꿔 넣어 전송 계층을 건너뛴다. 여기서 보는 것은 번역 한 단계뿐이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("카카오 실패의 HTTP 번역")
class LocationKakaoFailureMappingTest {

    private static final long USER_ID = 92_201L;
    /** {@code KakaoLocalClient} 가 실제로 만드는 사용자용 문구와 같은 형태. 키도 카카오 원문도 없다. */
    private static final String MESSAGE = "위치 정보 조회 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private KakaoLocalPort kakaoLocalPort;

    private Authentication user;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'kakao-fail-user', '카카오실패', 'USER', 'ACTIVE')
                """, USER_ID);
        Member member = memberRepository.findById(USER_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(member);
        user = new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from member where member_id = ?", USER_ID);
    }

    @ParameterizedTest(name = "{0} → HTTP {1}")
    @CsvSource({
            "TOO_MANY_REQUESTS,   429",
            "SERVICE_UNAVAILABLE, 503",
            "INVALID_REQUEST,     400",
            "INTERNAL_ERROR,      500"})
    @DisplayName("전송 계층이 정한 ErrorCode 가 그대로 HTTP 상태와 error.code 가 된다")
    void transportErrorCodeBecomesTheResponse(ErrorCode code, int httpStatus) throws Exception {
        given(kakaoLocalPort.searchPlacesByKeyword(any()))
                .willThrow(new KakaoLocalException(code, false, MESSAGE));

        mockMvc.perform(get("/api/locations/places").param("query", "서울시청")
                        .with(authentication(user)))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.error.code").value(code.name()))
                .andExpect(jsonPath("$.error.message").value(MESSAGE));
    }

    @Test
    @DisplayName("쿼터 초과는 네 엔드포인트 모두 429 다 — 한 곳만 번역하면 FE 가 화면마다 다르게 처리한다")
    void quotaIs429OnEveryEndpoint() throws Exception {
        KakaoLocalException quota = new KakaoLocalException(ErrorCode.TOO_MANY_REQUESTS, false, MESSAGE);
        given(kakaoLocalPort.searchAddress(any())).willThrow(quota);
        given(kakaoLocalPort.reverseGeocode(any())).willThrow(quota);
        given(kakaoLocalPort.searchPlacesByKeyword(any())).willThrow(quota);
        given(kakaoLocalPort.searchPlacesByCategory(any())).willThrow(quota);

        mockMvc.perform(get("/api/locations/geocode").param("query", "서울시청").with(authentication(user)))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/locations/reverse-geocode")
                        .param("latitude", "37.5665").param("longitude", "126.9780")
                        .with(authentication(user)))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/locations/places").param("query", "서울시청").with(authentication(user)))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/locations/places/category").param("categoryGroupCode", "PM9")
                        .param("latitude", "37.5665").param("longitude", "126.9780")
                        .with(authentication(user)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("오류 응답에 키·Authorization 헤더 흔적이 없다")
    void failureBodyHasNoKey() throws Exception {
        given(kakaoLocalPort.searchPlacesByKeyword(any()))
                .willThrow(new KakaoLocalException(ErrorCode.SERVICE_UNAVAILABLE, false,
                        "위치 정보 서비스를 일시적으로 사용할 수 없습니다."));

        String body = mockMvc.perform(get("/api/locations/places").param("query", "서울시청")
                        .with(authentication(user)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("KakaoAK").doesNotContain("Authorization");
    }
}
