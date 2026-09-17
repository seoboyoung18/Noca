package com.ssafy.a307.repairquestion;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairquestion.domain.RepairQuestionFailure;
import com.ssafy.a307.repairquestion.dto.RepairQuestionItemResponse;
import com.ssafy.a307.repairquestion.dto.RepairQuestionResponse;
import com.ssafy.a307.repairquestion.entity.RepairQuestionItemSource;
import com.ssafy.a307.repairquestion.entity.RepairQuestionStatus;
import com.ssafy.a307.repairquestion.service.RepairQuestionQueryService;
import com.ssafy.a307.repairquestion.service.RepairQuestionRequestService;
import com.ssafy.a307.repairquestion.service.RepairQuestionWorker;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 접수({@code QUEUED}) → 워커 선점({@code PROCESSING}) → 완료/실패를 <b>실제 H2</b> 로 본다
 * (S15P21A307-476 · -477).
 *
 * <p><b>GMS 를 실제로 부르지 않는다.</b> {@link LlmChatPort} 를 테스트 대역으로 주입한다 —
 * {@code RepairChecklistGenerationTest} 와 같은 방식이다. 운영에서는
 * {@code GmsKeyPresentCondition} 이 키가 있을 때만 진짜 구현을 만든다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 워커가 {@code REQUIRES_NEW} 로 도는데
 * 테스트가 트랜잭션을 쥐고 있으면 그 안쪽 트랜잭션이 시드 데이터를 보지 못한다. 대신 높은 ID
 * 대역을 쓰고 {@code @AfterEach} 에서 직접 지운다.
 *
 * <p>{@code initial-delay} 를 1시간으로 밀어 두어 <b>스케줄러가 스스로 돌지 않게</b> 한다.
 */
@SpringBootTest(properties = {
        "app.repair-question.enabled=true",
        "app.repair-question.poll-interval=PT1H",
        "app.repair-question.initial-delay=PT1H",
        "app.repair-question.batch-size=5",
        "app.repair-question.processing-timeout=PT10M"
})
@Import(RepairQuestionGenerationTest.ScriptedLlmConfig.class)
@DisplayName("정비소 확인 질문 생성 (LLM 대역)")
class RepairQuestionGenerationTest {

    private static final long MEMBER_ID = 96_301L;
    private static final long OTHER_MEMBER_ID = 96_302L;
    private static final long MODEL_ID = 96_303L;
    private static final long VEHICLE_ID = 96_304L;
    private static final long ACCIDENT_ID = 96_305L;
    private static final long JOB_ID = 96_306L;
    private static final long ESTIMATE_ID = 96_307L;

    private static final String PART_CODE = "REAR_DOOR_TEST";
    private static final String PART_NAME = "리어 도어";

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

