package com.ssafy.a307.repaircase;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유사 사례 조회 API (S15P21A307-236).
 *
 * <p><b>H2 가 아니라 로컬 PostgreSQL 로 돈다.</b> {@code schema-h2.sql} 에 {@code repair_case}
 * 계열 테이블이 없어 H2 로는 이 경로를 아예 실행할 수 없다. 컨테이너에는 정본 DDL 이 그대로
 * 들어가 있으므로 거기 붙는다 — 새 라이브러리를 넣지 않고, 다른 담당자의 테스트 스키마 파일도
 * 건드리지 않는 방법이다.
 *
 * <p><b>컨테이너가 없으면 건너뛴다.</b> {@code docker compose up -d} 를 안 한 환경에서 전체
 * 스위트가 무너지지 않게 한다. 다만 그 환경에서는 이 경로가 검증되지 않는다는 뜻이기도 하다.
 *
 * <p><b>{@code @Transactional} 로 롤백한다.</b> 로컬 개발 DB 를 공유하므로 심은 데이터를 남기지
 * 않는다.
 *
 * <p>AI 서버가 아직 없어 {@code ref_condition} 을 SQL 로 직접 심는다. 견적 조회·근거 조회에서
 * 쓴 방식과 같고, <b>계약은 데이터를 만들어 내는 경로 없이도 지금 고정할 수 있다.</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("localPostgresRunning")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/a307",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=a307",
        "spring.datasource.password=ssafy",
        "spring.sql.init.mode=never",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@DisplayName("유사 사례 조회")
class SimilarCaseApiTest {

    private static final String KAKAO_ID = "3877665544";

    /** 컨테이너가 떠 있을 때만 돈다. 컨텍스트가 만들어지기 전에 평가된다. */
    static boolean localPostgresRunning() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockHttpSession session;
    private long memberId;

    @BeforeEach
    void signUp() throws Exception {
        session = signedInSession();
        memberId = memberRepository.findByProviderAndProviderUserId(Provider.KAKAO, KAKAO_ID)
                .orElseThrow()
                .getMemberId();
    }

