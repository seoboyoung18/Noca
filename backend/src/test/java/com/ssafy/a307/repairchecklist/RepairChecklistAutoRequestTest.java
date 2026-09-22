package com.ssafy.a307.repairchecklist;

import com.ssafy.a307.analysis.callback.AnalysisCallbackController;
import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 결과가 저장되면 정비 체크리스트를 자동으로 큐에 올린다 (S15P21A307-542).
 *
 * <p>여기서 보는 것은 <b>큐에 들어가는지</b>까지다. 항목을 만드는 일은 워커가 하고
 * {@link RepairChecklistGenerationTest} 가 본다. 그래서 워커를 켜지 않는다
 * ({@code app.repair-checklist.enabled} 기본값 {@code false}) — 큐에 올라온 행이 테스트 도중
 * 소비되면 어느 시점의 상태를 단언하는지가 흐려진다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 자동 요청은 결과 저장이 <b>커밋된 뒤</b>
 * 도는 리스너가 한다. 테스트가 트랜잭션을 쥐고 있으면 커밋이 없어 리스너가 아예 불리지 않는다.
 * 대신 높은 ID 대역을 쓰고 {@code @AfterEach} 에서 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RepairChecklistAutoRequestTest.SilentLlmConfig.class)
@DisplayName("분석 완료 시 체크리스트 자동 생성 (S15P21A307-542)")
class RepairChecklistAutoRequestTest {

    private static final long MEMBER_ID = 96_401L;
    private static final long MODEL_ID = 96_402L;
    private static final long VEHICLE_ID = 96_403L;
    private static final long ACCIDENT_ID = 96_404L;
    private static final long JOB_ID = 96_405L;
    private static final long IMAGE_ID = 96_406L;
    private static final String TOKEN = "test-internal-token";
    private static final String REQUEST_ID = "auto-checklist-req";

    /**
     * 키가 없는 테스트 환경에서는 {@link LlmChatPort} 빈이 없어 요청이 503 으로 끊긴다. 자동
     * 생성의 계약은 "큐에 올린다" 이므로 어댑터가 있는 환경을 만든다. <b>호출되지는 않는다</b> —
     * 워커를 켜지 않았고, 접수는 LLM 을 부르지 않는다.
     */
    @TestConfiguration
    static class SilentLlmConfig {

