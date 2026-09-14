package com.ssafy.a307.repairchecklist;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairchecklist.domain.RepairChecklistFailure;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;
import com.ssafy.a307.repairchecklist.service.RepairChecklistRequestService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistStatusService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 접수({@code QUEUED}) → 워커 선점({@code PROCESSING}) → 완료/실패를 <b>실제 H2</b> 로 본다
 * (S15P21A307-460 · -461 · -463).
 *
 * <p><b>GMS 를 실제로 부르지 않는다.</b> {@link LlmChatPort} 를 테스트 대역으로 주입한다 —
 * {@code EstimateValidationWorkerTest} 의 {@code ScriptedOcrPort} 와 같은 방식이다. 운영에서는
 * {@code GmsKeyPresentCondition} 이 키가 있을 때만 진짜 구현을 만든다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 워커가 {@code REQUIRES_NEW} 로 도는데
 * 테스트가 트랜잭션을 쥐고 있으면 그 안쪽 트랜잭션이 시드 데이터를 보지 못한다. 대신 높은 ID
 * 대역을 쓰고 {@code @AfterEach} 에서 직접 지운다.
 *
 * <p>{@code initial-delay} 를 1시간으로 밀어 두어 <b>스케줄러가 스스로 돌지 않게</b> 한다.
 * 주기를 테스트가 직접 부르지 않으면 어느 시점에 무엇이 처리됐는지 단언할 수 없다.
 */
@SpringBootTest(properties = {
        "app.repair-checklist.enabled=true",
        "app.repair-checklist.poll-interval=PT1H",
        "app.repair-checklist.initial-delay=PT1H",
        "app.repair-checklist.batch-size=5",
        "app.repair-checklist.processing-timeout=PT10M"
})
@Import(RepairChecklistGenerationTest.ScriptedLlmConfig.class)
@DisplayName("체크리스트 생성 (LLM 대역)")
class RepairChecklistGenerationTest {

    private static final long MEMBER_ID = 96_201L;
    private static final long MODEL_ID = 96_202L;
    private static final long VEHICLE_ID = 96_203L;
    private static final long ACCIDENT_ID = 96_204L;