    @Test
    @DisplayName("항목이 참고한 사례를 AI 가 준 순서대로 준다")
    void returnsCasesInGivenOrder() throws Exception {
        long caseA = insertCase("현대", "아반떼", (short) 2021, 335_500);
        long caseB = insertCase("기아", "K3", (short) 2022, 280_000);

        Fixture f = insertEstimateItem(
                "{\"fallbackStage\":\"MODEL\",\"refYearFrom\":2021,\"refYearTo\":2022,"
                        + "\"referencedCaseIds\":[" + caseB + "," + caseA + "]}");

        mockMvc.perform(get("/api/estimates/{id}/similar-cases", f.estimateId())
                        .param("estimateItemId", String.valueOf(f.estimateItemId()))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimateItemId").value((int) f.estimateItemId()))
                .andExpect(jsonPath("$.data.partNameKo").value("앞 범퍼"))
                .andExpect(jsonPath("$.data.repairMethodDisplayName").value("교환"))
                .andExpect(jsonPath("$.data.fallbackStage").value("MODEL"))
                // 통계에 쓴 전체 건수. 목록 길이와 다르다
                .andExpect(jsonPath("$.data.refCaseCount").value(18))
                .andExpect(jsonPath("$.data.cases.length()").value(2))
                // SQL IN 은 순서를 보장하지 않는다. AI 가 준 순서(B, A)를 지켜야 한다
                .andExpect(jsonPath("$.data.cases[0].caseId").value((int) caseB))
                .andExpect(jsonPath("$.data.cases[1].caseId").value((int) caseA))
                .andExpect(jsonPath("$.data.cases[1].modelName").value("아반떼"))
                .andExpect(jsonPath("$.data.cases[1].repairYear").value(2021))
                .andExpect(jsonPath("$.data.cases[1].partTotal").value(335_500));
    }

    /** 참조할 사례가 없는 항목도 화면은 그려야 한다. 빈 목록이지 오류가 아니다. */
    @Test
    @DisplayName("참조 사례가 없으면 빈 목록이다")
    void emptyWhenNoReferencedCases() throws Exception {
        Fixture f = insertEstimateItem("{}");

        mockMvc.perform(get("/api/estimates/{id}/similar-cases", f.estimateId())
                        .param("estimateItemId", String.valueOf(f.estimateItemId()))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cases.length()").value(0))
                .andExpect(jsonPath("$.data.fallbackStage").value(nullValue()));
    }

    /** 사례 데이터가 다시 적재되며 사라질 수 있다. 한 건 때문에 나머지 근거를 못 보면 안 된다. */
    @Test
    @DisplayName("없는 사례 ID 는 조용히 빠지고 나머지는 그대로 온다")
    void missingCaseIsSkipped() throws Exception {
        long caseA = insertCase("현대", "아반떼", (short) 2021, 335_500);

        Fixture f = insertEstimateItem(
                "{\"referencedCaseIds\":[" + caseA + ",999999999]}");

        mockMvc.perform(get("/api/estimates/{id}/similar-cases", f.estimateId())
                        .param("estimateItemId", String.valueOf(f.estimateItemId()))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cases.length()").value(1))
                .andExpect(jsonPath("$.data.cases[0].caseId").value((int) caseA));
    }

    /**
     * S15P21A307-223 이 실사용자 사고를 {@code source='SERVICE'} 로 적재하기 시작한다.
     * 그 금액은 AI 추정에서 온 것이라 사례로 쓸 만큼 검증되지 않았고, 무엇보다 다른 사람의
     * 데이터다. 상세 조회({@code findPublicCase})는 이미 막혀 있는데 <b>이 목록이 뚫려 있으면
     * 막지 않은 것과 같다</b> — AI 가 실수로 그 사례를 참조하면 그대로 화면에 나간다.
     */
    @Test
    @DisplayName("SERVICE 사례는 참조돼 있어도 목록에서 빠진다")
    void serviceCaseIsExcluded() throws Exception {
        long aihubCase = insertCase("현대", "아반떼", (short) 2021, 335_500);
        long serviceCase = insertServiceCase("기아", "K5", (short) 2026, 1_250_000);

        Fixture f = insertEstimateItem(
                "{\"referencedCaseIds\":[" + aihubCase + "," + serviceCase + "]}");

        mockMvc.perform(get("/api/estimates/{id}/similar-cases", f.estimateId())
                        .param("estimateItemId", String.valueOf(f.estimateItemId()))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cases.length()").value(1))
                .andExpect(jsonPath("$.data.cases[0].caseId").value((int) aihubCase));
    }

    /** 참고 정가는 정산에 포함되지 않는다. 합산하면 사례 금액이 부풀려진다. */
    @Test
    @DisplayName("REFERENCE_PRICE 행은 부위 금액에 넣지 않는다")
    void referencePriceIsExcluded() throws Exception {
        long caseId = insertCase("현대", "아반떼", (short) 2021, 300_000);
        jdbcTemplate.update(
                "insert into repair_case_item (case_id, source_item_key, part_code, line_type,"
                        + " item_total) values (?, ?, 'FRONT_BUMPER', 'REFERENCE_PRICE', 900000)",
                caseId, itemKey());

        Fixture f = insertEstimateItem("{\"referencedCaseIds\":[" + caseId + "]}");

        mockMvc.perform(get("/api/estimates/{id}/similar-cases", f.estimateId())
                        .param("estimateItemId", String.valueOf(f.estimateItemId()))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cases[0].partTotal").value(300_000));
    }

    /**
     * 근거에도 수리비가 들어 있어 남에게 보이면 안 된다. 견적 조회와 같은 판정을 내려야
     * 한쪽으로 새어 나가지 않는다.
     */
    @Test
    @DisplayName("남의 견적 항목은 404 다")
    void othersItemIsNotFound() throws Exception {
        long otherMemberId = insertMember("other-" + System.nanoTime());
        Fixture other = insertEstimateItem("{}", otherMemberId);

        mockMvc.perform(get("/api/estimates/{id}/similar-cases", other.estimateId())
                        .param("estimateItemId", String.valueOf(other.estimateItemId()))
                        .session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /** 경로의 견적과 항목이 어긋나면 통과시키지 않는다. */
    @Test
    @DisplayName("다른 견적의 항목 번호를 넣으면 404 다")
    void itemFromAnotherEstimateIsNotFound() throws Exception {
        Fixture mine = insertEstimateItem("{}");
        Fixture another = insertEstimateItem("{}");

        mockMvc.perform(get("/api/estimates/{id}/similar-cases", mine.estimateId())
                        .param("estimateItemId", String.valueOf(another.estimateItemId()))
                        .session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("estimateItemId 가 없으면 400 이다")
    void missingItemIdIsBadRequest() throws Exception {
        Fixture f = insertEstimateItem("{}");

        mockMvc.perform(get("/api/estimates/{id}/similar-cases", f.estimateId()).session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/estimates/{id}/similar-cases", 1).param("estimateItemId", "1"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 데이터 준비 ----------

    private record Fixture(long estimateId, long estimateItemId) {
    }

    private Fixture insertEstimateItem(String refCondition) {
        return insertEstimateItem(refCondition, memberId);
    }

    private Fixture insertEstimateItem(String refCondition, long ownerId) {
        long accidentId = insertAccident(ownerId);
        Long jobId = insertAnalysisJob(accidentId);

        insertPartCode();
        jdbcTemplate.update(
                "insert into damaged_part (job_id, part_code, damage_type, repair_method, confidence)"
                        + " values (?, 'FRONT_BUMPER', 'Crushed', 'exchange', 0.9200)", jobId);
        Long damagedPartId = jdbcTemplate.queryForObject(
                "select damaged_part_id from damaged_part where job_id = ?", Long.class, jobId);

        jdbcTemplate.update(
                "insert into estimate (job_id, version, is_estimable, total_median)"
                        + " values (?, 1, true, 800000)", jobId);
        Long estimateId = jdbcTemplate.queryForObject(
                "select estimate_id from estimate where job_id = ?", Long.class, jobId);

        jdbcTemplate.update(
                "insert into estimate_item (estimate_id, damaged_part_id, repair_method,"
                        + " item_min, item_median, item_max, ref_case_count, ref_condition)"
                        + " values (?, ?, 'exchange', 74000, 92000, 118000, 18, cast(? as jsonb))",
                estimateId, damagedPartId, refCondition);
        Long estimateItemId = jdbcTemplate.queryForObject(
                "select estimate_item_id from estimate_item where estimate_id = ?",
                Long.class, estimateId);

        return new Fixture(estimateId, estimateItemId);
    }

    /**
     * {@code repair_case_item.source_item_key} 는 NOT NULL 이고 {@code uk_rci_source_item
     * (case_id, source_item_key)} 로 묶여 있다 — 원천 견적 배열 순번이자 재적재 멱등 키다
     * (S15P21A307-232). 테스트에는 대응하는 원천이 없으므로 호출마다 겹치지 않는 값을 만든다.
     */
    private static String itemKey() {
        return "test-" + System.nanoTime();
    }

    private void insertPartCode() {
        jdbcTemplate.update(
                "insert into part_code (part_code, name_ko, layout_zone, display_order, is_active)"
                        + " values ('FRONT_BUMPER', '앞 범퍼', 'FRONT', 1, true)"
                        + " on conflict (part_code) do nothing");
    }

    private long insertCase(String manufacturer, String modelName, short repairYear, int itemTotal) {
        return insertCase("AIHUB_AS", "TEST-" + System.nanoTime(),
                manufacturer, modelName, repairYear, itemTotal);
    }

    /**
     * 서비스 사고에서 온 사례. {@code external_ref} 접두사는 적재 job 이 쓰는 것과 같다
     * ({@code pipeline/jobs/load_service_accidents.py}).
     */
    private long insertServiceCase(String manufacturer, String modelName, short repairYear,
                                   int itemTotal) {
        return insertCase("SERVICE", "svc-test-" + System.nanoTime(),
                manufacturer, modelName, repairYear, itemTotal);
    }

    private long insertCase(String source, String ref, String manufacturer, String modelName,
                            short repairYear, int itemTotal) {
        insertPartCode();
        jdbcTemplate.update(
                "insert into repair_case (source, external_ref, manufacturer, model_name,"
                        + " car_class, repair_year) values (?, ?, ?, ?, 'Compact', ?)",
                source, ref, manufacturer, modelName, repairYear);
        Long caseId = jdbcTemplate.queryForObject(
                "select case_id from repair_case where external_ref = ?", Long.class, ref);

        jdbcTemplate.update(
                "insert into repair_case_item (case_id, source_item_key, part_code, line_type,"
                        + " work_code, item_total) values (?, ?, 'FRONT_BUMPER', 'WORK', 'EXCHANGE', ?)",
                caseId, itemKey(), itemTotal);
        jdbcTemplate.update(
                "insert into repair_case_image (case_id, source_image_ref, storage_key,"
                        + " image_type, is_searchable) values (?, ?, ?, 'DAMAGE_PART', true)",
                caseId, ref + ".jpg", "repair-cases/" + caseId + "/images/1/original.jpg");

        return caseId;
    }

    private long insertMember(String providerUserId) {
        jdbcTemplate.update(
                "insert into member (provider, provider_user_id, nickname, role, status)"
                        + " values ('KAKAO', ?, '사례조회', 'USER', 'ACTIVE')", providerUserId);
        return jdbcTemplate.queryForObject(
                "select member_id from member where provider_user_id = ?", Long.class, providerUserId);
    }

    private long insertAccident(long ownerId) {
        String modelName = "아반떼" + System.nanoTime();
        jdbcTemplate.update(
                "insert into vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)"
                        + " values ('현대', ?, 'SEDAN', 'Compact', true)", modelName);
        Long modelId = jdbcTemplate.queryForObject(
                "select model_id from vehicle_model where model_name = ?", Long.class, modelName);

        jdbcTemplate.update(
                "insert into vehicle (member_id, model_id, model_year) values (?, ?, 2020)",
                ownerId, modelId);
        Long vehicleId = jdbcTemplate.queryForObject(
                "select max(vehicle_id) from vehicle where member_id = ?", Long.class, ownerId);

        jdbcTemplate.update(
                "insert into accident (vehicle_id, vehicle_input_type, snapshot_model_id,"
                        + " snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,"
                        + " snapshot_car_class, snapshot_model_year)"
                        + " values (?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Compact', 2020)",
                vehicleId, modelId);
        return jdbcTemplate.queryForObject(
                "select max(accident_id) from accident where vehicle_id = ?", Long.class, vehicleId);
    }

    private Long insertAnalysisJob(long accidentId) {
        jdbcTemplate.update(
                "insert into analysis_job (accident_id, status) values (?, 'COMPLETED')", accidentId);
        return jdbcTemplate.queryForObject(
                "select max(job_id) from analysis_job where accident_id = ?", Long.class, accidentId);
    }

    private MockHttpSession signedInSession() throws Exception {
        UserPrincipal principal =
                UserPrincipal.ofPendingSignup(Provider.KAKAO, KAKAO_ID, "카카오닉네임");
        Authentication authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), Provider.KAKAO.registrationId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        MockHttpSession created = new MockHttpSession();
        created.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        mockMvc.perform(post("/api/auth/signup")
                        .session(created)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"보영\",\"agreedTerms\":[\"SERVICE\",\"PRIVACY\"]}"))
                .andExpect(status().isCreated());

        return created;
    }
}
