package com.ssafy.a307.estimate;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 견적 조회 API. 필터 체인을 켠 채로 본다.
 *
 * <p>데이터를 SQL 로 심는다. {@code analysis_job}·{@code damaged_part}·{@code estimate_item} 은
 * 아직 엔티티가 없고, 산정 로직(S15P21A307-256·257)도 없어 견적을 만들어 낼 경로가 없다.
 * 조회 계약은 그와 무관하게 지금 확정할 수 있다.
 *
 * <p><b>소유자 검사가 이 API 의 핵심이다.</b> 견적에는 수리비가 들어 있어 남에게 보이면 안 된다.
 * 소유자까지 가는 길이 {@code estimate → analysis_job → accident → vehicle → member} 로 길어,
 * 중간 어디가 끊겨도 새어 나가지 않는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("견적 조회")
class EstimateQueryApiTest {

    private static final String KAKAO_ID = "3866554433";

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
    @DisplayName("상세 조회는 항목과 고지 문구를 함께 준다")
    void detailIncludesItems() throws Exception {
        long accidentId = insertAccident(memberId);
        long jobId = insertAnalysisJob(accidentId);
        long estimateId = insertEstimate(jobId, (short) 1, 800_000);
        insertItem(estimateId, jobId, "front_bumper", "프론트 범퍼", (short) 1, "exchange", 300_000);

        mockMvc.perform(get("/api/estimates/{id}", estimateId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimateId").value((int) estimateId))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.estimable").value(true))
                .andExpect(jsonPath("$.data.totalMedian").value(800_000))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].partNameKo").value("프론트 범퍼"))
                .andExpect(jsonPath("$.data.items[0].damageType").value("Crushed"))
                // 서버가 표시 문구를 준다 — FE 가 4종을 하드코딩하지 않게
                .andExpect(jsonPath("$.data.items[0].repairMethod").value("exchange"))
                .andExpect(jsonPath("$.data.items[0].repairMethodDisplayName").value("교환"))
                // estimate_notice 테이블에서 내려온다(S15P21A307-288). 시드는 LEGAL_NOTICE 한 건이다.
                // 문장 자체는 여기서 단언하지 않는다 — 운영자가 psql 로 바꿀 수 있는 값이라
                // 이 테스트가 문구 개정을 막아서는 안 된다. 값 정합성은
                // EstimateNoticeProviderTest 가 상수·마이그레이션·시드 셋을 묶어 본다.
                .andExpect(jsonPath("$.data.notices.length()").value(1))
                .andExpect(jsonPath("$.data.notices[0].code").value("LEGAL_NOTICE"))
                .andExpect(jsonPath("$.data.notices[0].message").isNotEmpty());
    }

    /** 화면이 앞·뒤·좌·우를 매번 같은 차례로 그려야 사용자가 같은 자리에서 찾는다. */
    @Test
    @DisplayName("항목은 부위 표시 순서대로 정렬된다")
    void itemsAreOrderedByDisplayOrder() throws Exception {
        long accidentId = insertAccident(memberId);
        long jobId = insertAnalysisJob(accidentId);
        long estimateId = insertEstimate(jobId, (short) 1, 800_000);

        insertItem(estimateId, jobId, "rear_bumper", "리어 범퍼", (short) 9, "repair", 100_000);
        insertItem(estimateId, jobId, "hood", "본넷", (short) 5, "sheet_metal", 200_000);
        insertItem(estimateId, jobId, "front_bumper", "프론트 범퍼", (short) 1, "exchange", 300_000);

        mockMvc.perform(get("/api/estimates/{id}", estimateId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].partNameKo").value("프론트 범퍼"))
                .andExpect(jsonPath("$.data.items[1].partNameKo").value("본넷"))
                .andExpect(jsonPath("$.data.items[2].partNameKo").value("리어 범퍼"));
    }

    @Test
    @DisplayName("산정 불가 견적은 금액이 비고 사유만 온다")
    void nonEstimableHasReasonWithoutAmounts() throws Exception {
        long accidentId = insertAccident(memberId);
        long jobId = insertAnalysisJob(accidentId);
        long estimateId = insertNonEstimable(jobId, "참조할 유사 사례가 부족합니다.");

        mockMvc.perform(get("/api/estimates/{id}", estimateId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimable").value(false))
                .andExpect(jsonPath("$.data.nonEstimableReason").value("참조할 유사 사례가 부족합니다."))
                .andExpect(jsonPath("$.data.totalMedian").value(nullValue()))
                .andExpect(jsonPath("$.data.confidenceGrade").value(nullValue()));
    }

    /**
     * 403 과 404 를 나누면 "그 견적은 존재한다"는 사실이 새어 나간다. 수리비가 담긴
     * 자원이라 존재 여부 자체를 감춘다.
     */
    @Test
    @DisplayName("남의 견적은 404 다 — 존재 여부를 알려주지 않는다")
    void othersEstimateIsNotFound() throws Exception {
        long otherMemberId = insertMember("other-" + System.nanoTime());
        long otherAccidentId = insertAccident(otherMemberId);
        long otherJobId = insertAnalysisJob(otherAccidentId);
        long otherEstimateId = insertEstimate(otherJobId, (short) 1, 500_000);

        mockMvc.perform(get("/api/estimates/{id}", otherEstimateId).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 견적도 같은 404 다")
    void missingEstimateIsNotFound() throws Exception {
        mockMvc.perform(get("/api/estimates/{id}", 999_999).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("목록은 기본으로 최신 한 건만 준다")
    void historyReturnsLatestByDefault() throws Exception {
        long accidentId = insertAccident(memberId);
        long jobId = insertAnalysisJob(accidentId);
        insertEstimate(jobId, (short) 1, 800_000);
        insertEstimate(jobId, (short) 2, 950_000);

        mockMvc.perform(get("/api/accidents/{id}/estimates", accidentId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[0].totalMedian").value(950_000));
    }

    @Test
    @DisplayName("latest=false 면 버전 이력을 최신부터 전부 준다")
    void historyReturnsAllVersions() throws Exception {
        long accidentId = insertAccident(memberId);
        long jobId = insertAnalysisJob(accidentId);
        insertEstimate(jobId, (short) 1, 800_000);
        insertEstimate(jobId, (short) 2, 950_000);
        insertEstimate(jobId, (short) 3, 910_000);

        mockMvc.perform(get("/api/accidents/{id}/estimates", accidentId)
                        .param("latest", "false")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].version").value(3))
                .andExpect(jsonPath("$.data[2].version").value(1));
    }

    /** 견적이 아직 없는 것과 남의 사고인 것은 다르다. 전자는 정상적인 빈 목록이다. */
    @Test
    @DisplayName("내 사고인데 견적이 없으면 빈 목록이다 — 404 가 아니다")
    void ownAccidentWithoutEstimateReturnsEmpty() throws Exception {
        long accidentId = insertAccident(memberId);

        mockMvc.perform(get("/api/accidents/{id}/estimates", accidentId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("남의 사고 견적 목록은 404 다")
    void othersAccidentHistoryIsNotFound() throws Exception {
        long otherMemberId = insertMember("other-" + System.nanoTime());
        long otherAccidentId = insertAccident(otherMemberId);

        mockMvc.perform(get("/api/accidents/{id}/estimates", otherAccidentId).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/estimates/{id}", 1))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/accidents/{id}/estimates", 1))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 산정 근거 조회(S15P21A307-286).
     *
     * <p><b>근거를 채우는 쪽이 아직 없다.</b> 산정 로직(S15P21A307-256·257)이 들어오기 전까지
     * 모든 행의 {@code ref_condition} 은 빈 객체다. 그래도 <b>계약과 소유자 검사는 지금
     * 확정할 수 있고</b>, 근거가 없다는 사실을 화면에 알리는 것 자체가 요구사항이다(명세서 51행).
     */
    @Nested
    @DisplayName("산정 근거")
    class Basis {

        private static final String FULL_BASIS = """
                {"fallbackStage":"MODEL",
                 "costDistribution":{"p25":74000,"median":92000,"p75":118000},
                 "refYearFrom":2023,"refYearTo":2025,
                 "repairMethodReason":{"candidates":["sheet_metal","exchange"],"reasonCode":"MAJORITY"}}
                """;

        @Test
        @DisplayName("근거가 있으면 문구와 상세를 함께 준다")
        void returnsNarrativeAndDetail() throws Exception {
            long accidentId = insertAccident(memberId);
            long jobId = insertAnalysisJob(accidentId);
            long estimateId = insertEstimate(jobId, (short) 2, 800_000);
            insertItem(estimateId, jobId, "front_bumper", "프론트 범퍼",
                    (short) 1, "exchange", 300_000, FULL_BASIS);

            mockMvc.perform(get("/api/estimates/{id}/basis", estimateId).session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.estimateId").value((int) estimateId))
                    // 어느 버전의 근거인지 밝히지 않으면 옛 견적에 새 근거가 붙어 보인다
                    .andExpect(jsonPath("$.data.version").value(2))
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].basisAvailable").value(true))
                    .andExpect(jsonPath("$.data.items[0].narrative")
                            .value("2023~2025년 동일 모델 프론트 범퍼 교환 사례 12건의 중앙값 기준"))
                    .andExpect(jsonPath("$.data.items[0].fallbackStage").value("MODEL"))
                    .andExpect(jsonPath("$.data.items[0].costDistribution.median").value(92_000))
                    .andExpect(jsonPath("$.data.items[0].refYearFrom").value(2023))
                    .andExpect(jsonPath("$.data.items[0].repairMethodReason.candidates.length()")
                            .value(2))
                    .andExpect(jsonPath("$.data.items[0].refCaseCount").value(12));
        }

        /**
         * 산정 로직이 들어오기 전 모든 행이 이 상태다. 빈 근거를 "근거가 있다"고 내보내면
         * 화면은 확인되지 않은 견적을 확인된 것처럼 보여 준다.
         */
        @Test
        @DisplayName("근거가 비면 그 사실이 명시된다")
        void emptyBasisIsStated() throws Exception {
            long accidentId = insertAccident(memberId);
            long jobId = insertAnalysisJob(accidentId);
            long estimateId = insertEstimate(jobId, (short) 1, 800_000);
            insertItem(estimateId, jobId, "hood", "본넷", (short) 5, "sheet_metal", 200_000);

            mockMvc.perform(get("/api/estimates/{id}/basis", estimateId).session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].basisAvailable").value(false))
                    .andExpect(jsonPath("$.data.items[0].fallbackStage").value(nullValue()))
                    .andExpect(jsonPath("$.data.items[0].costDistribution").value(nullValue()))
                    // 건수는 별도 컬럼이라 근거가 비어도 남아 있다
                    .andExpect(jsonPath("$.data.items[0].refCaseCount").value(12))
                    .andExpect(jsonPath("$.data.items[0].narrative").value("본넷 판금 사례 12건의 기준"));
        }

        /** 근거 한 줄이 깨졌다고 500 을 내면 사용자는 금액도 항목도 못 본다. */
        @Test
        @DisplayName("깨진 근거도 조회를 끊지 않는다")
        void malformedBasisDoesNotBreakQuery() throws Exception {
            long accidentId = insertAccident(memberId);
            long jobId = insertAnalysisJob(accidentId);
            long estimateId = insertEstimate(jobId, (short) 1, 800_000);
            insertItem(estimateId, jobId, "front_bumper", "프론트 범퍼",
                    (short) 1, "exchange", 300_000, "{\"fallbackStage\":\"UNKNOWN_STAGE\"}");

            mockMvc.perform(get("/api/estimates/{id}/basis", estimateId).session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].basisAvailable").value(false));
        }

        /** 화면이 견적 조회와 근거 조회를 짝지으므로 순서가 어긋나면 남의 부위 근거가 붙는다. */
        @Test
        @DisplayName("항목 순서가 견적 조회와 같다")
        void itemOrderMatchesDetail() throws Exception {
            long accidentId = insertAccident(memberId);
            long jobId = insertAnalysisJob(accidentId);
            long estimateId = insertEstimate(jobId, (short) 1, 800_000);
            insertItem(estimateId, jobId, "rear_bumper", "리어 범퍼", (short) 9, "repair", 100_000);
            insertItem(estimateId, jobId, "front_bumper", "프론트 범퍼", (short) 1, "exchange", 300_000);

            mockMvc.perform(get("/api/estimates/{id}/basis", estimateId).session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].partNameKo").value("프론트 범퍼"))
                    .andExpect(jsonPath("$.data.items[1].partNameKo").value("리어 범퍼"));
        }

        /** 근거에도 수리비 통계가 들어 있어 견적 조회와 같은 판정을 내려야 한다. */
        @Test
        @DisplayName("남의 견적 근거는 404 다")
        void othersBasisIsNotFound() throws Exception {
            long otherMemberId = insertMember("other-" + System.nanoTime());
            long otherAccidentId = insertAccident(otherMemberId);
            long otherJobId = insertAnalysisJob(otherAccidentId);
            long otherEstimateId = insertEstimate(otherJobId, (short) 1, 500_000);

            mockMvc.perform(get("/api/estimates/{id}/basis", otherEstimateId).session(session))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("없는 견적 근거도 같은 404 다")
        void missingBasisIsNotFound() throws Exception {
            mockMvc.perform(get("/api/estimates/{id}/basis", 999_999).session(session))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("로그인하지 않으면 401 이다")
        void rejectsAnonymous() throws Exception {
            mockMvc.perform(get("/api/estimates/{id}/basis", 1))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---------- 데이터 준비 ----------

    private long insertMember(String providerUserId) {
        jdbcTemplate.update("""
                insert into member (provider, provider_user_id, nickname, role, status)
                values ('KAKAO', ?, '견적조회', 'USER', 'ACTIVE')
                """, providerUserId);
        return jdbcTemplate.queryForObject(
                "select member_id from member where provider_user_id = ?", Long.class, providerUserId);
    }

    private long insertAccident(long ownerId) {
        String modelName = "아반떼" + System.nanoTime();
        jdbcTemplate.update("""
                insert into vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)
                values ('현대', ?, 'SEDAN', 'Compact', true)
                """, modelName);
        Long modelId = jdbcTemplate.queryForObject(
                "select model_id from vehicle_model where model_name = ?", Long.class, modelName);

        jdbcTemplate.update("""
                insert into vehicle (member_id, model_id, model_year) values (?, ?, 2020)
                """, ownerId, modelId);
        Long vehicleId = jdbcTemplate.queryForObject(
                "select max(vehicle_id) from vehicle where member_id = ?", Long.class, ownerId);

        jdbcTemplate.update("""
                insert into accident (vehicle_id, vehicle_input_type, snapshot_model_id,
                    snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                    snapshot_car_class, snapshot_model_year)
                values (?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Compact', 2020)
                """, vehicleId, modelId);
        return jdbcTemplate.queryForObject(
                "select max(accident_id) from accident where vehicle_id = ?", Long.class, vehicleId);
    }

    private long insertAnalysisJob(long accidentId) {
        jdbcTemplate.update("""
                insert into analysis_job (accident_id, status) values (?, 'COMPLETED')
                """, accidentId);
        return jdbcTemplate.queryForObject(
                "select max(job_id) from analysis_job where accident_id = ?", Long.class, accidentId);
    }

    private long insertEstimate(long jobId, short version, int median) {
        jdbcTemplate.update("""
                insert into estimate (job_id, version, is_estimable, labor_rate, total_hq,
                    total_min, total_median, total_max, ref_case_total, confidence_grade)
                values (?, ?, true, 52000, 3.20, ?, ?, ?, 12, 'HIGH')
                """, jobId, version, (int) (median * 0.8), median, (int) (median * 1.3));
        return jdbcTemplate.queryForObject(
                "select estimate_id from estimate where job_id = ? and version = ?",
                Long.class, jobId, version);
    }

    private long insertNonEstimable(long jobId, String reason) {
        jdbcTemplate.update("""
                insert into estimate (job_id, version, is_estimable, non_estimable_reason)
                values (?, 1, false, ?)
                """, jobId, reason);
        return jdbcTemplate.queryForObject(
                "select estimate_id from estimate where job_id = ? and version = 1",
                Long.class, jobId);
    }

    private void insertItem(long estimateId, long jobId, String partCode, String nameKo,
                            short displayOrder, String repairMethod, int median) {
        insertItem(estimateId, jobId, partCode, nameKo, displayOrder, repairMethod, median, "{}");
    }

    private void insertItem(long estimateId, long jobId, String partCode, String nameKo,
                            short displayOrder, String repairMethod, int median,
                            String refCondition) {
        jdbcTemplate.update("""
                insert into part_code (part_code, name_ko, layout_zone, display_order, is_active)
                values (?, ?, 'FRONT', ?, true)
                """, partCode, nameKo, displayOrder);

        jdbcTemplate.update("""
                insert into damaged_part (job_id, part_code, damage_type, repair_method, confidence)
                values (?, ?, 'Crushed', ?, 0.9200)
                """, jobId, partCode, repairMethod);
        Long damagedPartId = jdbcTemplate.queryForObject(
                "select damaged_part_id from damaged_part where job_id = ? and part_code = ?",
                Long.class, jobId, partCode);

        jdbcTemplate.update("""
                insert into estimate_item (estimate_id, damaged_part_id, repair_method, standard_hq,
                    part_cost_median, labor_cost_median, item_min, item_median, item_max,
                    ref_case_count, ref_condition, is_low_confidence)
                values (?, ?, ?, 2.30, null, ?, ?, ?, ?, 12, ? FORMAT JSON, false)
                """, estimateId, damagedPartId, repairMethod,
                median, (int) (median * 0.8), median, (int) (median * 1.3), refCondition);
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
                        .content("""
                                {"nickname":"보영","agreedTerms":["SERVICE","PRIVACY"]}
                                """))
                .andExpect(status().isCreated());

        return created;
    }
}
