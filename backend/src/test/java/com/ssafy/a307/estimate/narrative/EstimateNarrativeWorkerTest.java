package com.ssafy.a307.estimate.narrative;

import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.entity.ConfidenceGrade;
import com.ssafy.a307.estimate.entity.Estimate;
import com.ssafy.a307.estimate.service.EstimateQueryService;
import com.ssafy.a307.estimate.service.EstimateVersioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 견적을 저장하면 요약이 큐에 들어가고, 워커가 그것을 문장으로 만든다 (S15P21A307-537).
 *
 * <p><b>GMS 를 실제로 부르지 않는다.</b> {@link LlmChatPort} 를 대역으로 주입한다
 * ({@code RepairChecklistGenerationTest.ScriptedLlmConfig} 와 같은 방식).
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 워커가 {@code REQUIRES_NEW} 로 돌아
 * 테스트가 트랜잭션을 쥐고 있으면 그 안쪽이 시드 데이터를 보지 못한다. 대신 만든 행을
 * {@code @AfterEach} 에서 직접 지운다.
 *
 * <p>{@code initial-delay} 를 1시간으로 밀어 <b>스케줄러가 스스로 돌지 않게</b> 한다 — 주기를
 * 테스트가 직접 부르지 않으면 어느 시점에 무엇이 처리됐는지 단언할 수 없다.
 */
@SpringBootTest(properties = {
        "app.estimate-narrative.enabled=true",
        "app.estimate-narrative.poll-interval=PT1H",
        "app.estimate-narrative.initial-delay=PT1H",
        "app.estimate-narrative.batch-size=5",
        "app.estimate-narrative.processing-timeout=PT10M"
})
@Import(EstimateNarrativeWorkerTest.ScriptedLlmConfig.class)
@DisplayName("견적 요약 생성 (LLM 대역)")
class EstimateNarrativeWorkerTest {

    private static final String PART_CODE = "ZZ_NARRATIVE_PART";

    @TestConfiguration
    static class ScriptedLlmConfig {

        @Bean
        ScriptedLlmChatPort scriptedLlmChatPort() {
            return new ScriptedLlmChatPort();
        }
    }

    /** 다음 호출이 무엇을 낼지 테스트가 정해 주는 LLM 대역. */
    static class ScriptedLlmChatPort implements LlmChatPort {

        String nextJson;
        RuntimeException failure;
        int calls;
        String lastInstruction;

        void reset() {
            nextJson = null;
            failure = null;
            calls = 0;
            lastInstruction = null;
        }

        @Override
        public ChatResult complete(ChatRequest request) {
            calls++;
            lastInstruction = request.instruction();
            if (failure != null) {
                throw failure;
            }
            return new ChatResult(nextJson, "test-model", Usage.unknown());
        }
    }

    @Autowired private EstimateVersioningService versioningService;
    @Autowired private EstimateQueryService queryService;
    @Autowired private EstimateNarrativeWorker worker;
    @Autowired private ScriptedLlmChatPort llm;
    @Autowired private JdbcTemplate jdbc;

    private long memberId;
    private long vehicleId;
    private long modelId;
    private long accidentId;
    private long jobId;

