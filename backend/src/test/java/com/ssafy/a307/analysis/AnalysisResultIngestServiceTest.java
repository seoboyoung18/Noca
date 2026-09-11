package com.ssafy.a307.analysis;

import com.ssafy.a307.analysis.contract.AnalysisDocument;
import com.ssafy.a307.analysis.contract.AnalysisDocumentReader;
import com.ssafy.a307.analysis.repository.DamagedPartRepository;
import com.ssafy.a307.analysis.service.AnalysisIngestResult;
import com.ssafy.a307.analysis.service.AnalysisResultIngestService;
import com.ssafy.a307.analysis.service.DeferralReason;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 분석 결과 JSON 적재 — {@code S15P21A307-218}.
 *
 * <p>계약과 DDL 이 어긋나는 자리에서 <b>근거 없는 값을 만들지 않는다</b>는 것을 고정한다.
 * 통과시키려고 단언을 약화시키지 않았다 — 보류된 건은 "몇 건이 왜 보류됐는지" 까지 본다.
 */
@SpringBootTest
@DisplayName("분석 결과 JSON 적재")
class AnalysisResultIngestServiceTest {

    private static final long OWNER_ID = 98_201L;
    private static final long STRANGER_ID = 98_202L;

    @Autowired private AnalysisResultIngestService ingestService;
    @Autowired private AnalysisDocumentReader reader;
    @Autowired private DamagedPartRepository damagedPartRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long jobId;
    private long ownerAccidentId;

    @BeforeEach
    void setUp() {
        insertMember(OWNER_ID, "ingest-owner", "적재주인");
        insertMember(STRANGER_ID, "ingest-stranger", "남");

        jdbcTemplate.update("""
                insert into vehicle_model (model_id, manufacturer, model_name, vehicle_type, car_class)
                values (98201, '현대', '적재테스트차', 'SEDAN', 'Mid-size')
                """);
        jdbcTemplate.update("""
                insert into vehicle (vehicle_id, member_id, model_id, model_year)
                values (98201, ?, 98201, 2021)
                """, OWNER_ID);
        jdbcTemplate.update("""
                insert into accident (accident_id, vehicle_id, vehicle_input_type, snapshot_model_id,
                                      snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                                      snapshot_car_class, snapshot_model_year)
                values (98201, 98201, 'REGISTERED', 98201, '현대', '적재테스트차', 'SEDAN', 'Mid-size', 2021)
                """);
        ownerAccidentId = 98_201L;

        jdbcTemplate.update("""
                insert into analysis_job (job_id, accident_id, status)
                values (98201, 98201, 'PROCESSING')
                """);
        jobId = 98_201L;

        insertPartCode("FRONT_BUMPER", "앞 범퍼", "FRONT", 1, true);
        insertPartCode("BONNET", "보닛", "FRONT", 3, true);
        insertPartCode("HEAD_LIGHT_L", "헤드램프(좌)", "SIDE_L", 25, true);
        insertPartCode("REAR_BUMPER", "뒤 범퍼", "REAR", 2, true);
        insertPartCode("ROOF", "루프", "TOP", 5, false);        // 비활성
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from damaged_part where job_id = ?", jobId);
        jdbcTemplate.update("delete from analysis_job where job_id = ?", jobId);
        jdbcTemplate.update("delete from accident where accident_id = ?", ownerAccidentId);
        jdbcTemplate.update("delete from vehicle where vehicle_id = 98201");
        jdbcTemplate.update("delete from vehicle_model where model_id = 98201");
        jdbcTemplate.update("delete from member where member_id in (?, ?)", OWNER_ID, STRANGER_ID);
        jdbcTemplate.update("""
                delete from part_code
                where part_code in ('FRONT_BUMPER','BONNET','HEAD_LIGHT_L','REAR_BUMPER','ROOF')
                """);
    }

    // ── 계약 샘플 한 장 ────────────────────────────────────────────────────

