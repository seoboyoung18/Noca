package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.domain.ValidationFlag;
import com.ssafy.a307.estimatevalidation.domain.ValidationGrade;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationItemRequest;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class EstimateValidationServiceTest {

    private static final long ME = 101L;
    private static final long OTHER = 102L;
    private static final long MY_ACCIDENT = 301L;
    private static final long OTHER_ACCIDENT = 302L;
    private static final long ESTIMATE = 501L;

    @Autowired EstimateValidationService service;
    @Autowired CostComparisonService costComparisonService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUp() {
        insertMember(ME, "estimate-me");
        insertMember(OTHER, "estimate-other");
        jdbc.update("insert into vehicle_model(model_id, manufacturer, model_name, vehicle_type, car_class, is_active) values(201,'현대','아반떼','SEDAN','Mid-size',true)");
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(211,?,?,2024)", ME, 201L);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(212,?,?,2024)", OTHER, 201L);
        insertAccident(MY_ACCIDENT, 211L);
        insertAccident(OTHER_ACCIDENT, 212L);
        jdbc.update("insert into part_code(part_code,name_ko,layout_zone,display_order,is_active) values('P-FENDER','프론트 펜더','FRONT',1,true)");
        jdbc.update("insert into part_name_mapping(raw_name,part_code) values('프론트 펜더','P-FENDER')");
        jdbc.update("insert into analysis_job(job_id,accident_id,status,retry_count) values(401,?,'COMPLETED',0)", MY_ACCIDENT);
        jdbc.update("insert into damaged_part(damaged_part_id,job_id,part_code,damage_type,repair_method,confidence) values(411,401,'P-FENDER','Scratched','sheet_metal',0.95)");
        jdbc.update("insert into estimate(estimate_id,job_id,version,total_min,total_median,total_max) values(?,401,1,120000,150000,180000)", ESTIMATE);
        jdbc.update("""
                insert into repair_cost_stat(stat_id,car_class,part_code,damage_type,repair_method,source,case_count,
                    cost_min,cost_p25,cost_median,cost_p75,cost_max)
                values(601,'Mid-size','P-FENDER','Scratched','sheet_metal','PUBLIC',20,90000,110000,130000,150000,180000)
                """);
    }

    private void insertAccident(long accidentId, long vehicleId) {
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, ?, 'REGISTERED', 201, '현대', '아반떼', 'SEDAN', 'Mid-size', 2024)
                """, accidentId, vehicleId);
    }

    @Test
    void manualRegistrationCompletesAndPersistsStableResultAndQuestions() {
        var response = service.registerManual(ME, request(150_001));

        assertThat(response.status()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(response.inputType()).isEqualTo(EstimateFileType.MANUAL);
        var result = service.result(ME, response.validationId());
        var questions = service.questions(ME, response.validationId());

        assertThat(result.grade()).isEqualTo(ValidationGrade.CAUTION);
        assertThat(result.claimedTotal()).isEqualTo(150_001);
        assertThat(result.differenceFromMedian()).isEqualTo(1L);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.partCode()).isEqualTo("P-FENDER");
            assertThat(item.referenceP75()).isEqualTo(150_000);
            assertThat(item.flag()).isEqualTo(ValidationFlag.OVER_P75);
            assertThat(item.displayDecision()).isEqualTo("확인 권장");
        });
        assertThat(questions).hasSize(1);
        assertThat(result.questions()).containsExactlyElementsOf(questions);
        assertThat(questions.getFirst().text()).contains("프론트 펜더", "산정 근거");
        assertThat(service.questions(ME, response.validationId())).containsExactlyElementsOf(questions);
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation_question where validation_id=?",
                Integer.class, response.validationId())).isEqualTo(1);
    }

    @Test
    void p75EqualityIsNotOverchargeAndQuestionsAreEmpty() {
        var response = service.registerManual(ME, request(150_000));
        var result = service.result(ME, response.validationId());

        assertThat(result.grade()).isEqualTo(ValidationGrade.APPROPRIATE);
        assertThat(result.reviewItemCount()).isZero();
        assertThat(result.items().getFirst().flag()).isNull();
        assertThat(service.questions(ME, response.validationId())).isEmpty();
    }

    @Test
    void sameAccidentAcceptsMultipleValidationsAndHistoryIsNewestFirst() {
        long first = service.registerManual(ME, request(150_000)).validationId();
        long second = service.registerManual(ME, request(150_001)).validationId();

        var history = service.history(ME, 0, 500);

        assertThat(history.size()).isEqualTo(100);
        assertThat(history.content()).extracting(entry -> entry.validationId())
                .containsExactly(second, first);
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation where accident_id=?", Integer.class, MY_ACCIDENT))
                .isEqualTo(2);
    }

    @Test
    void historyLoadsPageAndVehicleSummaryWithoutNPlusOne() {
        service.registerManual(ME, request(150_000));
        service.registerManual(ME, request(150_001));
        jdbc.update("update vehicle set model_year=2025 where vehicle_id=211");
        jdbc.update("""
                update vehicle_model
                set manufacturer='현대자동차', model_name='아반떼 변경', car_class='Full-size'
                where model_id=201
                """);
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var history = service.history(ME, 0, 20);

        assertThat(history.content()).hasSize(2);
        assertThat(history.content()).allSatisfy(entry -> {
            assertThat(entry.manufacturer()).isEqualTo("현대");
            assertThat(entry.modelName()).isEqualTo("아반떼");
            assertThat(entry.modelYear()).isEqualTo((short) 2024);
        });
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    @Test
    void referenceLookupUsesAccidentSnapshotCarClassAfterModelMasterChanges() {
        jdbc.update("update vehicle_model set car_class='Full-size' where model_id=201");

        var response = service.registerManual(ME, request(150_001));
        var result = service.result(ME, response.validationId());

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.referenceMedian()).isEqualTo(130_000);
            assertThat(item.referenceP75()).isEqualTo(150_000);
        });
    }

    @Test
    void inaccessibleAccidentAndMismatchedEstimateAreBothNotFound() {
        assertThatThrownBy(() -> service.registerManual(ME,
                new ManualValidationRequest(OTHER_ACCIDENT, null, EstimateFileType.MANUAL, null,
                        List.of(line(150_000)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        jdbc.update("insert into analysis_job(job_id,accident_id,status,retry_count) values(402,?,'COMPLETED',0)", OTHER_ACCIDENT);
        jdbc.update("insert into estimate(estimate_id,job_id,version,total_min,total_median,total_max) values(502,402,1,1,2,3)");
        assertThatThrownBy(() -> service.registerManual(ME,
                new ManualValidationRequest(MY_ACCIDENT, 502L, EstimateFileType.MANUAL, null,
                        List.of(line(150_000)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void claimedTotalMismatchAndOverflowAreRejected() {
        assertThatThrownBy(() -> service.registerManual(ME,
                new ManualValidationRequest(MY_ACCIDENT, ESTIMATE, EstimateFileType.MANUAL, 1,
                        List.of(line(150_000)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        var overflow = new ManualValidationItemRequest(1, "프론트 펜더", "판금", 32767,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThatThrownBy(() -> service.registerManual(ME,
                new ManualValidationRequest(MY_ACCIDENT, ESTIMATE, EstimateFileType.MANUAL, null,
                        List.of(overflow))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void comparisonAndModelExportPreserveMultipleShopEstimatesWithoutPersonalData() {
        long first = service.registerManual(ME, request(140_000)).validationId();
        long second = service.registerManual(ME, request(160_000)).validationId();
        jdbc.update("""
                update accident
                   set actual_repair_cost=155000,
                       actual_repair_completed_date=DATE '2026-09-01',
                       repair_shop_name='안심 정비소',
                       actual_cost_recorded_at=TIMESTAMP WITH TIME ZONE '2026-09-02 00:00:00+00'
                 where accident_id=?
                """, MY_ACCIDENT);
        entityManager.clear();

        var comparison = costComparisonService.compare(ME, MY_ACCIDENT);
        assertThat(comparison.aiEstimate().estimateId()).isEqualTo(ESTIMATE);
        assertThat(comparison.aiEstimate().differenceFromActual()).isEqualTo(-5_000L);
        assertThat(comparison.aiEstimate().actualWithinRange()).isTrue();
        assertThat(comparison.shopEstimates()).extracting(item -> item.validationId())
                .containsExactly(second, first);
        assertThat(comparison.shopEstimates()).extracting(item -> item.differenceFromActual())
                .containsExactly(5_000L, -15_000L);

        var records = costComparisonService.export(0, 100);
        assertThat(records).hasSize(2);
        assertThat(records).extracting(record -> record.recordKey())
                .containsExactly(MY_ACCIDENT + ":" + first, MY_ACCIDENT + ":" + second);
        assertThat(records).allSatisfy(record -> {
            assertThat(record.actualRepairCost()).isEqualTo(155_000);
            assertThat(record.carClass()).isEqualTo("Mid-size");
        });
    }

    @Test
    void comparisonUsesNullInsteadOfInventingActualCost() {
        var comparison = costComparisonService.compare(ME, MY_ACCIDENT);

        assertThat(comparison.actualRepair().cost()).isNull();
        assertThat(comparison.aiEstimate().differenceFromActual()).isNull();
        assertThat(comparison.aiEstimate().actualWithinRange()).isNull();
    }

    private ManualValidationRequest request(int subtotal) {
        return new ManualValidationRequest(
                MY_ACCIDENT, ESTIMATE, EstimateFileType.MANUAL, null,
                List.of(line(subtotal)));
    }

    private ManualValidationItemRequest line(int subtotal) {
        return new ManualValidationItemRequest(1, "프론트 펜더", "판금", 1, subtotal, 0);
    }

    private void insertMember(long id, String providerUserId) {
        jdbc.update("""
                insert into member(member_id,provider,provider_user_id,nickname,role,status)
                values(?,'KAKAO',?,'tester','USER','ACTIVE')
                """, id, providerUserId);
    }
}