    @BeforeEach
    void setUp() {
        llm.reset();
        String tag = "narrative-" + System.nanoTime();
        jdbc.update("insert into member(provider,provider_user_id,nickname,role,status)"
                + " values('KAKAO',?,'요약',  'USER','ACTIVE')", tag);
        memberId = jdbc.queryForObject(
                "select member_id from member where provider_user_id = ?", Long.class, tag);

        jdbc.update("insert into vehicle_model(manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values('현대',?,'SEDAN','Compact',true)", tag);
        modelId = jdbc.queryForObject(
                "select model_id from vehicle_model where model_name = ?", Long.class, tag);

        jdbc.update("insert into vehicle(member_id,model_id,model_year) values(?,?,2020)", memberId, modelId);
        vehicleId = jdbc.queryForObject(
                "select max(vehicle_id) from vehicle where member_id = ?", Long.class, memberId);

        jdbc.update("""
                insert into accident(vehicle_id, vehicle_input_type, snapshot_model_id,
                    snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                    snapshot_car_class, snapshot_model_year)
                values(?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Compact', 2020)
                """, vehicleId, modelId);
        accidentId = jdbc.queryForObject(
                "select max(accident_id) from accident where vehicle_id = ?", Long.class, vehicleId);

        jdbc.update("insert into analysis_job(accident_id,status) values(?,'COMPLETED')", accidentId);
        jobId = jdbc.queryForObject(
                "select max(job_id) from analysis_job where accident_id = ?", Long.class, accidentId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("""
                delete from estimate_narrative where estimate_id in
                    (select estimate_id from estimate where job_id = ?)
                """, jobId);
        jdbc.update("""
                delete from estimate_item where estimate_id in
                    (select estimate_id from estimate where job_id = ?)
                """, jobId);
        jdbc.update("delete from estimate where job_id = ?", jobId);
        jdbc.update("delete from damaged_part where job_id = ?", jobId);
        jdbc.update("delete from analysis_job where accident_id = ?", accidentId);
        jdbc.update("delete from accident where accident_id = ?", accidentId);
        jdbc.update("delete from vehicle where vehicle_id = ?", vehicleId);
        jdbc.update("delete from vehicle_model where model_id = ?", modelId);
        jdbc.update("delete from member where member_id = ?", memberId);
        jdbc.update("delete from part_code where part_code = ?", PART_CODE);
    }

    @Test
    @DisplayName("견적을 저장하면 요약이 QUEUED 로 접수된다 — 그때 LLM 을 부르지 않는다")
    void savingEstimateQueuesNarrative() {
        long estimateId = nonEstimable();

        assertThat(statusOf(estimateId)).isEqualTo("QUEUED");
        assertThat(llm.calls).isZero();
    }

    @Test
    @DisplayName("워커가 지나면 COMPLETED 가 되고 문장이 저장된다")
    void workerStoresNarrative() {
        long estimateId = nonEstimable();
        llm.nextJson = """
                {
                  "summary": "비슷한 수리 사례가 부족해 금액을 내지 못했습니다.",
                  "cautions": ["정비소 견적서를 받아 비교해 보세요."],
                  "basisNotes": []
                }
                """;

        worker.pollOnce();

        assertThat(statusOf(estimateId)).isEqualTo("COMPLETED");
        assertThat(llm.calls).isEqualTo(1);
        assertThat(contentOf(estimateId))
                .contains("금액을 내지 못했습니다")
                .contains("정비소 견적서를 받아 비교해 보세요");
    }

    /** 지시문에는 확정된 값만 들어간다. 산정 불가면 금액 대신 사유가 실린다. */
    @Test
    @DisplayName("지시문에 산정 불가 사유가 실린다 — 0원이라고 쓰지 않는다")
    void instructionCarriesReasonNotZero() {
        nonEstimable();
        llm.nextJson = """
                {"summary":"요약","cautions":[],"basisNotes":[]}
                """;

        worker.pollOnce();

        assertThat(llm.lastInstruction)
                .contains("산정하지 못함")
                .contains("INSUFFICIENT_CASES")
                .doesNotContain("0원");
    }

    @Test
    @DisplayName("LLM 이 실패하면 FAILED 로 남고 사유가 적힌다 — 견적은 그대로다")
    void failureIsRecorded() {
        long estimateId = nonEstimable();
        llm.failure = new IllegalStateException("호출 실패");

        worker.pollOnce();

        assertThat(statusOf(estimateId)).isEqualTo("FAILED");
        assertThat(failureReasonOf(estimateId))
                .isEqualTo(EstimateNarrativeFailure.LLM_CALL_FAILED.name());
        assertThat(jdbc.queryForObject("select count(*) from estimate where estimate_id = ?",
                Integer.class, estimateId)).isEqualTo(1);
    }

    /**
     * 다듬어진 문장은 근거 조회에 그대로 나온다. 규칙이 만든 문장을 <b>대체</b>하는 것이 이
     * 기능의 계약이다.
     */
    @Test
    @DisplayName("다듬어진 근거 문장이 근거 조회에 나온다")
    void polishedBasisReplacesRuleSentence() {
        long estimateId = estimable();
        String ruleSentence = queryService.basis(estimateId, memberId).items().getFirst().narrative();
        assertThat(ruleSentence).isNotBlank();

        llm.nextJson = """
                {
                  "summary": "앞 범퍼 교환이 중심인 사고입니다.",
                  "cautions": [],
                  "basisNotes": [{"partCode":"%s","text":"비슷한 사례를 모아 가운데 값으로 계산했습니다."}]
                }
                """.formatted(PART_CODE);

        worker.pollOnce();

        EstimateBasisResponse basis = queryService.basis(estimateId, memberId);
        assertThat(basis.items().getFirst().narrative())
                .isEqualTo("비슷한 사례를 모아 가운데 값으로 계산했습니다.");
    }

    /** 생성 전에는 규칙 문장이 그대로 나간다 — 근거 섹션은 요약보다 먼저 있던 것이다. */
    @Test
    @DisplayName("요약을 만들기 전에는 규칙이 만든 근거 문장이 그대로 나온다")
    void ruleSentenceStandsBeforeGeneration() {
        long estimateId = estimable();

        assertThat(queryService.basis(estimateId, memberId).items().getFirst().narrative())
                .isNotBlank();
        assertThat(statusOf(estimateId)).isEqualTo("QUEUED");
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private long nonEstimable() {
        return versioningService.appendNonEstimable(jobId, "INSUFFICIENT_CASES", null).getEstimateId();
    }

    /** 근거 문장이 만들어지려면 참조 사례 수와 조건 스냅샷이 있어야 한다. */
    private long estimable() {
        Estimate estimate = versioningService.append(jobId,
                new Estimate.Amounts(52_000, new java.math.BigDecimal("3.20"),
                        700_000, 800_000, 900_000, 12),
                ConfidenceGrade.HIGH, null);

        jdbc.update("insert into part_code(part_code,name_ko,layout_zone,display_order,is_active)"
                + " values(?,'앞 범퍼','FRONT',1,true)", PART_CODE);
        jdbc.update("insert into damaged_part(job_id,part_code,damage_type,repair_method,confidence)"
                + " values(?,?,'Crushed','exchange',0.9200)", jobId, PART_CODE);
        Long damagedPartId = jdbc.queryForObject(
                "select damaged_part_id from damaged_part where job_id = ? and part_code = ?",
                Long.class, jobId, PART_CODE);

        jdbc.update("""
                insert into estimate_item(estimate_id, damaged_part_id, repair_method, standard_hq,
                    part_cost_median, labor_cost_median, item_min, item_median, item_max,
                    ref_case_count, ref_condition, is_low_confidence)
                values(?, ?, 'exchange', 2.30, 500000, 92000, 640000, 800000, 1040000, 12,
                       ? FORMAT JSON, false)
                """, estimate.getEstimateId(), damagedPartId,
                "{\"carClass\":\"Compact\",\"yearFrom\":2018,\"yearTo\":2022}");
        return estimate.getEstimateId();
    }

    private String statusOf(long estimateId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status from estimate_narrative where estimate_id = ?", estimateId);
        return rows.isEmpty() ? null : (String) rows.getFirst().get("status");
    }

    private String contentOf(long estimateId) {
        return jdbc.queryForObject(
                "select cast(content as varchar) from estimate_narrative where estimate_id = ?",
                String.class, estimateId);
    }

    private String failureReasonOf(long estimateId) {
        return jdbc.queryForObject(
                "select failure_reason from estimate_narrative where estimate_id = ?",
                String.class, estimateId);
    }
}
