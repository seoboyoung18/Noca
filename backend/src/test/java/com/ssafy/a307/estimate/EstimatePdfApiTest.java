package com.ssafy.a307.estimate;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.estimate.pdf.EstimatePdfKeys;
import com.ssafy.a307.estimate.pdf.EstimatePdfStoragePort;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 견적 PDF 요청·상태·다운로드 API (S15P21A307-341·342·391·393).
 *
 * <p>로컬 PostgreSQL 로 돈다. {@code estimate_report} 가 H2 스키마에 없고, 번호 발급 잠금
 * ({@code pg_advisory_xact_lock})과 진행 중 1건 제약({@code ux_er_inflight})이 PostgreSQL 기능이다.
 * 워커는 꺼져 있다 — 생성 자체는 처리기 단위 테스트와 렌더링 테스트가 본다.
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
@DisplayName("견적 PDF API")
class EstimatePdfApiTest {

    private static final String KAKAO_ID = "3877665547";

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

    /** 버킷 없는 환경에서 다운로드 서명을 고정한다. */
    @MockitoBean
    private EstimatePdfStoragePort pdfStorage;

    private MockHttpSession session;
    private long memberId;

    @BeforeEach
    void setUp() throws Exception {
        session = signedInSession();
        memberId = memberRepository.findByProviderAndProviderUserId(Provider.KAKAO, KAKAO_ID)
                .orElseThrow()
                .getMemberId();
    }

    @Test
    @DisplayName("요청하면 202 와 함께 QUEUED 리포트가 번호를 받는다")
    void requestIssuesQueuedReport() throws Exception {
        long estimateId = insertEstimate(memberId);

        mockMvc.perform(post("/api/estimates/{id}/pdf", estimateId).session(session))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.retryCount").value(0))
                .andExpect(jsonPath("$.data.reportNo").value(matchesPattern("R-\\d{8}-\\d{4}")))
                .andExpect(jsonPath("$.data.storageKey").doesNotExist());
    }

