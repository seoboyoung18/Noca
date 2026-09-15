package com.ssafy.a307.repairquestion;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairquestion.domain.RepairQuestionFailure;
import com.ssafy.a307.repairquestion.service.RepairQuestionRequestService;
import com.ssafy.a307.repairquestion.service.RepairQuestionWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>{@code GMS_KEY} 가 없는 환경</b>을 그대로 본다 (prompt65 §4-1 · 완료 조건).
 *
 * <p>테스트 프로퍼티의 {@code app.gms.api-key} 가 비어 있어 {@code GmsKeyPresentCondition} 이
 * {@link LlmChatPort} 빈을 만들지 않는다. <b>대역도 주입하지 않는다</b> — 그래야 소비처가 받는
 * {@code Optional} 이 실제로 빈 값이 된다. {@link RepairQuestionGenerationTest} 는 반대로 대역을
 * 넣어 두므로 두 상황을 한 클래스에서 볼 수 없다.
 *
 * <p>확인하는 것은 둘이다.
 *
 * <ol>
 *   <li><b>키가 없어도 컨텍스트가 뜬다.</b> 이 테스트가 도는 것 자체가 그 증거다 —
 *       {@code Optional<LlmChatPort>} 로 받지 않았다면 여기서 기동이 실패한다</li>
 *   <li>새 요청은 <b>503</b> 으로 먼저 막히고, 그 전에 접수돼 큐에 남아 있던 건은 워커가
 *       <b>{@code FAILED} · {@code LLM_UNAVAILABLE}</b> 로 끝낸다</li>
 * </ol>
 *
 * <p>둘째를 보려면 큐에 든 행이 필요한데 요청 경로가 막혀 있으므로 <b>행을 직접 넣는다.</b>
 * 운영에서 이 상태가 생기는 경로도 같다 — 키가 있을 때 접수된 뒤 키가 빠진 경우다.
 */
@SpringBootTest(properties = {
        "app.repair-question.enabled=true",
        "app.repair-question.poll-interval=PT1H",
        "app.repair-question.initial-delay=PT1H",
        "app.repair-question.batch-size=5",
        "app.repair-question.processing-timeout=PT10M"
})
@DisplayName("질문 목록 생성 — LLM 어댑터가 없는 환경")
class RepairQuestionWithoutLlmTest {

    private static final long MEMBER_ID = 96_401L;
    private static final long MODEL_ID = 96_402L;
    private static final long VEHICLE_ID = 96_403L;
    private static final long ACCIDENT_ID = 96_404L;
    private static final long QUESTION_ID = 96_405L;

    @Autowired private RepairQuestionRequestService requestService;
    @Autowired private RepairQuestionWorker worker;
    @Autowired private ApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','question-nokey','tester','USER','ACTIVE')", MEMBER_ID);
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
        jdbc.update("delete from repair_question_item where question_id = ?", QUESTION_ID);
        jdbc.update("delete from repair_question where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from member where member_id = ?", MEMBER_ID);
    }

    @Test
    @DisplayName("키가 없으면 LlmChatPort 빈이 아예 없고, 그래도 컨텍스트는 뜬다")
    void contextStartsWithoutTheAdapter() {
        assertThat(context.getBeanNamesForType(LlmChatPort.class)).isEmpty();
        assertThat(context.getBeanProvider(LlmChatPort.class).getIfAvailable())
                .isNull();
        assertThat(Optional.ofNullable(context.getBeanProvider(LlmChatPort.class).getIfAvailable()))
                .isEmpty();
    }

    @Test
    @DisplayName("새 요청은 503 이다 — 성공할 수 없는 요청을 큐에 눌러앉히지 않는다")
    void requestIsRejectedWith503() {
        assertThatThrownBy(() -> requestService.request(MEMBER_ID, ACCIDENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(jdbc.queryForObject("select count(*) from repair_question where accident_id = ?",
                Integer.class, ACCIDENT_ID)).isZero();
    }

    @Test
    @DisplayName("접수된 뒤 키가 빠진 건은 FAILED · LLM_UNAVAILABLE 로 끝난다")
    void queuedRowEndsAsLlmUnavailable() {
        jdbc.update("insert into repair_question(question_id,accident_id,status,generation_no)"
                + " values(?,?,'QUEUED',1)", QUESTION_ID, ACCIDENT_ID);

        worker.pollOnce();

        assertThat(jdbc.queryForObject("select status from repair_question where question_id = ?",
                String.class, QUESTION_ID)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select failure_reason from repair_question where question_id = ?",
                String.class, QUESTION_ID))
                .isEqualTo(RepairQuestionFailure.LLM_UNAVAILABLE.name());
        assertThat(jdbc.queryForObject(
                "select count(*) from repair_question_item where question_id = ?",
                Integer.class, QUESTION_ID)).isZero();
    }
}
