package com.ssafy.a307.estimate;

import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.accident.image.AccidentImageStoragePort.PresignedDownload;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationService;
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
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사고 분석 견적 리포트 API (S15P21A307-337·338).
 *
 * <p>로컬 PostgreSQL 로 돈다. {@code schema-h2.sql} 에 {@code analysis_image_result} 가 없고,
 * 검증 연결 규칙({@code COALESCE} 서브쿼리)을 정본 스키마 위에서 확인해야 한다.
 * 컨테이너가 없으면 건너뛰고, 심은 데이터는 롤백한다.
 *
 * <p>AI 분석 콜백(S15P21A307-157)이 아직 없어 분석 결과·견적·검증을 SQL 로 직접 심는다.
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
@DisplayName("사고 분석 견적 리포트")
class EstimateReportApiTest {

    private static final String KAKAO_ID = "3877665546";
    private static final String PART = "ZZ_REPORT_PART";

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

    /** 버킷 없는 환경에서도 오버레이 URL 발급 성공·실패를 고정하려고 저장소를 대신한다. */
    @MockitoBean
    private AccidentImageStoragePort imageStorage;

    private MockHttpSession session;
    private long memberId;

    @BeforeEach
    void setUp() throws Exception {
        session = signedInSession();
        memberId = memberRepository.findByProviderAndProviderUserId(Provider.KAKAO, KAKAO_ID)
                .orElseThrow()
                .getMemberId();
        jdbcTemplate.update(
                "insert into part_code (part_code, name_ko, layout_zone, display_order, is_active)"
                        + " values (?, '리포트 부위', 'FRONT', 1, true) on conflict (part_code) do nothing", PART);
    }