    @Test
    @DisplayName("후보가 하나인 검출만 적재하고, 나머지는 이유와 함께 보류한다")
    void ingestsOnlyUnambiguousDetections() throws IOException {
        AnalysisIngestResult result = ingestService.ingest(OWNER_ID, jobId, fixtureDocument());

        assertThat(result.detectionCount()).isEqualTo(3);
        assertThat(result.persistedCount()).isEqualTo(1);
        assertThat(result.deferred())
                .extracting(AnalysisIngestResult.Deferred::partCode,
                        AnalysisIngestResult.Deferred::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("BONNET",
                                DeferralReason.AMBIGUOUS_WORK_CANDIDATE),
                        org.assertj.core.groups.Tuple.tuple("HEAD_LIGHT_L",
                                DeferralReason.MISSING_CONFIDENCE));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select part_code, damage_type, repair_method, severity_score, confidence "
                        + "from damaged_part where job_id = ?", jobId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("PART_CODE", "FRONT_BUMPER");
    }

    @Test
    @DisplayName("표기를 DDL 쪽으로 바꿔 넣는다 — SCRATCHED 는 'Scratched', COATING 은 'coating'")
    void convertsNotationToColumnValues() throws IOException {
        ingestService.ingest(OWNER_ID, jobId, fixtureDocument());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select damage_type, repair_method from damaged_part where job_id = ? and part_code = ?",
                jobId, "FRONT_BUMPER");