    /** 진행 중인 요청이 있는데 또 누르면 같은 PDF 를 두 번 만들게 된다. */
    @Test
    @DisplayName("진행 중인 요청이 있으면 409 다")
    void inFlightRequestIsConflict() throws Exception {
        long estimateId = insertEstimate(memberId);
        mockMvc.perform(post("/api/estimates/{id}/pdf", estimateId).session(session))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/estimates/{id}/pdf", estimateId).session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    /** 재생성은 새 번호를 받는다(견적 1 : 리포트 N). 번호는 그날 안에서 이어진다. */
    @Test
    @DisplayName("완료 뒤 다시 요청하면 그날 다음 번호가 발급된다")
    void regenerationGetsNextNumber() throws Exception {
        long estimateId = insertEstimate(memberId);
        String first = reportNoOf(mockMvc.perform(post("/api/estimates/{id}/pdf", estimateId).session(session))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        jdbcTemplate.update("update estimate_report set status = 'PROCESSING', retry_count = 1 where report_no = ?", first);
        jdbcTemplate.update("update estimate_report set status = 'COMPLETED', s3_key_pdf = 'estimate-reports/x.pdf',"
                + " completed_at = now() where report_no = ?", first);

        String second = reportNoOf(mockMvc.perform(post("/api/estimates/{id}/pdf", estimateId).session(session))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        assertThat(EstimatePdfKeys.nextSequence(first)).isEqualTo(EstimatePdfKeys.nextSequence(second) - 1);
        assertThat(second.substring(0, 11)).isEqualTo(first.substring(0, 11));
    }

    @Test
    @DisplayName("요청한 적이 없으면 상태는 null 이다")
    void statusIsNullBeforeRequest() throws Exception {
        long estimateId = insertEstimate(memberId);

        mockMvc.perform(get("/api/estimates/{id}/pdf", estimateId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("상태는 가장 최근 요청을 보여 준다")
    void statusShowsLatest() throws Exception {
        long estimateId = insertEstimate(memberId);
        mockMvc.perform(post("/api/estimates/{id}/pdf", estimateId).session(session))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/estimates/{id}/pdf", estimateId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.completedAt").value(nullValue()));
    }

    @Test
    @DisplayName("완료본이 없으면 다운로드는 409 다")
    void downloadWithoutCompletedIsConflict() throws Exception {
        long estimateId = insertEstimate(memberId);
        mockMvc.perform(post("/api/estimates/{id}/pdf", estimateId).session(session))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/estimates/{id}/pdf/download", estimateId).session(session))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("완료본이 있으면 5분짜리 서명 URL 로 302 한다")
    void downloadRedirects() throws Exception {
        long estimateId = insertEstimate(memberId);
        String reportNo = "T-" + System.nanoTime();
        jdbcTemplate.update("insert into estimate_report (estimate_id, report_no, status, s3_key_pdf, retry_count, completed_at)"
                + " values (?, ?, 'COMPLETED', 'estimate-reports/done.pdf', 1, now())", estimateId, reportNo);
        given(pdfStorage.createPresignedDownloadUrl(eq("estimate-reports/done.pdf"), eq(Duration.ofMinutes(5)),
                eq("예상견적_" + reportNo + ".pdf")))
                .willReturn(URI.create("https://bucket.example/report.pdf"));

        mockMvc.perform(get("/api/estimates/{id}/pdf/download", estimateId).session(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://bucket.example/report.pdf"));
    }

    @Test
    @DisplayName("남의 견적은 요청·상태 모두 404 다")
    void othersEstimateIsNotFound() throws Exception {
        long otherEstimate = insertEstimate(insertMember("pdf-other-" + System.nanoTime()));

        mockMvc.perform(post("/api/estimates/{id}/pdf", otherEstimate).session(session))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/estimates/{id}/pdf", otherEstimate).session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(post("/api/estimates/{id}/pdf", 1))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 데이터 준비 ----------

    private static String reportNoOf(String body) {
        return JsonPath.read(body, "$.data.reportNo");
    }

    private long insertEstimate(long ownerId) {
        String modelName = "아반떼" + System.nanoTime();
        jdbcTemplate.update(
                "insert into vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)"
                        + " values ('현대', ?, 'SEDAN', 'Compact', true)", modelName);
        Long modelId = jdbcTemplate.queryForObject(
                "select model_id from vehicle_model where model_name = ?", Long.class, modelName);
        jdbcTemplate.update("insert into vehicle (member_id, model_id, model_year) values (?, ?, 2020)", ownerId, modelId);
        Long vehicleId = jdbcTemplate.queryForObject(
                "select max(vehicle_id) from vehicle where member_id = ?", Long.class, ownerId);
        jdbcTemplate.update(
                "insert into accident (vehicle_id, vehicle_input_type, snapshot_model_id,"
                        + " snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,"
                        + " snapshot_car_class, snapshot_model_year)"
                        + " values (?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Compact', 2020)",
                vehicleId, modelId);
        Long accidentId = jdbcTemplate.queryForObject(
                "select max(accident_id) from accident where vehicle_id = ?", Long.class, vehicleId);
        jdbcTemplate.update("insert into analysis_job (accident_id, status) values (?, 'COMPLETED')", accidentId);
        Long jobId = jdbcTemplate.queryForObject(
                "select max(job_id) from analysis_job where accident_id = ?", Long.class, accidentId);
        jdbcTemplate.update(
                "insert into estimate (job_id, version, is_estimable, total_min, total_median, total_max)"
                        + " values (?, 1, true, 700000, 800000, 900000)", jobId);
        return jdbcTemplate.queryForObject("select estimate_id from estimate where job_id = ?", Long.class, jobId);
    }

    private long insertMember(String providerUserId) {
        jdbcTemplate.update(
                "insert into member (provider, provider_user_id, nickname, role, status)"
                        + " values ('KAKAO', ?, 'PDF', 'USER', 'ACTIVE')", providerUserId);
        return jdbcTemplate.queryForObject(
                "select member_id from member where provider_user_id = ?", Long.class, providerUserId);
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