    /** 마스터에 들어 있는 공통 항목 수. 마이그레이션이 6이 아니면 멈추도록 해 둔 그 값이다. */
    private static final int COMMON_ITEM_COUNT = 6;

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
            if (failure != null) throw failure;
            return new ChatResult(nextJson, "test-model", Usage.unknown());
        }
    }

    @Autowired private RepairChecklistRequestService requestService;
    @Autowired private RepairChecklistStatusService statusService;
    @Autowired private RepairChecklistWorker worker;
    @Autowired private ScriptedLlmChatPort llm;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
        llm.reset();
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','checklist-worker','tester','USER','ACTIVE')", MEMBER_ID);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                VEHICLE_ID, MEMBER_ID, MODEL_ID);
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, ?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Mid-size', 2024)
                """, ACCIDENT_ID, VEHICLE_ID, MODEL_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("delete from repair_checklist_item where checklist_id in"
                + " (select checklist_id from repair_checklist where accident_id = ?)", ACCIDENT_ID);
        jdbc.update("delete from repair_checklist where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from member where member_id = ?", MEMBER_ID);
    }

    // ─────────────────────────────────────────────────────────── 성공 경로

    @Nested
    @DisplayName("생성 성공")
    class Success {

        @Test
        @DisplayName("접수는 QUEUED 로 즉시 돌아오고 그때는 LLM 을 부르지 않는다")
        void requestIsAcceptedWithoutCallingLlm() {
            RepairChecklistStatusResponse accepted = requestService.request(MEMBER_ID, ACCIDENT_ID);

            assertThat(accepted.status()).isEqualTo(RepairChecklistStatus.QUEUED);
            assertThat(accepted.checklistId()).isNotNull();
            assertThat(accepted.generationNo()).isEqualTo((short) 1);
            assertThat(llm.calls).isZero();
        }

        @Test
        @DisplayName("워커가 지나면 COMPLETED 가 되고 AI 항목 뒤에 공통 6종이 붙는다")
        void workerCompletesAndAppendsCommonItems() {
            long checklistId = accept();
            llm.nextJson = itemsJson("범퍼 교체 대신 판금으로 가능한지 확인", "도장 색상 맞춤 범위 확인");

            worker.pollOnce();

            assertThat(statusOf(checklistId)).isEqualTo("COMPLETED");
            assertThat(completedAtOf(checklistId)).isNotNull();
            assertThat(failureReasonOf(checklistId)).isNull();
            assertThat(llm.calls).isEqualTo(1);

            List<Map<String, Object>> items = itemsOf(checklistId);
            assertThat(items).hasSize(2 + COMMON_ITEM_COUNT);
            assertThat(items).extracting(row -> row.get("source"))
                    .containsExactly("AI", "AI", "COMMON", "COMMON", "COMMON", "COMMON", "COMMON", "COMMON");
        }

        /**
         * §3-5 의 고정. <b>AI 항목이 1..N, 공통 항목이 N+1..</b> 이고 번호가 이어진다.
         */
        @Test
        @DisplayName("display_order 가 AI 먼저, 공통이 뒤로 이어진다")
        void displayOrderPutsAiFirst() {
            long checklistId = accept();
            llm.nextJson = itemsJson("항목 하나", "항목 둘", "항목 셋");

            worker.pollOnce();

            assertThat(itemsOf(checklistId)).extracting(row -> ((Number) row.get("display_order")).intValue())
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        }

        /**
         * §3-5 의 핵심. <b>{@code content} 는 마스터 {@code message} 의 복사본</b>이고
         * {@code common_code} 가 채워져 있다({@code ck_rcli_link}). 코드가 문안을 갖지 않으므로
         * 기대값도 마스터에서 읽어 와 비교한다 — 여기에 한글 문자열을 적으면 사본이 하나 더 생긴다.
         */
        @Test
        @DisplayName("공통 항목은 마스터 문안의 복사본으로 들어가고 common_code 가 찬다")
        void commonItemsAreCopiedFromMaster() {
            long checklistId = accept();
            llm.nextJson = itemsJson("항목 하나");

            worker.pollOnce();

            List<Map<String, Object>> master = jdbc.queryForList(
                    "select code, message from repair_checklist_common_item"
                            + " where is_active = true order by display_order asc, code asc");
            assertThat(master).hasSize(COMMON_ITEM_COUNT);

            List<Map<String, Object>> common = itemsOf(checklistId).stream()
                    .filter(row -> "COMMON".equals(row.get("source")))
                    .toList();

            assertThat(common).extracting(row -> row.get("common_code"))
                    .containsExactlyElementsOf(master.stream().map(row -> row.get("code")).toList());
            assertThat(common).extracting(row -> row.get("content"))
                    .containsExactlyElementsOf(master.stream().map(row -> row.get("message")).toList());
        }

        @Test
        @DisplayName("AI 항목은 common_code 가 비어 있다 — ck_rcli_link 가 양방향이다")
        void aiItemsHaveNoCommonCode() {
            long checklistId = accept();
            llm.nextJson = itemsJson("항목 하나");

            worker.pollOnce();

            assertThat(itemsOf(checklistId).stream()
                    .filter(row -> "AI".equals(row.get("source")))
                    .map(row -> row.get("common_code")))
                    .containsOnlyNulls();
        }

        /**
         * §4-2. 진행률 열을 만들지 않았으므로 {@code COUNT} 두 번으로 나와야 한다
         * ({@code S15P21A307-483} 이 쓸 값이다). 여기서 그 구조만 확인한다.
         */
        @Test
        @DisplayName("진행률은 COUNT 두 번으로 계산할 수 있는 구조다")
        void progressIsCountable() {
            long checklistId = accept();
            llm.nextJson = itemsJson("항목 하나", "항목 둘");
            worker.pollOnce();

            Integer total = jdbc.queryForObject(
                    "select count(*) from repair_checklist_item where checklist_id = ?",
                    Integer.class, checklistId);
            Integer checked = jdbc.queryForObject(
                    "select count(*) from repair_checklist_item where checklist_id = ? and is_checked = true",
                    Integer.class, checklistId);

            assertThat(total).isEqualTo(2 + COMMON_ITEM_COUNT);
            assertThat(checked).isZero();
        }
    }

    // ─────────────────────────────────────────────────────────── 실패 경로

    @Nested
    @DisplayName("생성 실패")
    class Failure {

        /**
         * §3-3 의 고정. {@code S15P21A307-452}(AI 쪽 스키마 검증·재시도)가 미배정이라 백엔드가
         * 스스로 막아야 한다. <b>요청 자체는 이미 200 으로 끝났고</b> 실패는 상태로만 알린다.
         */
        @Test
        @DisplayName("LLM 이 깨진 JSON 을 주면 FAILED 로 남는다")
        void brokenJsonEndsAsFailed() {
            long checklistId = accept();
            llm.nextJson = "{\"items\": [";

            worker.pollOnce();

            assertThat(statusOf(checklistId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(checklistId))
                    .isEqualTo(RepairChecklistFailure.INVALID_RESPONSE.name());
            assertThat(itemsOf(checklistId)).isEmpty();
        }

        @Test
        @DisplayName("형식은 맞는데 쓸 항목이 없어도 FAILED 다 — 공통 6종만 남기지 않는다")
        void emptyItemsEndAsFailed() {
            long checklistId = accept();
            llm.nextJson = "{\"items\": [{\"content\": \"   \"}, {\"content\": 12}]}";

            worker.pollOnce();

            assertThat(statusOf(checklistId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(checklistId))
                    .isEqualTo(RepairChecklistFailure.INVALID_RESPONSE.name());
            assertThat(itemsOf(checklistId)).isEmpty();
        }

        @Test
        @DisplayName("호출 자체가 실패하면 파싱 실패와 다른 사유로 남는다")
        void callFailureIsADifferentReason() {
            long checklistId = accept();
            llm.failure = new IllegalStateException("boom");

            worker.pollOnce();

            assertThat(statusOf(checklistId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(checklistId))
                    .isEqualTo(RepairChecklistFailure.LLM_CALL_FAILED.name());
        }

        /**
         * §3-3 · §3-4. {@code failure_reason} 은 <b>분류 코드</b>이고 화면 문구가 아니다.
         * 한글이 한 글자라도 섞이면 서버가 문안을 쥐고 있다는 뜻이다.
         */
        @Test
        @DisplayName("failure_reason 에 한글이 없다 — 코드이지 문구가 아니다")
        void failureReasonCarriesNoKorean() {
            long checklistId = accept();
            llm.nextJson = "not json at all";

            worker.pollOnce();

            String reason = failureReasonOf(checklistId);
            assertThat(reason).isNotNull();
            assertThat(reason).matches("[A-Z_]+");
            assertThat(reason.chars().anyMatch(RepairChecklistGenerationTest::isHangul)).isFalse();
        }

        @Test
        @DisplayName("한 줄이 깨져도 나머지 항목은 살린다")
        void oneBadItemDoesNotDiscardTheRest() {
            long checklistId = accept();
            llm.nextJson = "{\"items\": [{\"content\": \"살아남는 항목\"}, {\"content\": \"\"},"
                    + " {\"note\": \"content 가 없다\"}]}";

            worker.pollOnce();

            assertThat(statusOf(checklistId)).isEqualTo("COMPLETED");
            assertThat(itemsOf(checklistId)).hasSize(1 + COMMON_ITEM_COUNT);
        }
    }

    // ─────────────────────────────────────────────────────────── 재요청

    @Nested
    @DisplayName("이미 있을 때의 재요청 (uk_rcl_accident)")
    class Rerequest {

        @Test
        @DisplayName("진행 중이면 같은 체크리스트를 그대로 돌려준다 — 행이 늘지 않는다")
        void inFlightRequestIsIdempotent() {
            long first = accept();

            RepairChecklistStatusResponse again = requestService.request(MEMBER_ID, ACCIDENT_ID);

            assertThat(again.checklistId()).isEqualTo(first);
            assertThat(again.status()).isEqualTo(RepairChecklistStatus.QUEUED);
            assertThat(countChecklists()).isEqualTo(1);
        }

        /**
         * 완성된 것을 갈아엎는 것은 재생성({@code S15P21A307-486})이고 이 스토리가 아니다.
         * 조용히 덮으면 사용자가 체크해 둔 항목이 사라진다.
         */
        @Test
        @DisplayName("이미 완성돼 있으면 409 다 — 재생성은 -486 의 몫이다")
        void completedChecklistRejectsAnotherRequest() {
            long checklistId = accept();
            llm.nextJson = itemsJson("항목 하나");
            worker.pollOnce();
            assertThat(statusOf(checklistId)).isEqualTo("COMPLETED");

            assertThatThrownBy(() -> requestService.request(MEMBER_ID, ACCIDENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONFLICT);
        }

        /**
         * 실패한 건은 결과물이 없으므로 "만들어 달라" 는 요청이 아직 유효하다. 이 경로가 없으면
         * 한 번 실패한 사고는 영영 체크리스트를 가질 수 없다.
         *
         * <p><b>재생성이 아니다</b> — {@code generation_no} 가 그대로 1이고
         * {@code regenerated_at} 도 비어 있어야 한다({@code ck_rcl_regen}).
         */
        @Test
        @DisplayName("실패한 건은 다시 요청하면 큐로 돌아가고 다음 주기에 완성된다")
        void failedChecklistCanBeRetried() {
            long checklistId = accept();
            llm.failure = new IllegalStateException("boom");
            worker.pollOnce();
            assertThat(statusOf(checklistId)).isEqualTo("FAILED");

            RepairChecklistStatusResponse requeued = requestService.request(MEMBER_ID, ACCIDENT_ID);

            assertThat(requeued.checklistId()).isEqualTo(checklistId);
            assertThat(requeued.status()).isEqualTo(RepairChecklistStatus.QUEUED);
            assertThat(requeued.generationNo()).isEqualTo((short) 1);
            assertThat(requeued.regeneratedAt()).isNull();
            assertThat(failureReasonOf(checklistId)).isNull();
            assertThat(completedAtOf(checklistId)).isNull();

            llm.failure = null;
            llm.nextJson = itemsJson("이번에는 성공");
            worker.pollOnce();

            assertThat(statusOf(checklistId)).isEqualTo("COMPLETED");
            assertThat(countChecklists()).isEqualTo(1);
            assertThat(itemsOf(checklistId)).hasSize(1 + COMMON_ITEM_COUNT);
        }

        @Test
        @DisplayName("재시도하면 앞 시도가 남긴 항목이 섞이지 않는다")
        void retryReplacesPreviousItems() {
            long checklistId = accept();
            llm.nextJson = itemsJson("첫 시도 항목");
            worker.pollOnce();

            // COMPLETED 는 재요청이 막히므로, 실패로 되돌려 재시도 경로를 연다.
            jdbc.update("update repair_checklist set status = 'FAILED' where checklist_id = ?", checklistId);
            requestService.request(MEMBER_ID, ACCIDENT_ID);
            llm.nextJson = itemsJson("두 번째 시도 항목");
            worker.pollOnce();

            List<Map<String, Object>> ai = itemsOf(checklistId).stream()
                    .filter(row -> "AI".equals(row.get("source")))
                    .toList();
            assertThat(ai).extracting(row -> row.get("content")).containsExactly("두 번째 시도 항목");
            assertThat(itemsOf(checklistId)).hasSize(1 + COMMON_ITEM_COUNT);
        }
    }

    // ─────────────────────────────────────────────────────────── 상태 표현

    /**
     * §3-4. {@code -461} 제목은 "대기·완료·실패" 3종이지만 <b>네 값을 그대로 내보낸다.</b>
     * 합치는 것은 화면 결정이고, 서버가 미리 접으면 FE 가 "생성 중" 을 보여 줄 수 없다.
     */
    @Test
    @DisplayName("상태를 QUEUED·PROCESSING·COMPLETED·FAILED 네 값 그대로 내보낸다")
    void statusIsNotFoldedIntoThree() {
        long checklistId = accept();
        assertThat(statusService.status(MEMBER_ID, ACCIDENT_ID).status())
                .isEqualTo(RepairChecklistStatus.QUEUED);

        jdbc.update("update repair_checklist set status = 'PROCESSING' where checklist_id = ?", checklistId);
        assertThat(statusService.status(MEMBER_ID, ACCIDENT_ID).status())
                .isEqualTo(RepairChecklistStatus.PROCESSING);

        llm.nextJson = itemsJson("항목 하나");
        jdbc.update("update repair_checklist set status = 'QUEUED' where checklist_id = ?", checklistId);
        worker.pollOnce();
        assertThat(statusService.status(MEMBER_ID, ACCIDENT_ID).status())
                .isEqualTo(RepairChecklistStatus.COMPLETED);

        assertThat(RepairChecklistStatus.values()).containsExactly(
                RepairChecklistStatus.QUEUED, RepairChecklistStatus.PROCESSING,
                RepairChecklistStatus.COMPLETED, RepairChecklistStatus.FAILED);
    }

    @Test
    @DisplayName("지시문이 공통 6종 문안을 담지 않는다 — 그 문안은 DB 가 정본이다")
    void instructionDoesNotCarryCommonMessages() {
        accept();
        llm.nextJson = itemsJson("항목 하나");
        worker.pollOnce();

        List<String> masterMessages = jdbc.queryForList(
                "select message from repair_checklist_common_item", String.class);
        assertThat(llm.lastInstruction).isNotNull();
        assertThat(masterMessages).isNotEmpty()
                .allSatisfy(message -> assertThat(llm.lastInstruction).doesNotContain(message));
    }

    // ─────────────────────────────────────────────────────────── 도우미

    private static boolean isHangul(int codePoint) {
        return (codePoint >= 0xAC00 && codePoint <= 0xD7A3) || (codePoint >= 0x3130 && codePoint <= 0x318F);
    }

    private long accept() {
        return requestService.request(MEMBER_ID, ACCIDENT_ID).checklistId();
    }

    private static String itemsJson(String... contents) {
        StringBuilder json = new StringBuilder("{\"items\": [");
        for (int i = 0; i < contents.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"content\": \"").append(contents[i]).append("\"}");
        }
        return json.append("]}").toString();
    }

    private String statusOf(long checklistId) {
        return jdbc.queryForObject("select status from repair_checklist where checklist_id = ?",
                String.class, checklistId);
    }

    private String failureReasonOf(long checklistId) {
        return jdbc.queryForObject("select failure_reason from repair_checklist where checklist_id = ?",
                String.class, checklistId);
    }

    private Object completedAtOf(long checklistId) {
        return jdbc.queryForObject("select completed_at from repair_checklist where checklist_id = ?",
                Object.class, checklistId);
    }

    private Integer countChecklists() {
        return jdbc.queryForObject("select count(*) from repair_checklist where accident_id = ?",
                Integer.class, ACCIDENT_ID);
    }

    private List<Map<String, Object>> itemsOf(long checklistId) {
        return jdbc.queryForList(
                "select source, common_code, content, display_order, is_checked"
                        + " from repair_checklist_item where checklist_id = ?"
                        + " order by display_order asc, item_id asc", checklistId);
    }
}