        @Bean
        LlmChatPort silentLlmChatPort() {
            return request -> {
                throw new UnsupportedOperationException("이 테스트는 LLM 을 부르지 않는다");
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname)"
                + " values(?,'KAKAO','checklist-auto','자동생성')", MEMBER_ID);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2021)",
                VEHICLE_ID, MEMBER_ID, MODEL_ID);
        jdbc.update("""
                insert into accident(accident_id,vehicle_id,vehicle_input_type,snapshot_model_id,
                                     snapshot_manufacturer,snapshot_model_name,snapshot_vehicle_type,
                                     snapshot_car_class,snapshot_model_year)
                values(?,?,'REGISTERED',?,'현대','아반떼','SEDAN','Mid-size',2021)
                """, ACCIDENT_ID, VEHICLE_ID, MODEL_ID);
        jdbc.update("insert into analysis_job(job_id,accident_id,status) values(?,?,'PROCESSING')",
                JOB_ID, ACCIDENT_ID);
        jdbc.update("insert into part_code(part_code,name_ko,layout_zone,display_order,is_active)"
                + " values('FRONT_BUMPER','앞 범퍼','FRONT',1,true)");
        jdbc.update("insert into accident_image(image_id,accident_id,original_filename,angle_code)"
                + " values(?,?,'front.jpg','FRONT')", IMAGE_ID, ACCIDENT_ID);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("""
                delete from repair_checklist_item where checklist_id in
                    (select checklist_id from repair_checklist where accident_id = ?)
                """, ACCIDENT_ID);
        jdbc.update("delete from repair_checklist where accident_id = ?", ACCIDENT_ID);
        jdbc.update("""
                delete from estimate_item where estimate_id in
                    (select estimate_id from estimate where job_id = ?)
                """, JOB_ID);
        jdbc.update("delete from estimate where job_id = ?", JOB_ID);
        jdbc.update("delete from analysis_stage where job_id = ?", JOB_ID);
        jdbc.update("delete from analysis_image_result where job_id = ?", JOB_ID);
        jdbc.update("delete from damaged_part where job_id = ?", JOB_ID);
        jdbc.update("delete from analysis_job where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from accident_image where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from member where member_id = ?", MEMBER_ID);
        jdbc.update("delete from part_code where part_code = 'FRONT_BUMPER'");
    }

    @Test
    @DisplayName("분석 결과가 저장되면 체크리스트가 QUEUED 로 생긴다 — 사용자가 요청하지 않아도 된다")
    void successfulCallbackQueuesChecklist() throws Exception {
        mockMvc.perform(callback(successBody())).andExpect(status().isOk());

        assertThat(checklistStatus()).isEqualTo(RepairChecklistStatus.QUEUED.name());
    }

    /**
     * 산정 불가 견적도 대상이다. 체크리스트는 손상 부위가 없으면 차량 정보만으로 항목을 만들도록
     * 설계돼 있고, 무엇보다 <b>지금 AI 는 mock 이라 운영 견적이 대부분 산정 불가로 저장된다</b> —
     * 산정된 견적만 대상으로 하면 운영에서 자동 생성이 거의 일어나지 않는다.
     */
    @Test
    @DisplayName("산정 불가 견적이어도 체크리스트는 만든다")
    void nonEstimableCallbackQueuesChecklistToo() throws Exception {
        mockMvc.perform(callback("""
                {
                  "requestId":"%s","jobId":%d,"modelVersion":"a307-ai-mock-v1","pipelineVersionId":1,
                  "estimable":false,"nonEstimableReason":"INSUFFICIENT_CASES",
                  "imageResults":[{"imageId":%d,"width":1600,"height":1200,
                                   "excluded":false,"exclusionReason":null,"detections":[]}]
                }
                """.formatted(REQUEST_ID, JOB_ID, IMAGE_ID))).andExpect(status().isOk());

        assertThat(checklistStatus()).isEqualTo(RepairChecklistStatus.QUEUED.name());
    }

    /**
     * 실패한 분석에는 만들지 않는다. 화면이 보여 줄 것은 실패 사유와 재시도이지 체크리스트가
     * 아니고, 재시도(S15P21A307-161)가 성공하면 그때 만들어진다.
     */
    @Test
    @DisplayName("실패 callback 이면 체크리스트를 만들지 않는다")
    void failedCallbackDoesNotQueueChecklist() throws Exception {
        mockMvc.perform(callback("""
                {
                  "requestId":"%s","jobId":%d,"modelVersion":"a307-ai-mock-v1","pipelineVersionId":1,
                  "estimable":false,
                  "error":{"code":"MODEL_ERROR","message":"모델 실행 실패","retryable":true}
                }
                """.formatted(REQUEST_ID, JOB_ID))).andExpect(status().isOk());

        assertThat(checklistCount()).isZero();
    }

    /**
     * 이미 완성된 체크리스트가 있으면 {@code request()} 가 409 를 던진다. 자동 경로에서는 그것이
     * 정상이므로 삼킨다 — <b>결과 수신이 500 이 되면 AI 가 같은 결과를 세 번 더 보낸다.</b>
     */
    @Test
    @DisplayName("이미 완성된 체크리스트가 있어도 결과 수신은 200 이고 기존 체크리스트를 건드리지 않는다")
    void completedChecklistIsLeftAlone() throws Exception {
        jdbc.update("insert into repair_checklist(checklist_id,accident_id,status,generation_no,completed_at)"
                + " values(?,?,'COMPLETED',1,current_timestamp)", 96_407L, ACCIDENT_ID);

        mockMvc.perform(callback(successBody())).andExpect(status().isOk());

        assertThat(checklistStatus()).isEqualTo(RepairChecklistStatus.COMPLETED.name());
        assertThat(checklistCount()).isEqualTo(1);
    }

    /**
     * 부위를 골라 다시 분석한 작업(S15P21A307-570)은 완성된 체크리스트를 다시 만든다. 앞 체크리스트는
     * 부품을 못 찾은 분석으로 만든 것이라 부위가 비어 있다. 재생성은 세대를 올리고 {@code QUEUED} 로
     * 되돌린다 — 항목은 워커가 새로 만든다.
     */
    @Test
    @DisplayName("부위를 골라 다시 분석한 결과가 오면 완성된 체크리스트를 다시 만든다")
    void partSelectionRegeneratesCompletedChecklist() throws Exception {
        jdbc.update("update analysis_job set selected_part_code = 'FRONT_BUMPER' where job_id = ?", JOB_ID);
        jdbc.update("insert into repair_checklist(checklist_id,accident_id,status,generation_no,completed_at)"
                + " values(?,?,'COMPLETED',1,current_timestamp)", 96_409L, ACCIDENT_ID);

        mockMvc.perform(callback(successBody())).andExpect(status().isOk());

        assertThat(checklistStatus()).isEqualTo(RepairChecklistStatus.QUEUED.name());
        assertThat(jdbc.queryForObject("select generation_no from repair_checklist where accident_id = ?",
                Integer.class, ACCIDENT_ID)).isEqualTo(2);
        assertThat(checklistCount()).isEqualTo(1);
    }

    /** 한 번 실패한 체크리스트는 다시 큐에 올린다 — {@code request()} 가 이미 그렇게 한다. */
    @Test
    @DisplayName("실패했던 체크리스트는 다시 큐에 올린다")
    void failedChecklistIsRequeued() throws Exception {
        jdbc.update("insert into repair_checklist(checklist_id,accident_id,status,generation_no,"
                        + "failure_reason,completed_at) values(?,?,'FAILED',1,'LLM_CALL_FAILED',current_timestamp)",
                96_408L, ACCIDENT_ID);

        mockMvc.perform(callback(successBody())).andExpect(status().isOk());

        assertThat(checklistStatus()).isEqualTo(RepairChecklistStatus.QUEUED.name());
        assertThat(checklistCount()).isEqualTo(1);
    }

    /**
     * 분석 결과는 자동 생성보다 중요하다. 중복 callback 은 저장을 건너뛰고 200 을 주는데,
     * 그때도 체크리스트 때문에 예외가 밖으로 나가면 안 된다.
     */
    @Test
    @DisplayName("같은 결과가 두 번 와도 결과 수신은 200 이고 체크리스트는 하나다")
    void duplicateCallbackKeepsOneChecklist() throws Exception {
        mockMvc.perform(callback(successBody())).andExpect(status().isOk());
        mockMvc.perform(callback(successBody())).andExpect(status().isOk());

        assertThat(checklistCount()).isEqualTo(1);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private String successBody() {
        return """
                {
                  "requestId":"%s","jobId":%d,"modelVersion":"a307-ai-v1","pipelineVersionId":1,
                  "estimable":true,"nonEstimableReason":null,"confidenceGrade":"MEDIUM",
                  "totals":{"min":480000,"median":550000,"max":610000},
                  "refCaseTotal":30,"refYearFrom":2021,"refYearTo":2021,
                  "items":[{
                    "partCode":"FRONT_BUMPER","damageType":"Crushed","confidence":0.93,
                    "repairMethod":"exchange","standardHq":2.3,"partCost":null,
                    "laborCost":250000,"paintMaterialCost":0,"itemTotal":250000,
                    "refCaseCount":18,"referencedCaseIds":[],"fallbackStage":"CAR_CLASS"
                  }],
                  "imageResults":[{"imageId":%d,"width":1600,"height":1200,
                                   "excluded":false,"exclusionReason":null,"detections":[]}]
                }
                """.formatted(REQUEST_ID, JOB_ID, IMAGE_ID);
    }

    private MockHttpServletRequestBuilder callback(String body) {
        return post("/internal/analysis-jobs/{jobId}/result", JOB_ID)
                .header(AnalysisCallbackController.TOKEN_HEADER, TOKEN)
                .header(AnalysisCallbackController.REQUEST_ID_HEADER, REQUEST_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String checklistStatus() {
        List<String> statuses = jdbc.queryForList(
                "select status from repair_checklist where accident_id = ?", String.class, ACCIDENT_ID);
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private int checklistCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from repair_checklist where accident_id = ?", Integer.class, ACCIDENT_ID);
        return count == null ? 0 : count;
    }
}
