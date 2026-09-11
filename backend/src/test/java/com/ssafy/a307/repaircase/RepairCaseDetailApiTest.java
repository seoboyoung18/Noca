package com.ssafy.a307.repaircase;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.repaircase.image.RepairCaseImageStoragePort;
import com.ssafy.a307.repaircase.image.RepairCaseImageStoragePort.PresignedDownload;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유사 사례 상세 조회 API (S15P21A307-241).
 *
 * <p>{@link SimilarCaseApiTest} 와 같은 이유로 로컬 PostgreSQL 로 돈다 — {@code schema-h2.sql}
 * 에 {@code repair_case} 계열이 없다. 컨테이너가 없으면 건너뛰고, 심은 데이터는 롤백한다.
 *
 * <p><b>부위 코드를 테스트 전용으로 심는다.</b> 로컬 DB 에 시드가 들어 있으면 실제 부위의
 * {@code display_order} 가 테스트의 기대 순서를 흔든다.
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
@DisplayName("유사 사례 상세 조회")
class RepairCaseDetailApiTest {

    private static final String KAKAO_ID = "3877665545";
    private static final String PART_A = "ZZ_TEST_PART_A";
    private static final String PART_B = "ZZ_TEST_PART_B";

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
    private JdbcTemplate jdbcTemplate;