    @Test
    @DisplayName("차량·사고·견적·근거·고지 문구를 한 번에 준다")
    void assemblesAllSections() throws Exception {
        Fixture f = estimatedFixture(memberId);

        mockMvc.perform(get("/api/estimates/{id}/report", f.estimateId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicle.manufacturer").value("현대"))
                .andExpect(jsonPath("$.data.vehicle.modelName").value("아반떼"))
                .andExpect(jsonPath("$.data.vehicle.carClass").value("Compact"))
                .andExpect(jsonPath("$.data.vehicle.modelYear").value(2020))
                .andExpect(jsonPath("$.data.accident.accidentId").value((int) f.accidentId()))
                .andExpect(jsonPath("$.data.estimate.estimateId").value((int) f.estimateId()))
                .andExpect(jsonPath("$.data.estimate.totalMedian").value(800_000))
                .andExpect(jsonPath("$.data.estimate.items.length()").value(1))
                .andExpect(jsonPath("$.data.basis.estimateId").value((int) f.estimateId()))
                .andExpect(jsonPath("$.data.basis.items.length()").value(1))
                .andExpect(jsonPath("$.data.legalNotice").value(EstimateValidationService.LEGAL_NOTICE))
                .andExpect(jsonPath("$.data.generatedAt").exists());
    }

    /**
     * 리포트·견적 PDF 에 체크리스트를 싣지 않기로 했으므로(S15P21A307-532) 체크리스트 안내 고지도
     * 싣지 않는다(S15P21A307-533). 활성 문구를 일부러 심어 둔다 — 문구가 없으면 예전 코드도
     * {@code null} 을 내보내 이 테스트가 차이를 가리지 못한다.
     */
    @Test
    @DisplayName("체크리스트 안내 고지는 리포트에 싣지 않는다 — 활성 문구가 있어도")
    void guidanceNoticeIsNotInReport() throws Exception {
        String guidance = "본 체크리스트와 질문은 리포트 테스트용 안내 문구입니다";
        jdbcTemplate.update("""
                insert into estimate_notice (code, message, display_order, is_active)
                values ('GUIDANCE_LIMIT_NOTICE', ?, 90, true)
                on conflict (code) do update set message = excluded.message, is_active = true
                """, guidance);
        Fixture f = estimatedFixture(memberId);

        mockMvc.perform(get("/api/estimates/{id}/report", f.estimateId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guidanceNotice").doesNotExist())
                .andExpect(content().string(not(containsString(guidance))));
    }

    /**
     * 분석에서 제외된 사진은 견적 근거가 아니라 넣지 않는다. 오버레이가 없거나 서명이 실패한
     * 사진은 남기되 URL 만 비운다 — 화면·PDF 가 "분석 이미지 없음" 을 그린다.
     */
    @Test
    @DisplayName("분석 이미지는 제외 사진을 빼고, 오버레이가 없거나 서명이 실패하면 URL 만 비운다")
    void analyzedImagesWithOverlayFallbacks() throws Exception {
        Fixture f = estimatedFixture(memberId);
        String okKey = "accidents/" + f.accidentId() + "/images/1/overlay.jpg";
        String failKey = "accidents/" + f.accidentId() + "/images/2/overlay.jpg";
        long withOverlay = insertAnalyzedImage(f, okKey, false);
        long signFails = insertAnalyzedImage(f, failKey, false);
        long noOverlay = insertAnalyzedImage(f, null, false);
        insertAnalyzedImage(f, "accidents/" + f.accidentId() + "/images/9/overlay.jpg", true);

        given(imageStorage.createPresignedDownloadUrl(eq(okKey), any())).willReturn(
                new PresignedDownload(URI.create("https://bucket.example/overlay"), Instant.now().plusSeconds(600)));
        given(imageStorage.createPresignedDownloadUrl(eq(failKey), any()))
                .willThrow(new IllegalStateException("서명 실패"));

        mockMvc.perform(get("/api/estimates/{id}/report", f.estimateId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images.length()").value(3))
                .andExpect(jsonPath("$.data.images[0].imageId").value((int) withOverlay))
                .andExpect(jsonPath("$.data.images[0].overlayUrl").value("https://bucket.example/overlay"))
                .andExpect(jsonPath("$.data.images[1].imageId").value((int) signFails))
                .andExpect(jsonPath("$.data.images[1].overlayUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.images[2].imageId").value((int) noOverlay))
                .andExpect(jsonPath("$.data.images[2].overlayUrl").value(nullValue()))
                // 그릴 사진은 imageUrl 이다 (S15P21A307-560). 오버레이가 있으면 그것, 없으면 축소본 —
                // 이 픽스처엔 축소본이 없어 오버레이가 없는 두 장은 비어 있다
                .andExpect(jsonPath("$.data.images[0].imageUrl").value("https://bucket.example/overlay"))
                .andExpect(jsonPath("$.data.images[0].overlay").value(true))
                .andExpect(jsonPath("$.data.images[2].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.images[2].overlay").value(false))
                .andExpect(jsonPath("$.data.images[0].resizedKey").doesNotExist())
                .andExpect(jsonPath("$.data.images[0].overlayKey").doesNotExist());
    }

    @Test
    @DisplayName("연결된 견적서 검증이 없으면 검증 섹션이 null 이다")
    void validationSectionIsNullWithoutValidation() throws Exception {
        Fixture f = estimatedFixture(memberId);

        mockMvc.perform(get("/api/estimates/{id}/report", f.estimateId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validation").value(nullValue()));
    }

    @Test
    @DisplayName("이 견적에 연결된 완료 검증이 있으면 검증 섹션이 채워진다")
    void validationLinkedByEstimateId() throws Exception {
        Fixture f = estimatedFixture(memberId);
        long validationId = insertCompletedValidation(f.accidentId(), f.estimateId());

        mockMvc.perform(get("/api/estimates/{id}/report", f.estimateId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validation.validationId").value((int) validationId))
                .andExpect(jsonPath("$.data.validation.grade").value("APPROPRIATE"))
                .andExpect(jsonPath("$.data.validation.claimedTotal").value(900_000))
                .andExpect(jsonPath("$.data.validation.legalNotice").value(EstimateValidationService.LEGAL_NOTICE));
    }

    /** 견적을 고르지 않고 올린 검증은 그 사고의 최신 견적에 붙은 것으로 본다. */
    @Test
    @DisplayName("견적을 지정하지 않은 검증은 최신 견적의 리포트에 붙는다")
    void validationWithoutEstimateAttachesToLatest() throws Exception {
        Fixture f = estimatedFixture(memberId);
        long validationId = insertCompletedValidation(f.accidentId(), null);

        mockMvc.perform(get("/api/estimates/{id}/report", f.estimateId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validation.validationId").value((int) validationId));
    }

    /** 재산정으로 새 버전이 생기면 견적을 지정하지 않은 검증은 새 버전 쪽으로 옮겨 간다. */
    @Test
    @DisplayName("견적을 지정하지 않은 검증은 옛 버전 견적의 리포트에는 붙지 않는다")
    void validationWithoutEstimateSkipsOlderVersion() throws Exception {
        Fixture f = estimatedFixture(memberId);
        insertEstimate(f.jobId(), 2, true);
        insertCompletedValidation(f.accidentId(), null);

        mockMvc.perform(get("/api/estimates/{id}/report", f.estimateId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validation").value(nullValue()));
    }

    @Test
    @DisplayName("다른 견적에 연결된 검증은 붙지 않는다")
    void validationOfAnotherEstimateIsNotAttached() throws Exception {
        Fixture f = estimatedFixture(memberId);
        long newer = insertEstimate(f.jobId(), 2, true);
        insertCompletedValidation(f.accidentId(), newer);

        mockMvc.perform(get("/api/estimates/{id}/report", f.estimateId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validation").value(nullValue()));
    }

    /** 산정 불가 견적은 금액 대신 사유를 싣는다. 항목이 없어도 필수 항목 누락이 아니다. */
    @Test
    @DisplayName("산정 불가 견적도 리포트가 나오고 사유가 실린다")
    void nonEstimableEstimateStillReports() throws Exception {
        long accidentId = insertAccident(memberId);
        long jobId = insertAnalysisJob(accidentId);
        long estimateId = insertEstimate(jobId, 1, false);

        mockMvc.perform(get("/api/estimates/{id}/report", estimateId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimate.estimable").value(false))
                .andExpect(jsonPath("$.data.estimate.nonEstimableReason").value("참조 사례 부족"))
                .andExpect(jsonPath("$.data.estimate.totalMedian").value(nullValue()))
                .andExpect(jsonPath("$.data.estimate.items.length()").value(0));
    }

    /** 리포트에는 수리비와 검증 결과가 들어 있다. 견적 조회와 같은 판정을 내려야 한다. */
    @Test
    @DisplayName("남의 견적은 404 다")
    void othersEstimateIsNotFound() throws Exception {
        long otherMemberId = insertMember("report-other-" + System.nanoTime());
        Fixture other = estimatedFixture(otherMemberId);

        mockMvc.perform(get("/api/estimates/{id}/report", other.estimateId()).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 견적은 404 다")
    void missingEstimateIsNotFound() throws Exception {
        mockMvc.perform(get("/api/estimates/{id}/report", 999_999_999).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/estimates/{id}/report", 1))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 데이터 준비 ----------

    private record Fixture(long accidentId, long jobId, long estimateId) {
    }

    private Fixture estimatedFixture(long ownerId) {
        long accidentId = insertAccident(ownerId);
        long jobId = insertAnalysisJob(accidentId);
        long estimateId = insertEstimate(jobId, 1, true);

        jdbcTemplate.update(
                "insert into damaged_part (job_id, part_code, damage_type, repair_method, confidence)"
                        + " values (?, ?, 'Crushed', 'exchange', 0.9200)", jobId, PART);
        Long damagedPartId = jdbcTemplate.queryForObject(
                "select damaged_part_id from damaged_part where job_id = ?", Long.class, jobId);
        jdbcTemplate.update(
                "insert into estimate_item (estimate_id, damaged_part_id, repair_method,"
                        + " item_min, item_median, item_max, ref_case_count, ref_condition)"
                        + " values (?, ?, 'exchange', 700000, 800000, 900000, 12, cast('{}' as jsonb))",
                estimateId, damagedPartId);

        return new Fixture(accidentId, jobId, estimateId);
    }

    private long insertEstimate(long jobId, int version, boolean estimable) {
        if (estimable) {
            jdbcTemplate.update(
                    "insert into estimate (job_id, version, is_estimable, total_min, total_median, total_max,"
                            + " confidence_grade) values (?, ?, true, 700000, 800000, 900000, 'HIGH')",
                    jobId, version);
        } else {
            jdbcTemplate.update(
                    "insert into estimate (job_id, version, is_estimable, non_estimable_reason)"
                            + " values (?, ?, false, '참조 사례 부족')", jobId, version);
        }
        return jdbcTemplate.queryForObject(
                "select estimate_id from estimate where job_id = ? and version = ?",
                Long.class, jobId, version);
    }

    private long insertAnalyzedImage(Fixture f, String overlayKey, boolean excluded) {
        String filename = "report-" + System.nanoTime() + ".jpg";
        jdbcTemplate.update(
                "insert into accident_image (accident_id, original_filename, angle_code)"
                        + " values (?, ?, 'FRONT')", f.accidentId(), filename);
        Long imageId = jdbcTemplate.queryForObject(
                "select image_id from accident_image where original_filename = ?", Long.class, filename);
        jdbcTemplate.update(
                "insert into analysis_image_result (job_id, image_id, s3_key_overlay, is_excluded, exclusion_reason)"
                        + " values (?, ?, ?, ?, ?)",
                f.jobId(), imageId, overlayKey, excluded, excluded ? "NOT_VEHICLE" : null);
        return imageId;
    }

    private long insertCompletedValidation(long accidentId, Long estimateId) {
        jdbcTemplate.update(
                "insert into estimate_validation (member_id, accident_id, estimate_id, file_type, status,"
                        + " claimed_total, llm_grade, llm_summary, completed_at)"
                        + " values (?, ?, ?, 'MANUAL', 'COMPLETED', 900000, 'APPROPRIATE', '적정 범위입니다.', now())",
                memberId, accidentId, estimateId);
        return jdbcTemplate.queryForObject(
                "select max(validation_id) from estimate_validation where accident_id = ?", Long.class, accidentId);
    }

    private long insertMember(String providerUserId) {
        jdbcTemplate.update(
                "insert into member (provider, provider_user_id, nickname, role, status)"
                        + " values ('KAKAO', ?, '리포트', 'USER', 'ACTIVE')", providerUserId);
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
                "insert into vehicle (member_id, model_id, model_year) values (?, ?, 2020)", ownerId, modelId);
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

    private long insertAnalysisJob(long accidentId) {
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