    @Autowired private RepairQuestionRequestService requestService;
    @Autowired private RepairQuestionQueryService queryService;
    @Autowired private RepairQuestionWorker worker;
    @Autowired private ScriptedLlmChatPort llm;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
        llm.reset();
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','question-worker','tester','USER','ACTIVE')", MEMBER_ID);
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','question-stranger','stranger','USER','ACTIVE')", OTHER_MEMBER_ID);
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
        jdbc.update("insert into part_code(part_code,name_ko,layout_zone,display_order,is_active)"
                + " values(?,?,'REAR',900,true)", PART_CODE, PART_NAME);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("delete from repair_question_item where question_id in"
                + " (select question_id from repair_question where accident_id = ?)", ACCIDENT_ID);
        jdbc.update("delete from repair_question where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from estimate where job_id = ?", JOB_ID);
        jdbc.update("delete from damaged_part where job_id = ?", JOB_ID);
        jdbc.update("delete from analysis_job where job_id = ?", JOB_ID);
        jdbc.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from part_code where part_code = ?", PART_CODE);
        jdbc.update("delete from member where member_id in (?,?)", MEMBER_ID, OTHER_MEMBER_ID);
    }

    /** 분석 1건 + 손상 부위 1건 + 예상 견적 1건. 지시문에 실릴 재료다. */
    private void seedAnalysis() {
        jdbc.update("insert into analysis_job(job_id,accident_id,status) values(?,?,'COMPLETED')",
                JOB_ID, ACCIDENT_ID);
        jdbc.update("insert into damaged_part(job_id,part_code,damage_type,repair_method,confidence)"
                + " values(?,?,'Scratched','exchange',0.9)", JOB_ID, PART_CODE);
        jdbc.update("insert into estimate(estimate_id,job_id,version,is_estimable,confidence_grade,"
                + "total_median) values(?,?,1,true,'LOW',1234000)", ESTIMATE_ID, JOB_ID);
    }

    // ─────────────────────────────────────────────────────────── 성공 경로

    @Nested
    @DisplayName("생성 성공")
    class Success {

        @Test
        @DisplayName("접수는 QUEUED 로 즉시 돌아오고 그때는 LLM 을 부르지 않는다")
        void requestIsAcceptedWithoutCallingLlm() {
            RepairQuestionResponse accepted = requestService.request(MEMBER_ID, ACCIDENT_ID);

            assertThat(accepted.status()).isEqualTo(RepairQuestionStatus.QUEUED);
            assertThat(accepted.questionId()).isNotNull();
            assertThat(accepted.generationNo()).isEqualTo((short) 1);
            assertThat(accepted.questions()).isEmpty();
            assertThat(llm.calls).isZero();
        }

        @Test
        @DisplayName("워커가 지나면 COMPLETED 가 되고 display_order 가 1부터 이어진다")
        void workerCompletesInOrder() {
            long questionId = accept();
            llm.nextJson = questionsJson(
                    q("판금으로 수리가 가능한가요?", null),
                    q("순정 외 부품 사용 시 금액 차이는 얼마인가요?", null),
                    q("작업 기간은 얼마나 걸리나요?", null));

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("COMPLETED");
            assertThat(completedAtOf(questionId)).isNotNull();
            assertThat(failureReasonOf(questionId)).isNull();
            assertThat(llm.calls).isEqualTo(1);
            assertThat(itemsOf(questionId))
                    .extracting(row -> ((Number) row.get("display_order")).intValue())
                    .containsExactly(1, 2, 3);
        }

        /**
         * prompt65 §4-1 의 고정. <b>부품에 안 매인 질문</b>이 저장돼야 한다 —
         * {@code S15P21A307-476} 본문 두 번째 예시가 그 모양이고, 근거 네 열이 모두 {@code NULL}
         * 인 행을 {@code ck_rqi_part} 가 허용한다.
         */
        @Test
        @DisplayName("부품 없는 질문이 저장된다 — 근거 네 열이 전부 비어 있다 (ck_rqi_part)")
        void questionWithoutPartIsStored() {
            seedAnalysis();
            long questionId = accept();
            llm.nextJson = questionsJson(q("순정 외 부품 사용 시 금액 차이는 얼마인가요?", null));

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("COMPLETED");
            Map<String, Object> row = itemsOf(questionId).getFirst();
            assertThat(row.get("part_code")).isNull();
            assertThat(row.get("snapshot_part_name")).isNull();
            assertThat(row.get("damage_type")).isNull();
            assertThat(row.get("repair_method")).isNull();
        }

        /**
         * {@code strict: true} 스키마는 {@code partCode} 를 <b>required + nullable</b> 로 둔다
         * (prompt86). 그래서 부품에 안 매인 질문은 키가 빠진 모양이 아니라
         * {@code "partCode": null} 로 온다. 파서는 두 모양을 모두 받아야 한다 — 위 테스트가
         * 키가 빠진 모양을, 이 테스트가 명시적 {@code null} 을 고정한다.
         */
        @Test
        @DisplayName("partCode 가 명시적 null 로 와도 부품 없는 질문으로 저장된다")
        void explicitNullPartCodeIsStoredWithoutPart() {
            seedAnalysis();
            long questionId = accept();
            llm.nextJson = """
                    {"questions": [{"content": "작업 기간은 얼마나 걸리나요?", "partCode": null}]}
                    """;

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("COMPLETED");
            Map<String, Object> row = itemsOf(questionId).getFirst();
            assertThat(row.get("content")).isEqualTo("작업 기간은 얼마나 걸리나요?");
            assertThat(row.get("part_code")).isNull();
            assertThat(row.get("snapshot_part_name")).isNull();
        }

        /**
         * prompt65 §2-2 · §4-1 의 고정. {@code snapshot_part_name} 은 <b>생성 시점
         * {@code part_code.name_ko} 사본</b>이고, 판정 두 값은 <b>{@code damaged_part} 복사</b>다.
         */
        @Test
        @DisplayName("부품 있는 질문은 이름 사본과 판정이 함께 찬다 (ck_rqi_basis)")
        void groundedQuestionCopiesPartNameAndJudgement() {
            seedAnalysis();
            long questionId = accept();
            llm.nextJson = questionsJson(q("리어 도어는 교환 없이 수리가 가능한가요?", PART_CODE));

            worker.pollOnce();

            Map<String, Object> row = itemsOf(questionId).getFirst();
            assertThat(row.get("part_code")).isEqualTo(PART_CODE);
            assertThat(row.get("snapshot_part_name")).isEqualTo(PART_NAME);
            assertThat(row.get("damage_type")).isEqualTo("Scratched");
            assertThat(row.get("repair_method")).isEqualTo("exchange");
        }

        /**
         * 마스터 문구가 바뀌어도 이미 만들어진 질문의 근거 표시는 <b>그대로 남는다</b> —
         * {@code accident} 의 {@code snapshot_*} 과 같은 방식이다.
         */
        @Test
        @DisplayName("부품 이름이 바뀌어도 저장된 사본은 따라 바뀌지 않는다")
        void partNameSnapshotSurvivesMasterRename() {
            seedAnalysis();
            long questionId = accept();
            llm.nextJson = questionsJson(q("리어 도어는 교환 없이 수리가 가능한가요?", PART_CODE));
            worker.pollOnce();

            jdbc.update("update part_code set name_ko = '뒷문' where part_code = ?", PART_CODE);

            assertThat(itemsOf(questionId).getFirst().get("snapshot_part_name")).isEqualTo(PART_NAME);
        }

        /**
         * 판정은 LLM 이 정하지 않는다. 응답 스키마에 자리가 없으므로 지어낸 값은 들어올 곳이 없고,
         * 저장되는 값은 {@code damaged_part} 의 것이다.
         */
        @Test
        @DisplayName("LLM 이 판정을 지어내 보내도 담기지 않는다 — 판정은 damaged_part 복사다")
        void llmCannotInventJudgement() {
            seedAnalysis();
            long questionId = accept();
            llm.nextJson = """
                    {"questions": [{"content": "리어 도어 확인 부탁드립니다?", "partCode": "%s",
                      "damageType": "Crushed", "repairMethod": "coating"}]}
                    """.formatted(PART_CODE);

            worker.pollOnce();

            Map<String, Object> row = itemsOf(questionId).getFirst();
            assertThat(row.get("damage_type")).isEqualTo("Scratched");
            assertThat(row.get("repair_method")).isEqualTo("exchange");
        }

        @Test
        @DisplayName("모르는 부품 코드가 오면 질문은 살리고 근거만 비운다")
        void unknownPartCodeLosesOnlyTheBasis() {
            seedAnalysis();
            long questionId = accept();
            llm.nextJson = questionsJson(q("본넷은 교환이 필요한가요?", "NO_SUCH_PART"));

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("COMPLETED");
            Map<String, Object> row = itemsOf(questionId).getFirst();
            assertThat(row.get("content")).isEqualTo("본넷은 교환이 필요한가요?");
            assertThat(row.get("part_code")).isNull();
            assertThat(row.get("snapshot_part_name")).isNull();
        }

        @Test
        @DisplayName("source 는 AI 다 — 질문에는 COMMON 이 없다")
        void everyGeneratedItemIsAi() {
            long questionId = accept();
            llm.nextJson = questionsJson(q("질문 하나?", null), q("질문 둘?", null));

            worker.pollOnce();

            assertThat(itemsOf(questionId)).extracting(row -> row.get("source"))
                    .containsOnly("AI");
            assertThat(RepairQuestionItemSource.values())
                    .containsExactly(RepairQuestionItemSource.AI, RepairQuestionItemSource.USER);
        }

        @Test
        @DisplayName("같은 문장이 두 번 오면 하나만 남는다")
        void duplicateQuestionsAreCollapsed() {
            long questionId = accept();
            llm.nextJson = questionsJson(q("같은 질문?", null), q("같은 질문?", null), q("다른 질문?", null));

            worker.pollOnce();

            assertThat(itemsOf(questionId)).hasSize(2);
        }
    }

    // ─────────────────────────────────────────────────────────── 표현 경계

    @Nested
    @DisplayName("표현 경계 (prompt65 §3-3)")
    class Tone {

        @Test
        @DisplayName("비난하는 질문은 버리고 나머지는 살린다")
        void accusatoryQuestionsAreDropped() {
            long questionId = accept();
            llm.nextJson = questionsJson(
                    q("불필요한 수리를 넣은 것 아닌가요?", null),
                    q("혹시 바가지 아닌가요?", null),
                    q("교환 대신 판금으로 가능한지 확인 부탁드립니다?", null));

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("COMPLETED");
            assertThat(itemsOf(questionId)).extracting(row -> row.get("content"))
                    .containsExactly("교환 대신 판금으로 가능한지 확인 부탁드립니다?");
        }

        @Test
        @DisplayName("전부 비난이면 목록을 반쯤 내보내지 않고 FAILED 다")
        void allAccusatoryEndsAsFailed() {
            long questionId = accept();
            llm.nextJson = questionsJson(q("허위 청구 아닌가요?", null), q("사기 아닌가요?", null));

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(questionId))
                    .isEqualTo(RepairQuestionFailure.INVALID_RESPONSE.name());
            assertThat(itemsOf(questionId)).isEmpty();
        }

        @Test
        @DisplayName("지시문이 비난 금지와 금액 단정 금지를 담는다")
        void instructionCarriesTheBoundary() {
            accept();
            llm.nextJson = questionsJson(q("질문 하나?", null));

            worker.pollOnce();

            assertThat(llm.lastInstruction)
                    .contains("의심하거나 비난하는 표현을 쓰지 마세요")
                    .contains("확인을 부탁하는 정중한 어조")
                    .contains("금액, 수리비, 공임 단가를 단정해 적지 마세요");
        }

        /**
         * 예상 견적을 <b>쓰되 금액은 주지 않는다</b>. 지시문에 총액이 들어가면 LLM 이 그 숫자를
         * 질문에 옮겨 적어, 우리 추정치가 정비소 앞에서 확정된 사실처럼 읽힌다.
         */
        @Test
        @DisplayName("지시문에 견적 신뢰도는 들어가고 금액은 들어가지 않는다")
        void instructionCarriesGradeButNoAmount() {
            seedAnalysis();
            accept();
            llm.nextJson = questionsJson(q("질문 하나?", null));

            worker.pollOnce();

            assertThat(llm.lastInstruction).contains("신뢰도 등급 LOW");
            assertThat(llm.lastInstruction).doesNotContain("1234000");
        }

        @Test
        @DisplayName("마크업이 섞여 오면 태그를 벗겨 저장한다")
        void markupIsStripped() {
            long questionId = accept();
            llm.nextJson = questionsJson(q("<b>판금</b>으로 가능한가요?", null));

            worker.pollOnce();

            assertThat(itemsOf(questionId).getFirst().get("content"))
                    .isEqualTo("판금 으로 가능한가요?");
        }
    }

    // ─────────────────────────────────────────────────────────── 실패 경로

    @Nested
    @DisplayName("생성 실패")
    class Failure {

        @Test
        @DisplayName("LLM 이 깨진 JSON 을 주면 FAILED 로 남는다")
        void brokenJsonEndsAsFailed() {
            long questionId = accept();
            llm.nextJson = "{\"questions\": [";

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(questionId))
                    .isEqualTo(RepairQuestionFailure.INVALID_RESPONSE.name());
            assertThat(itemsOf(questionId)).isEmpty();
        }

        @Test
        @DisplayName("형식은 맞는데 쓸 질문이 없어도 FAILED 다")
        void emptyQuestionsEndAsFailed() {
            long questionId = accept();
            llm.nextJson = "{\"questions\": [{\"content\": \"   \"}, {\"content\": 12}]}";

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(questionId))
                    .isEqualTo(RepairQuestionFailure.INVALID_RESPONSE.name());
        }

        @Test
        @DisplayName("호출 자체가 실패하면 파싱 실패와 다른 사유로 남는다")
        void callFailureIsADifferentReason() {
            long questionId = accept();
            llm.failure = new IllegalStateException("boom");

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(questionId))
                    .isEqualTo(RepairQuestionFailure.LLM_CALL_FAILED.name());
        }

        @Test
        @DisplayName("failure_reason 에 한글이 없다 — 코드이지 문구가 아니다")
        void failureReasonCarriesNoKorean() {
            long questionId = accept();
            llm.nextJson = "not json at all";

            worker.pollOnce();

            String reason = failureReasonOf(questionId);
            assertThat(reason).isNotNull();
            assertThat(reason).matches("[A-Z_]+");
            assertThat(reason.length())
                    .isLessThanOrEqualTo(com.ssafy.a307.repairquestion.entity.RepairQuestion.MAX_FAILURE_REASON_LENGTH);
            assertThat(reason.chars().anyMatch(RepairQuestionGenerationTest::isHangul)).isFalse();
        }

        @Test
        @DisplayName("한 줄이 깨져도 나머지 질문은 살린다")
        void oneBadItemDoesNotDiscardTheRest() {
            long questionId = accept();
            llm.nextJson = "{\"questions\": [{\"content\": \"살아남는 질문?\"}, {\"content\": \"\"},"
                    + " {\"note\": \"content 가 없다\"}]}";

            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("COMPLETED");
            assertThat(itemsOf(questionId)).hasSize(1);
        }
    }

    // ─────────────────────────────────────────────────────────── DB 제약

    /**
     * prompt65 §4-2. {@code ck_rqi_basis} · {@code ck_rqi_part} 는 <b>코드가 어기면 DB 가
     * 거부</b>해야 한다. H2 에서도 CHECK 가 실제로 도는지 직접 밟아 본다 — 안 돌면 이 테스트가
     * 통과해 버려 잘못된 안심을 준다.
     */
    @Nested
    @DisplayName("근거 제약을 실제로 밟는다")
    class Constraints {

        @Test
        @DisplayName("코드만 있고 이름 사본이 없으면 거부된다 (ck_rqi_basis)")
        void codeWithoutNameIsRejected() {
            long questionId = accept();

            assertThatThrownBy(() -> jdbc.update(
                    "insert into repair_question_item(question_id,source,content,part_code,display_order)"
                            + " values(?,'AI','질문?',?,1)", questionId, PART_CODE))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("부품 없이 판정만 있으면 거부된다 (ck_rqi_part)")
        void judgementWithoutPartIsRejected() {
            long questionId = accept();

            assertThatThrownBy(() -> jdbc.update(
                    "insert into repair_question_item(question_id,source,content,damage_type,display_order)"
                            + " values(?,'AI','질문?','Scratched',1)", questionId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("source 는 AI·USER 둘뿐이다 — COMMON 은 거부된다 (ck_rqi_source)")
        void commonSourceIsRejected() {
            long questionId = accept();

            assertThatThrownBy(() -> jdbc.update(
                    "insert into repair_question_item(question_id,source,content,display_order)"
                            + " values(?,'COMMON','질문?',1)", questionId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ─────────────────────────────────────────────────────────── 재요청

    @Nested
    @DisplayName("이미 있을 때의 재요청 (uk_rq_accident)")
    class Rerequest {

        @Test
        @DisplayName("진행 중이면 같은 목록을 그대로 돌려준다 — 행이 늘지 않는다")
        void inFlightRequestIsIdempotent() {
            long first = accept();

            RepairQuestionResponse again = requestService.request(MEMBER_ID, ACCIDENT_ID);

            assertThat(again.questionId()).isEqualTo(first);
            assertThat(again.status()).isEqualTo(RepairQuestionStatus.QUEUED);
            assertThat(countQuestions()).isEqualTo(1);
        }

        @Test
        @DisplayName("이미 완성돼 있으면 409 다 — 조용히 덮지 않는다")
        void completedListRejectsAnotherRequest() {
            long questionId = accept();
            llm.nextJson = questionsJson(q("질문 하나?", null));
            worker.pollOnce();
            assertThat(statusOf(questionId)).isEqualTo("COMPLETED");

            assertThatThrownBy(() -> requestService.request(MEMBER_ID, ACCIDENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("실패한 건은 다시 요청하면 큐로 돌아가고 다음 주기에 완성된다")
        void failedListCanBeRetried() {
            long questionId = accept();
            llm.failure = new IllegalStateException("boom");
            worker.pollOnce();
            assertThat(statusOf(questionId)).isEqualTo("FAILED");

            RepairQuestionResponse requeued = requestService.request(MEMBER_ID, ACCIDENT_ID);

            assertThat(requeued.questionId()).isEqualTo(questionId);
            assertThat(requeued.status()).isEqualTo(RepairQuestionStatus.QUEUED);
            assertThat(requeued.generationNo()).isEqualTo((short) 1);
            assertThat(requeued.regeneratedAt()).isNull();
            assertThat(failureReasonOf(questionId)).isNull();
            assertThat(completedAtOf(questionId)).isNull();

            llm.failure = null;
            llm.nextJson = questionsJson(q("이번에는 성공?", null));
            worker.pollOnce();

            assertThat(statusOf(questionId)).isEqualTo("COMPLETED");
            assertThat(countQuestions()).isEqualTo(1);
            assertThat(itemsOf(questionId)).hasSize(1);
        }

        @Test
        @DisplayName("재시도하면 앞 시도가 남긴 질문이 섞이지 않는다")
        void retryReplacesPreviousItems() {
            long questionId = accept();
            llm.nextJson = questionsJson(q("첫 시도 질문?", null));
            worker.pollOnce();

            jdbc.update("update repair_question set status = 'FAILED' where question_id = ?", questionId);
            requestService.request(MEMBER_ID, ACCIDENT_ID);
            llm.nextJson = questionsJson(q("두 번째 시도 질문?", null));
            worker.pollOnce();

            assertThat(itemsOf(questionId)).extracting(row -> row.get("content"))
                    .containsExactly("두 번째 시도 질문?");
        }
    }

    // ─────────────────────────────────────────────────────────── 조회

    @Nested
    @DisplayName("조회 (S15P21A307-476 전체 복사)")
    class Query {

        @Test
        @DisplayName("완성된 목록은 질문을 display_order 순으로 함께 준다")
        void completedListCarriesItsQuestions() {
            seedAnalysis();
            accept();
            llm.nextJson = questionsJson(
                    q("리어 도어는 교환 없이 수리가 가능한가요?", PART_CODE),
                    q("순정 외 부품 사용 시 금액 차이는 얼마인가요?", null));
            worker.pollOnce();

            RepairQuestionResponse found = queryService.find(MEMBER_ID, ACCIDENT_ID);

            assertThat(found.status()).isEqualTo(RepairQuestionStatus.COMPLETED);
            assertThat(found.questions()).extracting(RepairQuestionItemResponse::content)
                    .containsExactly("리어 도어는 교환 없이 수리가 가능한가요?",
                            "순정 외 부품 사용 시 금액 차이는 얼마인가요?");
            assertThat(found.questions()).extracting(RepairQuestionItemResponse::displayOrder)
                    .containsExactly((short) 1, (short) 2);

            RepairQuestionItemResponse grounded = found.questions().getFirst();
            assertThat(grounded.partCode()).isEqualTo(PART_CODE);
            assertThat(grounded.partName()).isEqualTo(PART_NAME);
            assertThat(grounded.damageType()).isEqualTo("Scratched");
            assertThat(grounded.repairMethod()).isEqualTo("exchange");
            assertThat(grounded.source()).isEqualTo(RepairQuestionItemSource.AI);
        }

        @Test
        @DisplayName("아직 요청하지 않은 사고는 빈 상태 200 이다")
        void notRequestedIsEmptyNotError() {
            RepairQuestionResponse found = queryService.find(MEMBER_ID, ACCIDENT_ID);

            assertThat(found.questionId()).isNull();
            assertThat(found.status()).isNull();
            assertThat(found.questions()).isEmpty();
        }

        @Test
        @DisplayName("완성 전에는 질문을 주지 않는다 — 쓰는 중인 부분 결과가 보이면 안 된다")
        void inFlightListHasNoQuestions() {
            accept();

            RepairQuestionResponse found = queryService.find(MEMBER_ID, ACCIDENT_ID);

            assertThat(found.status()).isEqualTo(RepairQuestionStatus.QUEUED);
            assertThat(found.questions()).isEmpty();
        }

        @Test
        @DisplayName("남의 사고와 없는 사고는 모두 404 다 — 조회도 요청도")
        void othersAndMissingAreBoth404() {
            assertThat(notFound(() -> queryService.find(OTHER_MEMBER_ID, ACCIDENT_ID))).isTrue();
            assertThat(notFound(() -> queryService.find(MEMBER_ID, 99_999_999L))).isTrue();
            assertThat(notFound(() -> requestService.request(OTHER_MEMBER_ID, ACCIDENT_ID))).isTrue();
            assertThat(notFound(() -> requestService.request(MEMBER_ID, 99_999_999L))).isTrue();
        }

        @Test
        @DisplayName("상태를 QUEUED·PROCESSING·COMPLETED·FAILED 네 값 그대로 내보낸다")
        void statusIsNotFoldedIntoThree() {
            long questionId = accept();
            assertThat(queryService.find(MEMBER_ID, ACCIDENT_ID).status())
                    .isEqualTo(RepairQuestionStatus.QUEUED);

            jdbc.update("update repair_question set status = 'PROCESSING' where question_id = ?", questionId);
            assertThat(queryService.find(MEMBER_ID, ACCIDENT_ID).status())
                    .isEqualTo(RepairQuestionStatus.PROCESSING);

            llm.nextJson = questionsJson(q("질문 하나?", null));
            jdbc.update("update repair_question set status = 'QUEUED' where question_id = ?", questionId);
            worker.pollOnce();
            assertThat(queryService.find(MEMBER_ID, ACCIDENT_ID).status())
                    .isEqualTo(RepairQuestionStatus.COMPLETED);

            assertThat(RepairQuestionStatus.values()).containsExactly(
                    RepairQuestionStatus.QUEUED, RepairQuestionStatus.PROCESSING,
                    RepairQuestionStatus.COMPLETED, RepairQuestionStatus.FAILED);
        }
    }

    // ─────────────────────────────────────────────────────────── 도우미

    private static boolean isHangul(int codePoint) {
        return (codePoint >= 0xAC00 && codePoint <= 0xD7A3) || (codePoint >= 0x3130 && codePoint <= 0x318F);
    }

    private static boolean notFound(Runnable call) {
        try {
            call.run();
            return false;
        } catch (BusinessException e) {
            return e.getErrorCode() == ErrorCode.NOT_FOUND;
        }
    }

    private long accept() {
        return requestService.request(MEMBER_ID, ACCIDENT_ID).questionId();
    }

    private record Draft(String content, String partCode) {
    }

    private static Draft q(String content, String partCode) {
        return new Draft(content, partCode);
    }

    private static String questionsJson(Draft... drafts) {
        StringBuilder json = new StringBuilder("{\"questions\": [");
        for (int i = 0; i < drafts.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"content\": \"").append(drafts[i].content()).append('"');
            if (drafts[i].partCode() != null) {
                json.append(", \"partCode\": \"").append(drafts[i].partCode()).append('"');
            }
            json.append('}');
        }
        return json.append("]}").toString();
    }

    private String statusOf(long questionId) {
        return jdbc.queryForObject("select status from repair_question where question_id = ?",
                String.class, questionId);
    }

    private String failureReasonOf(long questionId) {
        return jdbc.queryForObject("select failure_reason from repair_question where question_id = ?",
                String.class, questionId);
    }

    private Object completedAtOf(long questionId) {
        return jdbc.queryForObject("select completed_at from repair_question where question_id = ?",
                Object.class, questionId);
    }

    private Integer countQuestions() {
        return jdbc.queryForObject("select count(*) from repair_question where accident_id = ?",
                Integer.class, ACCIDENT_ID);
    }

    private List<Map<String, Object>> itemsOf(long questionId) {
        return jdbc.queryForList(
                "select source, content, display_order, part_code, snapshot_part_name,"
                        + " damage_type, repair_method"
                        + " from repair_question_item where question_id = ?"
                        + " order by display_order asc, item_id asc", questionId);
    }
}
