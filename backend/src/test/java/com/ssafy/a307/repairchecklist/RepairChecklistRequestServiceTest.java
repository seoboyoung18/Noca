package com.ssafy.a307.repairchecklist;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.service.RepairChecklistRequestService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 체크리스트 생성 요청·상태 조회 중 <b>LLM 어댑터가 없는 환경</b>의 계약 (S15P21A307-460 · -461).
 *
 * <p><b>이 클래스가 곧 "키 없이도 돈다" 의 증거다.</b> 테스트 클래스패스의
 * {@code app.gms.api-key} 가 비어 있어 {@code GmsKeyPresentCondition} 이 LLM 계층 빈을 만들지
 * 않는다. 그래도 컨텍스트가 뜨고 사고·차량 API 가 살아 있으며, 체크리스트 생성만 503 이다.
 * 생성이 성공하는 경로는 {@link RepairChecklistGenerationTest} 가 대역을 넣어 따로 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("체크리스트 생성 요청 (LLM 어댑터 없음)")
class RepairChecklistRequestServiceTest {

    private static final long ME = 96_101L;
    private static final long OTHER = 96_102L;
    private static final long MODEL_ID = 96_103L;
    private static final long MY_VEHICLE = 96_104L;
    private static final long OTHER_VEHICLE = 96_105L;
    private static final long MY_ACCIDENT = 96_106L;
    private static final long OTHER_ACCIDENT = 96_107L;
    private static final long MISSING_ACCIDENT = 96_999L;

    @Autowired private RepairChecklistRequestService requestService;
    @Autowired private RepairChecklistStatusService statusService;
    @Autowired private Optional<LlmChatPort> llmChatPort;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        insertMember(ME, "checklist-me");
        insertMember(OTHER, "checklist-other");
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        insertVehicle(MY_VEHICLE, ME);
        insertVehicle(OTHER_VEHICLE, OTHER);
        insertAccident(MY_ACCIDENT, MY_VEHICLE);
        insertAccident(OTHER_ACCIDENT, OTHER_VEHICLE);
    }

    @Test
    @DisplayName("키가 없으면 LLM 어댑터 빈이 아예 없다 — 그래도 컨텍스트는 떴다")
    void contextStartsWithoutLlmAdapter() {
        assertThat(llmChatPort).isEmpty();
    }

    @Nested
    @DisplayName("소유자 판정")
    class Ownership {

        @Test
        @DisplayName("없는 사고에 요청하면 404 다")
        void missingAccidentIsNotFound() {
            assertThatThrownBy(() -> requestService.request(ME, MISSING_ACCIDENT))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        /**
         * <b>403 이 아니라 404 여야 한다.</b> 403 은 "그 사고는 존재한다" 를 알려 주고, 그러면
         * 사고 ID 를 훑어 남의 사고 존재 여부를 알아낼 수 있다.
         */
        @Test
        @DisplayName("남의 사고에 요청해도 403 이 아니라 404 다")
        void otherMembersAccidentIsNotFoundToo() {
            assertThatThrownBy(() -> requestService.request(ME, OTHER_ACCIDENT))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("상태 조회도 없는 사고·남의 사고를 똑같이 404 로 낸다")
        void statusHidesExistenceToo() {
            assertThatThrownBy(() -> statusService.status(ME, MISSING_ACCIDENT))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> statusService.status(ME, OTHER_ACCIDENT))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    /**
     * §3-2 판단의 고정. 접수해 두고 워커가 {@code FAILED} 로 적게 하는 대신 <b>그 자리에서
     * 503</b> 이다 — 성공할 수 없는 요청으로 사고당 한 자리뿐인 {@code uk_rcl_accident} 를
     * 실패 행이 차지하면, 사용자는 기다린 끝에 실패를 보고 그 행을 스스로 치울 방법도 없다.
     */
    @Test
    @DisplayName("GMS 키가 없으면 접수하지 않고 503 이다 — 행도 만들지 않는다")
    void withoutLlmAdapterRequestIsUnavailable() {
        assertThatThrownBy(() -> requestService.request(ME, MY_ACCIDENT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(countChecklists(MY_ACCIDENT)).isZero();
    }

    /**
     * 사고는 있는데 아직 요청하지 않은 상태. <b>404 가 아니다</b> — 404 로 내면 화면이
     * "없는 사고" 와 "생성 전" 을 구분하지 못한다({@code AnalysisProgressResponse.notRequested}).
     */
    @Test
    @DisplayName("요청 전 사고의 상태 조회는 오류가 아니라 빈 상태다")
    void statusBeforeRequestIsEmptyState() {
        RepairChecklistStatusResponse status = statusService.status(ME, MY_ACCIDENT);

        assertThat(status.checklistId()).isNull();
        assertThat(status.status()).isNull();
        assertThat(status.failureReason()).isNull();
    }

    private Integer countChecklists(long accidentId) {
        return jdbc.queryForObject(
                "select count(*) from repair_checklist where accident_id = ?", Integer.class, accidentId);
    }

    private void insertMember(long memberId, String providerUserId) {
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO',?,'tester','USER','ACTIVE')", memberId, providerUserId);
    }

    private void insertVehicle(long vehicleId, long memberId) {
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                vehicleId, memberId, MODEL_ID);
    }

    private void insertAccident(long accidentId, long vehicleId) {
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, ?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Mid-size', 2024)
                """, accidentId, vehicleId, MODEL_ID);
    }
}