    /** 버킷 없는 환경에서도 URL 발급 성공·실패를 모두 고정하려고 저장소를 대신한다. */
    @MockitoBean
    private RepairCaseImageStoragePort imageStorage;

    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        session = signedInSession();
        insertPartCode(PART_A, "테스트 부위 A", 1);
        insertPartCode(PART_B, "테스트 부위 B", 2);
    }

    @Test
    @DisplayName("사례 정보와 부위별 수리 내역을 부위 표시 순서대로 준다")
    void returnsDetailGroupedByPart() throws Exception {
        long caseId = insertCase("AIHUB_AS", 1_234_000, null);
        // 일부러 B 를 먼저 넣는다. 응답 순서는 삽입 순서가 아니라 부위 표시 순서여야 한다
        insertWork(caseId, PART_B, "판금", "SHEET_METAL", 70_000);
        insertWork(caseId, PART_A, "교환", "EXCHANGE", 100_000);
        insertWork(caseId, PART_A, "도장", "COATING", 50_000);

        mockMvc.perform(get("/api/repair-cases/{id}", caseId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseId").value((int) caseId))
                .andExpect(jsonPath("$.data.manufacturer").value("현대"))
                .andExpect(jsonPath("$.data.modelName").value("아반떼"))
                .andExpect(jsonPath("$.data.carClass").value("Compact"))
                .andExpect(jsonPath("$.data.repairYear").value(2021))
                .andExpect(jsonPath("$.data.totalCost").value(1_234_000))
                .andExpect(jsonPath("$.data.parts.length()").value(2))
                .andExpect(jsonPath("$.data.parts[0].partCode").value(PART_A))
                .andExpect(jsonPath("$.data.parts[0].partNameKo").value("테스트 부위 A"))
                .andExpect(jsonPath("$.data.parts[0].partTotal").value(150_000))
                .andExpect(jsonPath("$.data.parts[0].items.length()").value(2))
                .andExpect(jsonPath("$.data.parts[0].items[0].workCode").value("EXCHANGE"))
                .andExpect(jsonPath("$.data.parts[0].items[0].workName").value("교환"))
                .andExpect(jsonPath("$.data.parts[0].items[0].hq").value(1.5))
                .andExpect(jsonPath("$.data.parts[0].items[0].laborCost").value(100_000))
                .andExpect(jsonPath("$.data.parts[1].partCode").value(PART_B))
                .andExpect(jsonPath("$.data.parts[1].partTotal").value(70_000))
                .andExpect(jsonPath("$.data.ancillaryItems.length()").value(0));
    }

    /** SC 의 지급액은 보험 지급 기준이다. 정비소 청구 기준(청구액)으로 맞춰야 AS 와 비교된다. */
    @Test
    @DisplayName("SC 사례의 총액은 청구액이다")
    void scTotalIsClaimAmount() throws Exception {
        long caseId = insertCase("AIHUB_SC", null, 500_000);

        mockMvc.perform(get("/api/repair-cases/{id}", caseId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCost").value(500_000));
    }

    /** 참고 정가는 정산에 들지 않는다. 유사 사례 목록(236)의 부위 금액과 같은 기준이다. */
    @Test
    @DisplayName("REFERENCE_PRICE 행은 내역에도 합계에도 넣지 않는다")
    void referencePriceIsExcluded() throws Exception {
        long caseId = insertCase("AIHUB_AS", 100_000, null);
        insertWork(caseId, PART_A, "교환", "EXCHANGE", 100_000);
        jdbcTemplate.update(
                "insert into repair_case_item (case_id, part_code, line_type, reference_part_price, item_total)"
                        + " values (?, ?, 'REFERENCE_PRICE', 900000, 900000)", caseId, PART_A);

        mockMvc.perform(get("/api/repair-cases/{id}", caseId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parts[0].items.length()").value(1))
                .andExpect(jsonPath("$.data.parts[0].partTotal").value(100_000));
    }

    /**
     * 불인정은 정비소가 청구했으나 보험이 인정하지 않은 금액이다. 총액이 정비소 청구 기준이라
     * 부위 합계에도 들어가야 한다. 원래 작업은 복구할 수 없어 "불인정" 으로 보인다.
     */
    @Test
    @DisplayName("불인정 행은 불인정으로 표시되고 부위 합계에 포함된다")
    void notApprovedIsShownAndSummed() throws Exception {
        long caseId = insertCase("AIHUB_SC", null, 130_000);
        insertWork(caseId, PART_A, "교환", "EXCHANGE", 100_000);
        jdbcTemplate.update(
                "insert into repair_case_item (case_id, part_code, line_type, assessment_status, item_total)"
                        + " values (?, ?, 'WORK', 'NOT_APPROVED', 30000)", caseId, PART_A);

        mockMvc.perform(get("/api/repair-cases/{id}", caseId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parts[0].items.length()").value(2))
                .andExpect(jsonPath("$.data.parts[0].items[1].workName").value("불인정"))
                .andExpect(jsonPath("$.data.parts[0].items[1].notApproved").value(true))
                .andExpect(jsonPath("$.data.parts[0].items[1].workCode").value(nullValue()))
                .andExpect(jsonPath("$.data.parts[0].partTotal").value(130_000));
    }

    /** 견인·구난은 부품이 아니라 부위에 묶을 수 없다. 따로 준다. */
    @Test
    @DisplayName("부대 비용은 부위 밖에 따로 준다")
    void ancillaryIsSeparated() throws Exception {
        long caseId = insertCase("AIHUB_SC", null, 140_000);
        insertWork(caseId, PART_A, "교환", "EXCHANGE", 100_000);
        jdbcTemplate.update(
                "insert into repair_case_item (case_id, raw_item_name, line_type, work_type, work_code, item_total)"
                        + " values (?, '견인비', 'ANCILLARY', '견인', 'TOWING', 40000)", caseId);

        mockMvc.perform(get("/api/repair-cases/{id}", caseId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parts.length()").value(1))
                .andExpect(jsonPath("$.data.ancillaryItems.length()").value(1))
                .andExpect(jsonPath("$.data.ancillaryItems[0].workCode").value("TOWING"))
                .andExpect(jsonPath("$.data.ancillaryItems[0].workName").value("견인"))
                .andExpect(jsonPath("$.data.ancillaryItems[0].itemTotal").value(40_000));
    }

    /**
     * 걸러진 이미지는 내보내지 않고, 서명이 실패한 이미지는 그 한 장만 빈다.
     * 내부 식별자(storage_key·external_ref)는 응답에 없어야 한다.
     */
    @Test
    @DisplayName("검색 가능한 이미지만 주고, 서명 실패는 그 이미지만 비운다")
    void imagesAreFilteredAndFailureIsIsolated() throws Exception {
        long caseId = insertCase("AIHUB_AS", 100_000, null);
        String okKey = insertImage(caseId, "ok", true);
        String failKey = insertImage(caseId, "fail", true);
        insertImage(caseId, "hidden", false);

        given(imageStorage.createPresignedDownloadUrl(eq(okKey), any())).willReturn(
                new PresignedDownload(URI.create("https://bucket.example/ok"), Instant.now().plusSeconds(600)));
        given(imageStorage.createPresignedDownloadUrl(eq(failKey), any()))
                .willThrow(new IllegalStateException("서명 실패"));

        mockMvc.perform(get("/api/repair-cases/{id}", caseId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images.length()").value(2))
                .andExpect(jsonPath("$.data.images[0].url").value("https://bucket.example/ok"))
                .andExpect(jsonPath("$.data.images[1].url").value(nullValue()))
                .andExpect(jsonPath("$.data.images[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.data.externalRef").doesNotExist());
    }

    /** 수리 항목이 아직 적재되지 않은 사례도 화면은 그려야 한다. 빈 목록이지 오류가 아니다. */
    @Test
    @DisplayName("수리 항목이 없으면 빈 목록이다")
    void emptyWhenNoItems() throws Exception {
        long caseId = insertCase("AIHUB_AS", 100_000, null);

        mockMvc.perform(get("/api/repair-cases/{id}", caseId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parts.length()").value(0))
                .andExpect(jsonPath("$.data.ancillaryItems.length()").value(0))
                .andExpect(jsonPath("$.data.images.length()").value(0));
    }

    /** 실사용자 사고에서 온 사례는 다른 사람의 데이터다. 공개 정책이 정해질 때까지 없는 것으로 본다. */
    @Test
    @DisplayName("SERVICE 사례는 404 다")
    void serviceCaseIsNotFound() throws Exception {
        long caseId = insertCase("SERVICE", 100_000, null);

        mockMvc.perform(get("/api/repair-cases/{id}", caseId).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 사례는 404 다")
    void missingCaseIsNotFound() throws Exception {
        mockMvc.perform(get("/api/repair-cases/{id}", 999_999_999).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/repair-cases/{id}", 1))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 데이터 준비 ----------

    private void insertPartCode(String partCode, String nameKo, int displayOrder) {
        jdbcTemplate.update(
                "insert into part_code (part_code, name_ko, layout_zone, display_order, is_active)"
                        + " values (?, ?, 'FRONT', ?, true) on conflict (part_code) do nothing",
                partCode, nameKo, displayOrder);
    }

    private long insertCase(String source, Integer totalCost, Integer claimAmount) {
        String ref = "TEST-" + System.nanoTime();
        jdbcTemplate.update(
                "insert into repair_case (source, external_ref, manufacturer, model_name, car_class,"
                        + " repair_year, total_cost, claim_amount)"
                        + " values (?, ?, '현대', '아반떼', 'Compact', 2021, ?, ?)",
                source, ref, totalCost, claimAmount);
        return jdbcTemplate.queryForObject(
                "select case_id from repair_case where external_ref = ?", Long.class, ref);
    }

    private void insertWork(long caseId, String partCode, String workType, String workCode, int itemTotal) {
        jdbcTemplate.update(
                "insert into repair_case_item (case_id, part_code, line_type, work_type, work_code, hq,"
                        + " labor_cost, item_total) values (?, ?, 'WORK', ?, ?, 1.50, ?, ?)",
                caseId, partCode, workType, workCode, itemTotal, itemTotal);
    }

    private String insertImage(long caseId, String name, boolean searchable) {
        String key = "repair-cases/" + caseId + "/images/" + name + "/original.jpg";
        jdbcTemplate.update(
                "insert into repair_case_image (case_id, source_image_ref, storage_key, is_searchable)"
                        + " values (?, ?, ?, ?)",
                caseId, "TEST-" + System.nanoTime() + ".jpg", key, searchable);
        return key;
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