        assertThat(row.get("DAMAGE_TYPE")).isEqualTo("Scratched");
        assertThat(row.get("REPAIR_METHOD")).isEqualTo("coating");
    }

    @Test
    @DisplayName("confidence 는 part·damage 중 작은 값을 넣는다")
    void storesTheWeakerConfidence() throws IOException {
        ingestService.ingest(OWNER_ID, jobId, fixtureDocument());

        java.math.BigDecimal confidence = jdbcTemplate.queryForObject(
                "select confidence from damaged_part where job_id = ? and part_code = ?",
                java.math.BigDecimal.class, jobId, "FRONT_BUMPER");

        assertThat(confidence).isEqualByComparingTo("0.8700");
    }

    @Test
    @DisplayName("심각도를 채우지 않는다 — 계약에 심각도가 없다")
    void leavesSeverityNull() throws IOException {
        ingestService.ingest(OWNER_ID, jobId, fixtureDocument());

        Object severity = jdbcTemplate.queryForMap(
                "select severity_score from damaged_part where job_id = ? and part_code = ?",
                jobId, "FRONT_BUMPER").get("SEVERITY_SCORE");

        assertThat(severity).isNull();
    }

    @Test
    @DisplayName("작업 상태와 model_version 을 건드리지 않는다 — 작업의 수명은 -155 의 몫이다")
    void doesNotTouchJobLifecycle() throws IOException {
        ingestService.ingest(OWNER_ID, jobId, fixtureDocument());

        Map<String, Object> job = jdbcTemplate.queryForMap(
                "select status, model_version, finished_at from analysis_job where job_id = ?", jobId);

        assertThat(job.get("STATUS")).isEqualTo("PROCESSING");
        assertThat(job.get("MODEL_VERSION")).isNull();
        assertThat(job.get("FINISHED_AT")).isNull();
    }

    // ── 빈 결과 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detections 가 비어도 적재는 성공한다 — 손상 없는 이미지다")
    void emptyDetectionsSucceed() {
        AnalysisIngestResult result = ingestService.ingest(OWNER_ID, jobId, document("[]"));

        assertThat(result.detectionCount()).isZero();
        assertThat(result.persistedCount()).isZero();
        assertThat(result.deferredCount()).isZero();
        assertThat(damagedPartRepository.findByJob_JobIdOrderByPartCodeAsc(jobId)).isEmpty();
    }

    // ── part_code 마스터 ───────────────────────────────────────────────────

    @Test
    @DisplayName("마스터에 없는 부품 코드는 보류한다 — FK 가 INSERT 를 거절하기 전에 막는다")
    void unknownPartCodeIsDeferred() {
        AnalysisIngestResult result = ingestService.ingest(OWNER_ID, jobId,
                document(detection("SIDE_MIRROR_L", "MIRROR", "LEFT", "SCRATCHED", "COATING", "0.9", "0.9")));

        assertThat(result.persistedCount()).isZero();
        assertThat(result.deferred()).singleElement()
                .extracting(AnalysisIngestResult.Deferred::reason)
                .isEqualTo(DeferralReason.UNKNOWN_PART_CODE);
    }

    @Test
    @DisplayName("비활성 부품 코드는 보류한다 — 비활성 코드는 신규 입력에서 빠진다")
    void inactivePartCodeIsDeferred() {
        AnalysisIngestResult result = ingestService.ingest(OWNER_ID, jobId,
                document(detection("ROOF", "BODY_PANEL", "CENTER", "SCRATCHED", "COATING", "0.9", "0.9")));

        assertThat(result.persistedCount()).isZero();
        assertThat(result.deferred()).singleElement()
                .extracting(AnalysisIngestResult.Deferred::reason)
                .isEqualTo(DeferralReason.INACTIVE_PART_CODE);
    }

    // ── uk_dp (job_id, part_code) ──────────────────────────────────────────

    @Test
    @DisplayName("같은 부품이 같은 판정으로 두 번 오면 한 행만 남는다")
    void identicalDuplicateCollapsesToOneRow() {
        String one = detection("REAR_BUMPER", "BUMPER", "CENTER", "SCRATCHED", "COATING", "0.9", "0.8");
        String two = detection("REAR_BUMPER", "BUMPER", "CENTER", "SCRATCHED", "COATING", "0.7", "0.6");

        AnalysisIngestResult result = ingestService.ingest(OWNER_ID, jobId, document(one + "," + two));

        assertThat(result.persistedCount()).isEqualTo(1);
        assertThat(result.deferredCount()).isZero();
    }

    @Test
    @DisplayName("같은 부품이 서로 다른 판정으로 오면 양쪽 다 보류한다 — 순서가 결과를 바꾸면 안 된다")
    void conflictingDuplicateDefersBothSides() {
        String one = detection("REAR_BUMPER", "BUMPER", "CENTER", "SCRATCHED", "COATING", "0.9", "0.9");
        String two = detection("REAR_BUMPER", "BUMPER", "CENTER", "BREAKAGE", "EXCHANGE", "0.9", "0.9");

        AnalysisIngestResult result = ingestService.ingest(OWNER_ID, jobId, document(one + "," + two));

        assertThat(result.persistedCount()).isZero();
        assertThat(result.deferred())
                .extracting(AnalysisIngestResult.Deferred::reason)
                .containsOnly(DeferralReason.CONFLICTING_DUPLICATE_PART);
        assertThat(damagedPartRepository.findByJob_JobIdOrderByPartCodeAsc(jobId)).isEmpty();
    }

    @Test
    @DisplayName("먼저 들어온 문서가 이미 넣은 부품은 다음 문서에서 보류한다 — uk_dp 를 위반하지 않는다")
    void alreadyPersistedPartIsDeferredOnSecondDocument() {
        String detection = detection("REAR_BUMPER", "BUMPER", "CENTER", "SCRATCHED", "COATING", "0.9", "0.9");
        ingestService.ingest(OWNER_ID, jobId, document(detection));

        AnalysisIngestResult second = ingestService.ingest(OWNER_ID, jobId, document(detection));

        assertThat(second.persistedCount()).isZero();
        assertThat(second.deferred()).singleElement()
                .extracting(AnalysisIngestResult.Deferred::reason)
                .isEqualTo(DeferralReason.CONFLICTING_DUPLICATE_PART);
        assertThat(damagedPartRepository.findByJob_JobIdOrderByPartCodeAsc(jobId)).hasSize(1);
    }

    // ── 소유권 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("남의 작업은 404 다 — 403 은 그 작업이 존재한다는 사실을 알려준다")
    void strangerGetsNotFound() {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> ingestService.ingest(STRANGER_ID, jobId, document("[]")));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("없는 작업도 404 다")
    void missingJobIsNotFound() {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> ingestService.ingest(OWNER_ID, 7_654_321L, document("[]")));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("남의 작업에는 한 행도 적재되지 않는다")
    void strangerPersistsNothing() {
        String detection = detection("REAR_BUMPER", "BUMPER", "CENTER", "SCRATCHED", "COATING", "0.9", "0.9");

        catchThrowableOfType(BusinessException.class,
                () -> ingestService.ingest(STRANGER_ID, jobId, document(detection)));

        assertThat(damagedPartRepository.findByJob_JobIdOrderByPartCodeAsc(jobId)).isEmpty();
    }

    // ── image.id ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("image.id 를 accident_image.image_id 로 쓰지 않는다 — 적재 결과가 그 값에 좌우되지 않는다")
    void pipelineImageIdIsNotAnAccidentImageId() {
        String detection = detection("REAR_BUMPER", "BUMPER", "CENTER", "SCRATCHED", "COATING", "0.9", "0.9");

        AnalysisIngestResult withText = ingestService.ingest(OWNER_ID, jobId,
                documentWithImageId("\"어떤-파이프라인-식별자\"", detection));

        jdbcTemplate.update("delete from damaged_part where job_id = ?", jobId);

        AnalysisIngestResult withNumber = ingestService.ingest(OWNER_ID, jobId,
                documentWithImageId("999999999", detection));

        assertThat(withText.persistedCount()).isEqualTo(withNumber.persistedCount()).isEqualTo(1);
    }

    // ── 픽스처 ─────────────────────────────────────────────────────────────

    private AnalysisDocument fixtureDocument() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/analysis/sample-analysis-document.json")) {
            assertThat(in).as("픽스처를 찾을 수 없다").isNotNull();
            return reader.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private AnalysisDocument document(String detectionsJson) {
        return documentWithImageId("\"img-test\"", detectionsJson.startsWith("[")
                ? detectionsJson.substring(1, detectionsJson.length() - 1)
                : detectionsJson);
    }

    private AnalysisDocument documentWithImageId(String imageIdJson, String detectionsBody) {
        return reader.read("""
                { "schema_version": "1.1.0",
                  "image": { "id": %s, "width": 1600, "height": 1200 },
                  "detections": [%s] }
                """.formatted(imageIdJson, detectionsBody));
    }

    private static String detection(String partCode, String group, String side,
                                    String damageCode, String workCode,
                                    String partConfidence, String damageConfidence) {
        return """
                { "part": { "code": "%s", "name_en": "%s", "name_ko": "%s",
                            "group": "%s", "side": "%s", "raw_label": "%s" },
                  "damage": { "code": "%s", "name_en": "%s", "name_ko": "손상",
                              "raw_label": "%s" },
                  "geometry": {
                    "coordinate_system": "PIXEL_XY_TOP_LEFT", "bbox_format": "XYWH",
                    "bbox": { "x": 10, "y": 10, "width": 40, "height": 30 },
                    "segmentation": { "format": "POLYGONS",
                      "polygons": [[{ "x": 10, "y": 10 }, { "x": 50, "y": 10 }, { "x": 50, "y": 40 }]],
                      "area_px": 600, "area_ratio": 0.01 } },
                  "confidence": { "part": %s, "damage": %s },
                  "work_candidates": [{ "code": "%s", "name_en": "work", "name_ko": "작업" }],
                  "work_decision": "CANDIDATE",
                  "work_rule_version": "1.0.0" }
                """.formatted(partCode, partCode, partCode, group, side, partCode.toLowerCase(),
                damageCode, damageCode, damageCode.toLowerCase(),
                partConfidence, damageConfidence, workCode);
    }

    private void insertMember(long id, String providerUserId, String nickname) {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', ?, ?, 'USER', 'ACTIVE')
                """, id, providerUserId, nickname);
    }

    private void insertPartCode(String code, String nameKo, String zone, int order, boolean active) {
        jdbcTemplate.update("""
                insert into part_code (part_code, name_ko, layout_zone, display_order, is_active, code_scope)
                values (?, ?, ?, ?, ?, 'AI_LABEL')
                """, code, nameKo, zone, order, active);
    }
}
