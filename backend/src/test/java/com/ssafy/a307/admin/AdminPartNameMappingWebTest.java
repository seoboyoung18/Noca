package com.ssafy.a307.admin;

import com.ssafy.a307.admin.dto.PartCodeCreateRequest;
import com.ssafy.a307.admin.service.AdminPartCodeService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Member;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 부품명 매핑의 <b>HTTP 계약</b>. 특히 {@code rawName} 이 경로 변수에서 쿼리 파라미터로
 * 옮겨 간 것이 실제 시드 데이터에서 동작하는지를 본다.
 *
 * <h2>왜 서비스 테스트로 충분하지 않은가</h2>
 * {@code AdminMasterDataTest} 는 서비스를 직접 부르므로 URL 인코딩·디코딩을 전혀 거치지
 * 않는다. 그런데 이번 계약 변경의 이유가 <b>바로 그 인코딩</b>이다 — 시드
 * {@code raw_name} 에 {@code /} 가 346건, {@code %} 가 360건, {@code #} 가 7건 들어 있다.
 * 여기서는 인코딩된 URI 를 만들어 MockMvc 가 쿼리 문자열을 파싱·디코딩하게 한 뒤,
 * 서버가 <b>원문 그대로</b> 받았는지 확인한다.
 *
 * <p><b>쿼리 파라미터가 새로 들여오는 위험도 함께 검증한다.</b> 경로에서는 평범했던
 * {@code &}(61건)와 {@code +}(38건)가 쿼리 문자열에서는 각각 파라미터 구분자와 공백이 된다.
 * 그래서 {@code /}·{@code %}·{@code #} 만이 아니라 이 둘까지 왕복시킨다.
 *
 * <p><b>경로 변수로는 왜 안 되는가.</b> {@code /} 는 경로 구분자이고,
 * {@code %2F} 로 인코딩해도 스프링 시큐리티의 {@code StrictHttpFirewall} 이 기본값
 * ({@code allowUrlEncodedSlash = false})으로 거부한다. {@code SecurityConfig} 는 방화벽을
 * 따로 설정하지 않으므로 이 기본값이 그대로 적용된다. 별칭 하나 고치자고 전역 보안 설정을
 * 낮출 수는 없어 계약 쪽을 바꿨다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("부품명 매핑 HTTP 계약 (rawName 은 쿼리 파라미터)")
class AdminPartNameMappingWebTest {

    private static final String BASE = "/api/admin/part-name-mappings";
    private static final long ADMIN_ID = 94_101L;
    private static final String PART_A = "ZZ_WEB_PART_A";
    private static final String REASON = "경로 계약 검증";

    /** 시드에 실제로 들어 있는 모양의 원문. 경로 변수로는 표현할 수 없는 것들이다. */
    static Stream<String> reservedCharacterNames() {
        return Stream.of(
                "뒤범퍼2/1탈착",                    // '/' — 시드 346건
                "도어(앞우) 그린수가 50% 청구",      // '%' — 시드 360건
                "언더커버#1(앞뒤바닥)",              // '#' — 시드 7건
                "뒤범퍼 로워범퍼(분할형/수리&재사용)",  // '&' — 시드 61건. 쿼리 파라미터 구분자다
                "도어밸트(눈썹)몰딩(앞,우)+-재사용",   // '+' — 시드 38건. 쿼리에서 공백으로 디코딩된다
                "앞 범퍼(좌) 교환",                  // 한글·공백·괄호
                "휀더/도어#1 50%재사용&+");           // 전부 한꺼번에
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminPartCodeService partCodeService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Authentication admin;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'admin-web', '웹계약관리자', 'ADMIN', 'ACTIVE')
                """, ADMIN_ID);
        Member member = memberRepository.findById(ADMIN_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(member);
        admin = new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
        // 준비 단계의 부품 코드 등록도 감사 이력을 남긴다 — 행위자는 세션에서만 오므로
        // MockMvc 의 .with(authentication(...)) 만으로는 부족하고 컨텍스트에 올려야 한다.
        SecurityContextHolder.getContext().setAuthentication(admin);

        partCodeService.create(new PartCodeCreateRequest(PART_A, "웹계약", "FRONT", 940, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("delete from part_name_mapping where part_code = ?", PART_A);
        jdbcTemplate.update("delete from part_code where part_code = ?", PART_A);
        jdbcTemplate.update("delete from audit_log where actor_member_id = ?", ADMIN_ID);
        jdbcTemplate.update("delete from member where member_id = ?", ADMIN_ID);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reservedCharacterNames")
    @DisplayName("경로 예약 문자가 든 원문도 등록·수정·삭제된다")
    void reservedCharactersSurviveTheRoundTrip(String rawName) throws Exception {
        mockMvc.perform(post(BASE).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(rawName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rawName").value(rawName));

        assertThat(storedNames()).as("DB 에 원문 그대로 들어갔다").contains(rawName);

        mockMvc.perform(patch(uri(BASE, "rawName", rawName)).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partCode": "%s", "changeReason": "%s"}
                                """.formatted(PART_A, REASON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawName").value(rawName));

        mockMvc.perform(delete(uri(BASE, "rawName", rawName, "changeReason", REASON))
                        .with(authentication(admin)))
                .andExpect(status().isNoContent());

        assertThat(storedNames()).as("삭제되었다").doesNotContain(rawName);
    }

    @Test
    @DisplayName("rawName 을 빠뜨리면 500 이 아니라 400 이다")
    void missingRawNameParameterIsBadRequest() throws Exception {
        mockMvc.perform(delete(BASE).param("changeReason", REASON).with(authentication(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("rawName")));
    }

    @Test
    @DisplayName("삭제의 changeReason 을 빠뜨리면 400 이다 — 사유 없는 이력을 만들지 않는다")
    void missingChangeReasonParameterIsBadRequest() throws Exception {
        mockMvc.perform(delete(BASE).param("rawName", "무엇이든").with(authentication(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("등록 본문의 changeReason 이 공백이면 400 이다")
    void blankChangeReasonInBodyIsBadRequest() throws Exception {
        mockMvc.perform(post(BASE).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rawName": "ZZ웹공백사유", "partCode": "%s", "changeReason": "   "}
                                """.formatted(PART_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(storedNames()).doesNotContain("ZZ웹공백사유");
    }

    @Test
    @DisplayName("compact 키 충돌은 409 이고 error.code 로 원인을 가른다")
    void conflictsCarryDistinctCodes() throws Exception {
        mockMvc.perform(post(BASE).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("ZZ웹충돌원문")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("ZZ웹충돌원문")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_PART_NAME_MAPPING"));

        mockMvc.perform(post(BASE).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("ZZ웹.충돌/원문")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_PART_NAME_MAPPING"));
    }

    @Test
    @DisplayName("목록은 확정 매핑만 내려보내고 상태 필드를 지어내지 않는다")
    void listExposesOnlyConfirmedMappings() throws Exception {
        mockMvc.perform(post(BASE).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("ZZ웹목록원문")))
                .andExpect(status().isCreated());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(BASE).param("keyword", "ZZ웹목록").with(authentication(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].rawName").value("ZZ웹목록원문"))
                .andExpect(jsonPath("$.data.content[0].partCode").value(PART_A))
                .andExpect(jsonPath("$.data.content[0].partActive").value(true))
                // MAPPED / MAPPED_EXTENDED 같은 seed 상태 필드는 DB 에 없으므로 응답에도 없다.
                .andExpect(jsonPath("$.data.content[0].mappingStatus").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].status").doesNotExist());
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    private String createBody(String rawName) {
        // 텍스트 블록에 넣으면 " 나 \ 가 그대로 새므로 JSON 문자열은 이스케이프해서 만든다.
        return "{\"rawName\":%s,\"partCode\":\"%s\",\"changeReason\":\"%s\"}"
                .formatted(quote(rawName), PART_A, REASON);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 값을 퍼센트 인코딩한 URI 를 만든다. MockMvc 는 이 쿼리 문자열을 파싱하고 UTF-8 로
     * 디코딩하므로 <b>실제 왕복</b>이 검증된다 — {@code .param()} 으로 값을 직접 꽂으면
     * 인코딩 단계를 건너뛰어 이 테스트의 의미가 사라진다.
     */
    private static URI uri(String path, String... keyValues) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.queryParam(keyValues[i],
                    org.springframework.web.util.UriUtils.encode(keyValues[i + 1], StandardCharsets.UTF_8));
        }
        return builder.build(true).toUri();
    }

    private java.util.List<String> storedNames() {
        return jdbcTemplate.queryForList(
                "select raw_name from part_name_mapping where part_code = ?", String.class, PART_A);
    }
}
