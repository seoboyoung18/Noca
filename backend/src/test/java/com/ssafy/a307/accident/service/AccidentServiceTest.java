package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
import com.ssafy.a307.accident.dto.AccidentHistoryStatus;
import com.ssafy.a307.accident.dto.AccidentPageResponse;
import com.ssafy.a307.accident.dto.AccidentResponse;
import com.ssafy.a307.accident.dto.AccidentSummaryResponse;
import com.ssafy.a307.accident.dto.AccidentVehicleSearchCondition;
import com.ssafy.a307.accident.dto.ActualRepairCostRequest;
import com.ssafy.a307.accident.dto.ActualRepairCostResponse;
import com.ssafy.a307.accident.dto.DirectVehicleInput;
import com.ssafy.a307.accident.entity.VehicleInputType;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.vehicle.dto.VehicleCreateRequest;
import com.ssafy.a307.vehicle.dto.VehicleResponse;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import com.ssafy.a307.vehicle.service.VehicleService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 인증이 아직 없어도 memberId 를 넘기면 사고 접수 로직을 전부 검증할 수 있다.
 */
@SpringBootTest
@Transactional
@DisplayName("AccidentService")
class AccidentServiceTest {

    private static final long ME = 1L;
    private static final long OTHER = 2L;

    @Autowired
    private AccidentService accidentService;
    @Autowired
    private AccidentVehicleSearchConditionResolver searchConditionResolver;
    @Autowired
    private AccidentRepository accidentRepository;
    @Autowired
    private VehicleService vehicleService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private long avante;

    @BeforeEach
    void setUp() {
        insertMember(ME, "kakao-me");
        insertMember(OTHER, "kakao-other");
        avante = insertModel("현대", "아반떼", "SEDAN", "Mid-size");
    }

    @Nested
    @DisplayName("접수 생성")
    class Create {

        @Test
        @DisplayName("차량 정보를 함께 담아 돌려준다")
        void returnsVehicleInfo() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));

            AccidentResponse response =
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));

            assertThat(response.accidentId()).isNotNull();
            assertThat(response.createdAt()).isNotNull();
            assertThat(response.vehicleId()).isEqualTo(vehicle.vehicleId());
            assertThat(response.vehicleInputType()).isEqualTo(VehicleInputType.REGISTERED);
            assertThat(response.modelId()).isEqualTo(avante);
            assertThat(response.manufacturer()).isEqualTo("현대");
            assertThat(response.modelName()).isEqualTo("아반떼");
            assertThat(response.vehicleType()).isEqualTo(VehicleType.SEDAN);
            assertThat(response.carClass()).isEqualTo(CarClass.MID_SIZE);
            assertThat(response.modelYear()).isEqualTo(2020);
        }

        @Test
        @DisplayName("등록 차량 접수는 모든 차량 스냅샷 필드를 저장한다")
        void storesAllRegisteredVehicleSnapshotFields() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));

            AccidentResponse response = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId()));
            flushAndClear();

            Map<String, Object> stored = jdbcTemplate.queryForMap("""
                    select vehicle_input_type, snapshot_model_id, snapshot_manufacturer,
                           snapshot_model_name, snapshot_vehicle_type, snapshot_car_class,
                           snapshot_model_year
                    from accident where accident_id = ?
                    """, response.accidentId());

            assertThat(stored.get("VEHICLE_INPUT_TYPE")).isEqualTo("REGISTERED");
            assertThat(((Number) stored.get("SNAPSHOT_MODEL_ID")).longValue()).isEqualTo(avante);
            assertThat(stored.get("SNAPSHOT_MANUFACTURER")).isEqualTo("현대");
            assertThat(stored.get("SNAPSHOT_MODEL_NAME")).isEqualTo("아반떼");
            assertThat(stored.get("SNAPSHOT_VEHICLE_TYPE")).isEqualTo("SEDAN");
            assertThat(stored.get("SNAPSHOT_CAR_CLASS")).isEqualTo("Mid-size");
            assertThat(((Number) stored.get("SNAPSHOT_MODEL_YEAR")).intValue()).isEqualTo(2020);
        }

        @Test
        @DisplayName("즉시 입력은 정확한 활성 모델로 차량·사고·스냅샷을 한 트랜잭션에서 만든다")
        void createsDirectVehicleAndAccident() {
            int before = vehicleCountFor(ME);

            AccidentResponse response = accidentService.create(
                    ME, direct("  현대  ", "  아반떼  ", 2020));
            flushAndClear();

            assertThat(response.vehicleInputType()).isEqualTo(VehicleInputType.DIRECT);
            assertThat(response.modelId()).isEqualTo(avante);
            assertThat(response.manufacturer()).isEqualTo("현대");
            assertThat(response.modelName()).isEqualTo("아반떼");
            assertThat(response.vehicleType()).isEqualTo(VehicleType.SEDAN);
            assertThat(response.carClass()).isEqualTo(CarClass.MID_SIZE);
            assertThat(response.modelYear()).isEqualTo(2020);
            assertThat(vehicleCountFor(ME)).isEqualTo(before + 1);
            assertThat(jdbcTemplate.queryForObject(
                    "select member_id from vehicle where vehicle_id = ?",
                    Long.class, response.vehicleId())).isEqualTo(ME);
            assertThat(vehicleService.findMine(ME))
                    .extracting(VehicleResponse::vehicleId)
                    .contains(response.vehicleId());
        }

        @Test
        @DisplayName("즉시 입력은 제조사·차량명이 정확히 일치하지 않거나 비활성 모델이면 400")
        void rejectsUnknownOrInactiveDirectModel() {
            insertModel("현대", "단종차", "SEDAN", "Mid-size", false);

            assertInvalidDirectModel(direct("기아", "아반떼", 2020));
            assertInvalidDirectModel(direct("현대", "아반떼 AD", 2020));
            assertInvalidDirectModel(direct("현대", "단종차", 2020));
        }

        @Test
        @DisplayName("즉시 입력은 같은 모델·연식 보유 차량이 있어도 새 차량을 만든다")
        void directInputAllowsDuplicateVehicles() {
            VehicleResponse existing = vehicleService.create(
                    ME, new VehicleCreateRequest(avante, 2020));

            AccidentResponse response = accidentService.create(
                    ME, direct("현대", "아반떼", 2020));
            flushAndClear();

            assertThat(response.vehicleId()).isNotEqualTo(existing.vehicleId());
            assertThat(vehicleCountFor(ME)).isEqualTo(2);
        }

        @Test
        @DisplayName("created_at 은 DB 기본값이 아니라 애플리케이션(@CreatedDate)이 채운다")
        void applicationFillsCreatedAt() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));

            AccidentResponse response =
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            flushAndClear();

            Instant stored = jdbcTemplate.queryForObject(
                    "select created_at from accident where accident_id = ?",
                    Instant.class, response.accidentId());

            // 응답의 시각은 @CreatedDate 가 PrePersist 에서 넣은 값이다.
            // DB 의 DEFAULT now() 가 적용됐다면 INSERT 시점의 서버 시계를 읽으므로
            // JDBC 왕복 시간만큼(최소 수십 마이크로초) 벌어졌을 것이다.
            // 실측 예: 애플리케이션 ...738988500Z / 저장값 ...738989Z — 마이크로초 반올림 차이뿐이다.
            assertThat(stored).isCloseTo(response.createdAt(), within(1, ChronoUnit.MICROS));
        }

        @Test
        @DisplayName("차량 조회는 1회다 — 모델 정보가 join fetch 로 함께 온다")
        void loadsVehicleInOneQuery() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            flushAndClear();

            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();

            AccidentResponse response =
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));

            // 모델 정보가 실제로 담겼는데도 쿼리는 SELECT 1 + INSERT 1 뿐이다.
            assertThat(response.modelName()).isEqualTo("아반떼");
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("같은 차량으로 사고를 두 건 접수할 수 있다")
        void allowsMultipleAccidentsPerVehicle() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));

            AccidentResponse first =
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            AccidentResponse second =
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            flushAndClear();

            assertThat(first.accidentId()).isNotEqualTo(second.accidentId());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from accident where vehicle_id = ?", Integer.class,
                    vehicle.vehicleId())).isEqualTo(2);
        }

        @Test
        @DisplayName("남의 차량은 403 이 아니라 404 NOT_FOUND")
        void otherMembersVehicleIsNotFound() {
            VehicleResponse others = vehicleService.create(OTHER, new VehicleCreateRequest(avante, 2020));
            flushAndClear();

            assertThatThrownBy(() ->
                    accidentService.create(ME, new AccidentCreateRequest(others.vehicleId())))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("없는 차량은 404 NOT_FOUND")
        void unknownVehicleIsNotFound() {
            assertThatThrownBy(() -> accidentService.create(ME, new AccidentCreateRequest(999999L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("삭제된 차량으로는 접수할 수 없다 — 404 NOT_FOUND")
        void deletedVehicleIsNotFound() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            vehicleService.delete(ME, vehicle.vehicleId());
            flushAndClear();

            assertThatThrownBy(() ->
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId())))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("실제 수리 정보 기록")
    class RecordActualRepairCost {

        @Test
        @DisplayName("소유 사고의 금액·완료일·trim 한 정비소명과 서버 기록 시각을 저장한다")
        void recordsActualRepairInformation() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse accident = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId()));
            backdateAccident(accident.accidentId(), LocalDate.of(2026, 8, 15));

            ActualRepairCostResponse response = accidentService.recordActualRepairCost(
                    ME,
                    accident.accidentId(),
                    new ActualRepairCostRequest(1_250_000, LocalDate.of(2026, 9, 1), "  바른 정비소  "));
            flushAndClear();

            Map<String, Object> stored = jdbcTemplate.queryForMap("""
                    select actual_repair_cost, actual_repair_completed_date,
                           repair_shop_name, actual_cost_recorded_at
                    from accident where accident_id = ?
                    """, accident.accidentId());

            assertThat(response.accidentId()).isEqualTo(accident.accidentId());
            assertThat(response.actualRepairCost()).isEqualTo(1_250_000);
            assertThat(response.repairCompletedDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(response.repairShopName()).isEqualTo("바른 정비소");
            assertThat(response.actualCostRecordedAt()).isNotNull();
            assertThat(((Number) stored.get("ACTUAL_REPAIR_COST")).intValue()).isEqualTo(1_250_000);
            assertThat(((Date) stored.get("ACTUAL_REPAIR_COMPLETED_DATE")).toLocalDate())
                    .isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(stored.get("REPAIR_SHOP_NAME")).isEqualTo("바른 정비소");
            assertThat(stored.get("ACTUAL_COST_RECORDED_AT")).isNotNull();
        }

        @Test
        @DisplayName("같은 사고에 다시 PUT 하면 실제 수리 정보 전체를 교체한다")
        void replacesExistingInformation() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse accident = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId()));
            backdateAccident(accident.accidentId(), LocalDate.of(2026, 8, 15));
            ActualRepairCostResponse first = accidentService.recordActualRepairCost(
                    ME, accident.accidentId(),
                    new ActualRepairCostRequest(900_000, LocalDate.of(2026, 8, 20), "첫 정비소"));

            ActualRepairCostResponse replaced = accidentService.recordActualRepairCost(
                    ME, accident.accidentId(),
                    new ActualRepairCostRequest(1_100_000, LocalDate.of(2026, 9, 2), "둘째 정비소"));
            flushAndClear();

            assertThat(replaced.actualRepairCost()).isEqualTo(1_100_000);
            assertThat(replaced.repairCompletedDate()).isEqualTo(LocalDate.of(2026, 9, 2));
            assertThat(replaced.repairShopName()).isEqualTo("둘째 정비소");
            assertThat(replaced.actualCostRecordedAt()).isAfterOrEqualTo(first.actualCostRecordedAt());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from accident where accident_id = ?",
                    Integer.class, accident.accidentId())).isEqualTo(1);
        }

        @Test
        @DisplayName("수리 완료일이 사고 접수일보다 이전이면 400이다")
        void rejectsRepairCompletedBeforeReport() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse accident = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId()));
            backdateAccident(accident.accidentId(), LocalDate.of(2026, 8, 15));

            assertThatThrownBy(() -> accidentService.recordActualRepairCost(
                    ME, accident.accidentId(),
                    new ActualRepairCostRequest(500_000, LocalDate.of(2026, 8, 14), "정비소")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

            assertThat(jdbcTemplate.queryForObject(
                    "select actual_repair_cost from accident where accident_id = ?",
                    Integer.class, accident.accidentId())).isNull();
        }

        @Test
        @DisplayName("접수 당일에 수리가 끝난 경우는 허용한다")
        void allowsRepairCompletedOnReportDate() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse accident = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId()));
            backdateAccident(accident.accidentId(), LocalDate.of(2026, 8, 15));

            ActualRepairCostResponse response = accidentService.recordActualRepairCost(
                    ME, accident.accidentId(),
                    new ActualRepairCostRequest(300_000, LocalDate.of(2026, 8, 15), "당일 정비소"));

            assertThat(response.repairCompletedDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        }

        @Test
        @DisplayName("접수일 이후 완료일은 그대로 저장된다")
        void allowsRepairCompletedAfterReportDate() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse accident = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId()));
            backdateAccident(accident.accidentId(), LocalDate.of(2026, 8, 15));

            ActualRepairCostResponse response = accidentService.recordActualRepairCost(
                    ME, accident.accidentId(),
                    new ActualRepairCostRequest(700_000, LocalDate.of(2026, 8, 16), "이튿날 정비소"));

            assertThat(response.repairCompletedDate()).isEqualTo(LocalDate.of(2026, 8, 16));
        }

        @Test
        @DisplayName("남의 사고와 없는 사고는 모두 404 NOT_FOUND다")
        void inaccessibleAccidentIsNotFound() {
            VehicleResponse othersVehicle = vehicleService.create(
                    OTHER, new VehicleCreateRequest(avante, 2020));
            AccidentResponse othersAccident = accidentService.create(
                    OTHER, new AccidentCreateRequest(othersVehicle.vehicleId()));
            ActualRepairCostRequest request = new ActualRepairCostRequest(
                    500_000, LocalDate.of(2026, 9, 1), "정비소");
            flushAndClear();

            assertThatThrownBy(() -> accidentService.recordActualRepairCost(
                    ME, othersAccident.accidentId(), request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThatThrownBy(() -> accidentService.recordActualRepairCost(ME, 999999L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("사고 스냅샷 기반 유사 사례 검색 조건")
    class SimilarCasePath {

        @Test
        @DisplayName("등록 차량과 즉시 입력은 같은 검색 조건 객체로 수렴한다")
        void bothInputsResolveToSameSearchCondition() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse registered =
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            AccidentResponse direct = accidentService.create(
                    ME, direct("현대", "아반떼", 2020));
            flushAndClear();

            assertSearchCondition(
                    searchConditionResolver.resolve(ME, registered.accidentId()), registered.accidentId());
            assertSearchCondition(
                    searchConditionResolver.resolve(ME, direct.accidentId()), direct.accidentId());
        }

        @Test
        @DisplayName("차량 수정·삭제와 모델 마스터 변경 후에도 두 분기의 응답·검색 조건은 불변이다")
        void snapshotSurvivesVehicleAndModelChangesForBothInputs() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse registered =
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            AccidentResponse direct = accidentService.create(
                    ME, direct("현대", "아반떼", 2020));

            vehicleService.update(ME, registered.vehicleId(),
                    new com.ssafy.a307.vehicle.dto.VehicleUpdateRequest(2021, null));
            vehicleService.update(ME, direct.vehicleId(),
                    new com.ssafy.a307.vehicle.dto.VehicleUpdateRequest(2022, null));
            vehicleService.delete(ME, registered.vehicleId());
            vehicleService.delete(ME, direct.vehicleId());
            jdbcTemplate.update("""
                    update vehicle_model
                    set manufacturer = '현대자동차', model_name = '아반떼 변경',
                        vehicle_type = 'SUV', car_class = 'Full-size'
                    where model_id = ?
                    """, avante);
            flushAndClear();

            AccidentVehicleSearchCondition registeredCondition =
                    searchConditionResolver.resolve(ME, registered.accidentId());
            AccidentVehicleSearchCondition directCondition =
                    searchConditionResolver.resolve(ME, direct.accidentId());
            AccidentResponse reloaded = AccidentResponse.from(
                    accidentRepository.findById(registered.accidentId()).orElseThrow());

            assertSearchCondition(registeredCondition, registered.accidentId());
            assertSearchCondition(directCondition, direct.accidentId());
            assertThat(reloaded.vehicleInputType()).isEqualTo(VehicleInputType.REGISTERED);
            assertThat(reloaded.manufacturer()).isEqualTo("현대");
            assertThat(reloaded.modelName()).isEqualTo("아반떼");
            assertThat(reloaded.vehicleType()).isEqualTo(VehicleType.SEDAN);
            assertThat(reloaded.carClass()).isEqualTo(CarClass.MID_SIZE);
            assertThat(reloaded.modelYear()).isEqualTo(2020);
        }

        @Test
        @DisplayName("검색 조건 조회도 남의 사고와 없는 사고를 404로 숨긴다")
        void inaccessibleSearchConditionIsNotFound() {
            VehicleResponse otherVehicle = vehicleService.create(
                    OTHER, new VehicleCreateRequest(avante, 2020));
            AccidentResponse otherAccident = accidentService.create(
                    OTHER, new AccidentCreateRequest(otherVehicle.vehicleId()));
            flushAndClear();

            assertThatThrownBy(() -> searchConditionResolver.resolve(ME, otherAccident.accidentId()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThatThrownBy(() -> searchConditionResolver.resolve(ME, 999999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("조회")
    class Query {

        @Test
        @DisplayName("상세 조회는 접수 당시 스냅샷을 그대로 돌려준다")
        void findOneReturnsSnapshot() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse created =
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            flushAndClear();

            AccidentResponse found = accidentService.findOne(ME, created.accidentId());

            assertThat(found.accidentId()).isEqualTo(created.accidentId());
            assertThat(found.vehicleId()).isEqualTo(vehicle.vehicleId());
            assertThat(found.vehicleInputType()).isEqualTo(VehicleInputType.REGISTERED);
            assertThat(found.modelId()).isEqualTo(avante);
            assertThat(found.manufacturer()).isEqualTo("현대");
            assertThat(found.modelName()).isEqualTo("아반떼");
            assertThat(found.vehicleType()).isEqualTo(VehicleType.SEDAN);
            assertThat(found.carClass()).isEqualTo(CarClass.MID_SIZE);
            assertThat(found.modelYear()).isEqualTo(2020);
            assertThat(found.createdAt()).isNotNull();
        }

        @Test
        @DisplayName("없는 사고와 남의 사고는 모두 404 — 존재 여부를 알려주지 않는다")
        void inaccessibleAccidentIsNotFound() {
            VehicleResponse mine = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse created =
                    accidentService.create(ME, new AccidentCreateRequest(mine.vehicleId()));
            flushAndClear();

            assertAccidentNotFound(() -> accidentService.findOne(ME, 999999L));
            assertAccidentNotFound(() -> accidentService.findOne(OTHER, created.accidentId()));
        }

        @Test
        @DisplayName("차량을 폐차·매각해도 소유자에게 사고 상세가 그대로 보인다")
        void softDeletedVehicleDoesNotHideAccidentDetail() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            AccidentResponse created =
                    accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            vehicleService.delete(ME, vehicle.vehicleId());
            flushAndClear();

            AccidentResponse found = accidentService.findOne(ME, created.accidentId());

            assertThat(found.accidentId()).isEqualTo(created.accidentId());
            assertThat(found.modelName()).isEqualTo("아반떼");
        }

        @Test
        @DisplayName("목록은 createdAt 역순이고 같은 시각이면 accidentId 역순이다")
        void listIsNewestFirstWithStableTieBreak() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            long older = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId())).accidentId();
            long tieOld = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId())).accidentId();
            long tieNew = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId())).accidentId();
            flushAndClear();

            setCreatedAt(older, Instant.parse("2026-09-01T00:00:00Z"));
            setCreatedAt(tieOld, Instant.parse("2026-09-02T00:00:00Z"));
            setCreatedAt(tieNew, Instant.parse("2026-09-02T00:00:00Z"));
            flushAndClear();

            assertThat(accidentService.findMine(ME))
                    .extracting(AccidentResponse::accidentId)
                    .containsExactly(tieNew, tieOld, older);
        }

        @Test
        @DisplayName("목록에 남의 사고가 섞이지 않는다")
        void listExcludesOtherMembersAccidents() {
            VehicleResponse mine = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            VehicleResponse theirs = vehicleService.create(OTHER, new VehicleCreateRequest(avante, 2021));
            long myAccident = accidentService.create(
                    ME, new AccidentCreateRequest(mine.vehicleId())).accidentId();
            accidentService.create(OTHER, new AccidentCreateRequest(theirs.vehicleId()));
            flushAndClear();

            assertThat(accidentService.findMine(ME))
                    .extracting(AccidentResponse::accidentId)
                    .containsExactly(myAccident);
        }

        @Test
        @DisplayName("폐차·매각한 차량의 사고도 이력 목록에 남는다")
        void listKeepsAccidentsOfSoftDeletedVehicles() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            long accidentId = accidentService.create(
                    ME, new AccidentCreateRequest(vehicle.vehicleId())).accidentId();
            vehicleService.delete(ME, vehicle.vehicleId());
            flushAndClear();

            assertThat(accidentService.findMine(ME))
                    .extracting(AccidentResponse::accidentId)
                    .containsExactly(accidentId);
        }

        @Test
        @DisplayName("사고가 한 건도 없으면 예외가 아니라 빈 목록이다")
        void listIsEmptyWhenMemberHasNoAccident() {
            assertThat(accidentService.findMine(ME)).isEmpty();
        }

        @Test
        @DisplayName("페이지네이션 — 첫 페이지는 최신순으로 size 만큼만, 메타가 정확하다")
        void pagedListReturnsFirstPageWithMeta() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            for (int i = 0; i < 3; i++) {
                accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            }
            flushAndClear();

            AccidentPageResponse first = accidentService.findMinePaged(ME, 0, 2, false);

            assertThat(first.accidents()).hasSize(2);
            assertThat(first.page()).isZero();
            assertThat(first.size()).isEqualTo(2);
            assertThat(first.totalElements()).isEqualTo(3);
            assertThat(first.totalPages()).isEqualTo(2);
            assertThat(first.hasNext()).isTrue();
        }

        @Test
        @DisplayName("페이지네이션 — 마지막 페이지는 hasNext 가 false 이고 남은 건수만 준다")
        void pagedListMarksLastPage() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            for (int i = 0; i < 3; i++) {
                accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            }
            flushAndClear();

            AccidentPageResponse last = accidentService.findMinePaged(ME, 1, 2, false);

            assertThat(last.accidents()).hasSize(1);
            assertThat(last.hasNext()).isFalse();
        }

        @Test
        @DisplayName("페이지네이션 — 범위를 넘는 page 는 404 가 아니라 빈 목록이다")
        void pagedListReturnsEmptyBeyondLastPage() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            flushAndClear();

            AccidentPageResponse beyond = accidentService.findMinePaged(ME, 99, 20, false);

            assertThat(beyond.accidents()).isEmpty();
            assertThat(beyond.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("페이지네이션 — 파라미터가 없으면 첫 페이지 20건이다")
        void pagedListAppliesDefaults() {
            AccidentPageResponse defaults = accidentService.findMinePaged(ME, null, null, false);

            assertThat(defaults.page()).isZero();
            assertThat(defaults.size()).isEqualTo(AccidentService.DEFAULT_PAGE_SIZE);
        }

        @Test
        @DisplayName("페이지네이션 — size 상한을 넘기면 100 으로 줄이고, 음수 page 는 0 으로 보정한다")
        void pagedListClampsOutOfRangeParams() {
            AccidentPageResponse clamped = accidentService.findMinePaged(ME, -5, 500, false);

            assertThat(clamped.page()).isZero();
            assertThat(clamped.size()).isEqualTo(AccidentService.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("페이지네이션 — 남의 사고는 총 건수에도 포함되지 않는다")
        void pagedListExcludesOtherMembersFromTotal() {
            VehicleResponse mine = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            VehicleResponse theirs = vehicleService.create(OTHER, new VehicleCreateRequest(avante, 2021));
            accidentService.create(ME, new AccidentCreateRequest(mine.vehicleId()));
            accidentService.create(OTHER, new AccidentCreateRequest(theirs.vehicleId()));
            flushAndClear();

            assertThat(accidentService.findMinePaged(ME, 0, 20, false).totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("페이지네이션 — 폐차 차량의 사고도 포함되고 스냅샷 값을 그대로 준다")
        void pagedListKeepsSoftDeletedVehicleSnapshot() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            vehicleService.delete(ME, vehicle.vehicleId());
            flushAndClear();

            AccidentPageResponse paged = accidentService.findMinePaged(ME, 0, 20, false);

            assertThat(paged.accidents()).hasSize(1);
            assertThat(paged.accidents().getFirst().manufacturer()).isEqualTo("현대");
            assertThat(paged.accidents().getFirst().modelYear()).isEqualTo(2020);
        }
    }

    private void assertAccidentNotFound(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .hasMessage("사고를 찾을 수 없습니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Nested
    @DisplayName("이력 목록의 상태·장수·예상 비용 (Task 225)")
    class HistoryDetails {

        @Test
        @DisplayName("이미지가 없으면 RECEIVED 이고 장수 0, 썸네일과 예상 비용은 null 이다")
        void receivedWhenNoImage() {
            long accidentId = openAccident();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.accidentId()).isEqualTo(accidentId);
            assertThat(summary.status()).isEqualTo(AccidentHistoryStatus.RECEIVED);
            assertThat(summary.imageCount()).isZero();
            assertThat(summary.thumbnailUrl()).isNull();
            assertThat(summary.thumbnailExpiresAt()).isNull();
            assertThat(summary.estimatedCostMin()).isNull();
            assertThat(summary.estimatedCostMedian()).isNull();
            assertThat(summary.estimatedCostMax()).isNull();
        }

        @Test
        @DisplayName("이미지가 있으면 IMAGES_UPLOADED 이고 장수를 정확히 센다")
        void imagesUploadedCountsEveryImage() {
            long accidentId = openAccident();
            insertImage(accidentId, "front.jpg");
            insertImage(accidentId, "rear.jpg");
            insertImage(accidentId, "left.jpg");
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.status()).isEqualTo(AccidentHistoryStatus.IMAGES_UPLOADED);
            assertThat(summary.imageCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("완료 통보를 못 받은 이미지도 장수에 센다 — 화면의 N장은 사용자가 올린 수다")
        void countsImagesWithoutAssets() {
            long accidentId = openAccident();
            insertImage(accidentId, "pending.jpg");
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.imageCount()).isEqualTo(1);
            assertThat(summary.thumbnailUrl()).isNull();
        }

        @Test
        @DisplayName("견적이 있으면 ESTIMATED 이고 총액 범위를 준다")
        void estimatedExposesCostRange() {
            long accidentId = openAccident();
            insertImage(accidentId, "front.jpg");
            insertEstimate(accidentId, 1, true, 800_000, 1_000_000, 1_300_000);
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.status()).isEqualTo(AccidentHistoryStatus.ESTIMATED);
            assertThat(summary.estimatedCostMin()).isEqualTo(800_000);
            assertThat(summary.estimatedCostMedian()).isEqualTo(1_000_000);
            assertThat(summary.estimatedCostMax()).isEqualTo(1_300_000);
        }

        @Test
        @DisplayName("산정 불가 견적은 무시한다 — 화면에 예상 0원으로 보이면 안 된다")
        void ignoresNonEstimableEstimate() {
            long accidentId = openAccident();
            insertEstimate(accidentId, 1, false, null, null, null);
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.status()).isEqualTo(AccidentHistoryStatus.RECEIVED);
            assertThat(summary.estimatedCostMedian()).isNull();
        }

        @Test
        @DisplayName("견적이 여러 번 산정됐으면 최신 것을 쓴다")
        void usesLatestEstimate() {
            long accidentId = openAccident();
            long oldJob = insertEstimate(accidentId, 1, true, 100, 200, 300);
            backdateEstimate(oldJob, Instant.parse("2026-09-01T00:00:00Z"));
            insertEstimate(accidentId, 1, true, 900_000, 1_100_000, 1_400_000);
            flushAndClear();

            assertThat(onlyAccident().estimatedCostMedian()).isEqualTo(1_100_000);
        }

        @Test
        @DisplayName("남의 사고는 목록에 없고 그 견적도 새 나가지 않는다")
        void otherMembersAccidentIsInvisible() {
            long mine = openAccident();
            insertEstimate(mine, 1, true, 1, 2, 3);

            VehicleResponse otherVehicle =
                    vehicleService.create(OTHER, new VehicleCreateRequest(avante, 2021));
            long theirs = accidentService
                    .create(OTHER, new AccidentCreateRequest(otherVehicle.vehicleId()))
                    .accidentId();
            insertEstimate(theirs, 1, true, 9_000_000, 9_000_000, 9_000_000);
            flushAndClear();

            AccidentPageResponse paged = accidentService.findMinePaged(ME, 0, 20, false);

            assertThat(paged.totalElements()).isEqualTo(1);
            assertThat(paged.accidents()).singleElement()
                    .satisfies(only -> {
                        assertThat(only.accidentId()).isEqualTo(mine);
                        assertThat(only.estimatedCostMedian()).isEqualTo(2);
                    });
        }

        @Test
        @DisplayName("분석이 돌고 있으면 ANALYZING 이다 — QUEUED 와 PROCESSING 을 나누지 않는다")
        void analyzingWhileJobIsRunning() {
            long queued = openAccident();
            insertJob(queued, "QUEUED");
            long processing = openAccident();
            insertJob(processing, "PROCESSING");
            flushAndClear();

            assertThat(accidentService.findMinePaged(ME, 0, 20, false).accidents())
                    .extracting(AccidentSummaryResponse::status)
                    .containsOnly(AccidentHistoryStatus.ANALYZING);
        }

        @Test
        @DisplayName("분석이 실패했으면 ANALYSIS_FAILED 다")
        void analysisFailed() {
            long accidentId = openAccident();
            insertImage(accidentId, "front.jpg");
            insertJob(accidentId, "FAILED");
            flushAndClear();

            assertThat(onlyAccident().status()).isEqualTo(AccidentHistoryStatus.ANALYSIS_FAILED);
        }

        @Test
        @DisplayName("이미지가 0장이어도 작업이 있으면 분석 상태가 이긴다")
        void analysisStatusBeatsImageCount() {
            long accidentId = openAccident();
            insertJob(accidentId, "PROCESSING");
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.imageCount()).isZero();
            assertThat(summary.status()).isEqualTo(AccidentHistoryStatus.ANALYZING);
        }

        @Test
        @DisplayName("견적이 있어도 최신 재분석이 실패했으면 ANALYSIS_FAILED 다 — 실패가 묻히면 안 된다")
        void failedReanalysisBeatsOldEstimate() {
            long accidentId = openAccident();
            long estimatedJob = insertEstimate(accidentId, 1, true, 800_000, 1_000_000, 1_300_000);
            backdateJob(estimatedJob, Instant.parse("2026-09-01T00:00:00Z"));
            insertJob(accidentId, "FAILED");
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.status()).isEqualTo(AccidentHistoryStatus.ANALYSIS_FAILED);
            // 실패했다고 예전 견적의 금액까지 지우지는 않는다 — 화면이 둘을 함께 보여줄 수 있다.
            assertThat(summary.estimatedCostMedian()).isEqualTo(1_000_000);
        }

        @Test
        @DisplayName("재분석 중이어도 예전 견적의 금액은 그대로 남는다")
        void reanalysisKeepsPreviousAmounts() {
            long accidentId = openAccident();
            long estimatedJob = insertEstimate(accidentId, 1, true, 800_000, 1_000_000, 1_300_000);
            backdateJob(estimatedJob, Instant.parse("2026-09-01T00:00:00Z"));
            insertJob(accidentId, "PROCESSING");
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.status()).isEqualTo(AccidentHistoryStatus.ANALYZING);
            assertThat(summary.estimatedCostMedian()).isEqualTo(1_000_000);
            assertThat(summary.estimateId()).isNotNull();
        }

        @Test
        @DisplayName("분석이 끝나고 견적이 있으면 ESTIMATED 다")
        void completedJobWithEstimateIsEstimated() {
            long accidentId = openAccident();
            insertEstimate(accidentId, 1, true, 800_000, 1_000_000, 1_300_000);
            flushAndClear();

            assertThat(onlyAccident().status()).isEqualTo(AccidentHistoryStatus.ESTIMATED);
        }

        @Test
        @DisplayName("estimateId 를 준다 — PDF 진입 경로다")
        void exposesEstimateId() {
            long accidentId = openAccident();
            long jobId = insertEstimate(accidentId, 1, true, 800_000, 1_000_000, 1_300_000);
            flushAndClear();

            Long expected = jdbcTemplate.queryForObject(
                    "select estimate_id from estimate where job_id = ?", Long.class, jobId);
            assertThat(onlyAccident().estimateId()).isEqualTo(expected);
        }

        @Test
        @DisplayName("견적이 없으면 estimateId 는 null 이다 — 0 이나 -1 같은 마법값을 쓰지 않는다")
        void estimateIdIsNullWithoutEstimate() {
            openAccident();

            assertThat(onlyAccident().estimateId()).isNull();
        }

        @Test
        @DisplayName("estimateId 는 금액과 같은 견적 행을 가리킨다")
        void estimateIdMatchesTheAmountRow() {
            long accidentId = openAccident();
            long oldJob = insertEstimate(accidentId, 1, true, 100, 200, 300);
            backdateEstimate(oldJob, Instant.parse("2026-09-01T00:00:00Z"));
            long newJob = insertEstimate(accidentId, 1, true, 900_000, 1_100_000, 1_400_000);
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();
            Long latest = jdbcTemplate.queryForObject(
                    "select estimate_id from estimate where job_id = ?", Long.class, newJob);

            assertThat(summary.estimatedCostMedian()).isEqualTo(1_100_000);
            assertThat(summary.estimateId()).isEqualTo(latest);
        }

        @Test
        @DisplayName("산정 불가 견적뿐이면 estimateId 도 null 이다 — 금액과 같은 행에서 뽑기 때문이다")
        void nonEstimableGivesNoEstimateId() {
            long accidentId = openAccident();
            insertEstimate(accidentId, 1, false, null, null, null);
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.estimateId()).isNull();
            assertThat(summary.estimatedCostMedian()).isNull();
        }

        /**
         * S15P21A307-552. 체크리스트는 <b>산정 불가 견적에도 만들어진다</b>. 화면이
         * {@code estimateId} 로 체크리스트 유무를 가늠하다가 그 사고의 체크리스트를 통째로
         * 숨겼다 — 운영에서 7건 중 5건이 보이지 않았다.
         */
        @Test
        @DisplayName("산정 불가 사고여도 체크리스트 상태를 준다 — estimateId 로 가늠하지 않는다")
        void checklistStatusSurvivesNonEstimable() {
            long accidentId = openAccident();
            insertEstimate(accidentId, 1, false, null, null, null);
            insertChecklist(accidentId, "COMPLETED");
            flushAndClear();

            AccidentSummaryResponse summary = onlyAccident();

            assertThat(summary.estimateId()).isNull();
            assertThat(summary.checklistStatus()).isEqualTo("COMPLETED");
        }

        /** 만드는 중인 것도 화면이 "만들고 있어요" 로 보여 준다 — 없는 것과 구분해야 한다. */
        @Test
        @DisplayName("생성 중인 체크리스트도 상태 그대로 준다")
        void checklistStatusCarriesInProgress() {
            long accidentId = openAccident();
            insertEstimate(accidentId, 1, true, 100, 200, 300);
            insertChecklist(accidentId, "QUEUED");
            flushAndClear();

            assertThat(onlyAccident().checklistStatus()).isEqualTo("QUEUED");
        }

        @Test
        @DisplayName("체크리스트를 만든 적이 없으면 null 이다")
        void checklistStatusIsNullWithoutChecklist() {
            long accidentId = openAccident();
            insertEstimate(accidentId, 1, true, 100, 200, 300);
            flushAndClear();

            assertThat(onlyAccident().checklistStatus()).isNull();
        }

        @Test
        @DisplayName("페이지 크기와 무관하게 추가 쿼리가 3개다 — 건마다 조회하지 않는다")
        void enrichmentDoesNotScaleWithPageSize() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            for (int i = 0; i < 5; i++) {
                long accidentId = accidentService
                        .create(ME, new AccidentCreateRequest(vehicle.vehicleId())).accidentId();
                insertImage(accidentId, "front" + i + ".jpg");
                insertEstimate(accidentId, 1, true, 100, 200, 300);
            }
            flushAndClear();

            Statistics statistics =
                    entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.setStatisticsEnabled(true);
            statistics.clear();

            AccidentPageResponse paged = accidentService.findMinePaged(ME, 0, 20, false);

            assertThat(paged.accidents()).hasSize(5);
            // 목록 1 + 장수·썸네일·견적 3 = 4. 사고 건수(5)에 비례하지 않는 것이 요점이다.
            // count 쿼리는 나가지 않는다 — 첫 페이지에 전체가 들어가면 Spring Data 가
            // 생략한다(PageableExecutionUtils). 그래서 5가 아니라 4다.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        }

        /**
         * S15P21A307-554. 화면이 localStorage 로 하던 숨기기를 서버로 옮긴 것이다 —
         * 그쪽은 기기를 바꾸거나 캐시를 지우면 감춘 것이 다시 나타났다.
         */
        @Test
        @DisplayName("감춘 사고는 목록에서 빠진다")
        void hiddenAccidentLeavesTheList() {
            long accidentId = openAccident();

            accidentService.setHidden(ME, accidentId, true);
            flushAndClear();

            assertThat(accidentService.findMinePaged(ME, 0, 20, false).accidents()).isEmpty();
        }

        /** 화면의 "숨긴 이력 나타내기" 가 이 경로로 받는다. */
        @Test
        @DisplayName("숨긴 것까지 달라고 하면 감춘 시각과 함께 나온다")
        void includeHiddenBringsItBack() {
            long accidentId = openAccident();
            accidentService.setHidden(ME, accidentId, true);
            flushAndClear();

            List<AccidentSummaryResponse> accidents =
                    accidentService.findMinePaged(ME, 0, 20, true).accidents();

            assertThat(accidents).hasSize(1);
            assertThat(accidents.getFirst().hiddenAt()).isNotNull();
        }

        @Test
        @DisplayName("되돌리면 다시 보이고 감춘 시각이 비워진다")
        void unhideRestoresTheAccident() {
            long accidentId = openAccident();
            accidentService.setHidden(ME, accidentId, true);
            flushAndClear();

            accidentService.setHidden(ME, accidentId, false);
            flushAndClear();

            assertThat(onlyAccident().hiddenAt()).isNull();
        }

        /** 화면이 토글을 두 번 눌러도 "언제부터 안 보였나" 가 흔들리면 안 된다. */
        @Test
        @DisplayName("두 번 감춰도 처음 감춘 시각을 지킨다")
        void hidingTwiceKeepsTheFirstMoment() {
            long accidentId = openAccident();
            accidentService.setHidden(ME, accidentId, true);
            flushAndClear();
            Instant first = accidentService.findMinePaged(ME, 0, 20, true)
                    .accidents().getFirst().hiddenAt();

            accidentService.setHidden(ME, accidentId, true);
            flushAndClear();

            assertThat(accidentService.findMinePaged(ME, 0, 20, true)
                    .accidents().getFirst().hiddenAt()).isEqualTo(first);
        }

        /**
         * {@code @CreatedDate} 가 {@code hiddenAt} 에 붙으면 접수하는 순간 값이 채워져
         * <b>모든 새 사고가 감춰진 채로 태어난다.</b> 한 번 그렇게 끼워 넣은 적이 있다.
         */
        @Test
        @DisplayName("새로 접수한 사고는 감춰져 있지 않다")
        void newAccidentIsVisible() {
            openAccident();

            assertThat(onlyAccident().hiddenAt()).isNull();
        }

        /** 없는 사고와 남의 사고를 구분하지 않는다 — 403 은 그 사고가 있다는 사실을 알려 준다. */
        @Test
        @DisplayName("없는 사고를 감추려 하면 404 다")
        void hidingUnknownAccidentIsNotFound() {
            assertThatThrownBy(() -> accidentService.setHidden(ME, 999_999L, true))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
        }

        private long openAccident() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            long accidentId = accidentService
                    .create(ME, new AccidentCreateRequest(vehicle.vehicleId())).accidentId();
            flushAndClear();
            return accidentId;
        }

        private AccidentSummaryResponse onlyAccident() {
            AccidentPageResponse paged = accidentService.findMinePaged(ME, 0, 20, false);
            assertThat(paged.accidents()).hasSize(1);
            return paged.accidents().getFirst();
        }
    }

    /** @CreatedDate 가 잡는 시각을 고정해 정렬 2차 키(accidentId)를 검증할 수 있게 한다. */
    private void setCreatedAt(long accidentId, Instant createdAt) {
        jdbcTemplate.update("update accident set created_at = ? where accident_id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), accidentId);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * 접수 시각을 과거로 돌린다.
     *
     * <p>수리 완료일 하한 검증이 <b>사고 접수일</b>을 기준으로 하므로, 접수 직후에 만든 사고에
     * 과거 날짜를 기록하려 하면 거절된다. 실제 서비스에서는 사고를 접수하고 며칠 뒤 수리가 끝나므로,
     * 그 상황을 재현하려면 접수 시각을 뒤로 옮겨야 한다.
     *
     * <p>{@code created_at} 은 JPA 에서 {@code updatable = false} 라 JDBC 로 직접 바꾸고,
     * 영속성 컨텍스트가 옛 값을 들고 있지 않도록 비운다.
     */
    private void backdateAccident(long accidentId, LocalDate reportedOn) {
        jdbcTemplate.update(
                "update accident set created_at = ? where accident_id = ?",
                Timestamp.from(reportedOn.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()),
                accidentId);
        flushAndClear();
    }

    private void insertMember(long memberId, String providerUserId) {
        jdbcTemplate.update(
                "insert into member (member_id, provider, provider_user_id, nickname) values (?, 'KAKAO', ?, ?)",
                memberId, providerUserId, "tester" + memberId);
    }

    private long insertModel(String manufacturer, String modelName,
                             String vehicleType, String carClass) {
        return insertModel(manufacturer, modelName, vehicleType, carClass, true);
    }

    private long insertModel(String manufacturer, String modelName,
                             String vehicleType, String carClass, boolean active) {
        jdbcTemplate.update(
                "insert into vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)"
                        + " values (?, ?, ?, ?, ?)",
                manufacturer, modelName, vehicleType, carClass, active);
        return jdbcTemplate.queryForObject(
                "select model_id from vehicle_model where manufacturer = ? and model_name = ?",
                Long.class, manufacturer, modelName);
    }

    private AccidentCreateRequest direct(String manufacturer, String modelName, int modelYear) {
        return new AccidentCreateRequest(
                null, new DirectVehicleInput(manufacturer, modelName, modelYear));
    }

    private void assertInvalidDirectModel(AccidentCreateRequest request) {
        assertThatThrownBy(() -> accidentService.create(ME, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    private int vehicleCountFor(long memberId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from vehicle where member_id = ?", Integer.class, memberId);
    }

    private void assertSearchCondition(
            AccidentVehicleSearchCondition condition, Long accidentId) {
        assertThat(condition.accidentId()).isEqualTo(accidentId);
        assertThat(condition.modelId()).isEqualTo(avante);
        assertThat(condition.manufacturer()).isEqualTo("현대");
        assertThat(condition.modelName()).isEqualTo("아반떼");
        assertThat(condition.carClass()).isEqualTo(CarClass.MID_SIZE);
        assertThat(condition.modelYear()).isEqualTo(2020);
    }

    /** 사고 이미지 한 장. asset 은 넣지 않는다 — 완료 통보 전 상태다. */
    private long insertImage(long accidentId, String filename) {
        jdbcTemplate.update(
                "insert into accident_image (accident_id, original_filename) values (?, ?)",
                accidentId, filename);
        return jdbcTemplate.queryForObject(
                "select max(image_id) from accident_image where accident_id = ?",
                Long.class, accidentId);
    }

    private void insertAsset(long imageId, String variant, String s3Key) {
        jdbcTemplate.update(
                "insert into accident_image_asset (image_id, variant, s3_key, width, height, file_size)"
                        + " values (?, ?, ?, 320, 240, 14802)",
                imageId, variant, s3Key);
    }

    /**
     * 분석 작업 + 견적 한 벌. {@code estimate} 는 {@code analysis_job} 을 거쳐 사고에 매달리므로
     * 둘을 함께 만든다.
     *
     * @return 만든 job_id
     */
    private long insertEstimate(long accidentId, int version, boolean estimable,
                                Integer min, Integer median, Integer max) {
        jdbcTemplate.update(
                "insert into analysis_job (accident_id, status) values (?, 'COMPLETED')", accidentId);
        long jobId = jdbcTemplate.queryForObject(
                "select max(job_id) from analysis_job where accident_id = ?", Long.class, accidentId);
        jdbcTemplate.update(
                "insert into estimate (job_id, version, is_estimable, total_min, total_median, total_max)"
                        + " values (?, ?, ?, ?, ?, ?)",
                jobId, version, estimable, min, median, max);
        return jobId;
    }

    /** 사고당 한 건이다 ({@code uk_rcl_accident}). 나머지 열은 기본값으로 둔다. */
    private void insertChecklist(long accidentId, String status) {
        jdbcTemplate.update(
                "insert into repair_checklist (accident_id, status) values (?, ?)",
                accidentId, status);
    }

    /** 최신 견적 판정이 created_at 을 먼저 보므로, 오래된 견적을 만들려면 시각을 뒤로 옮긴다. */
    private void backdateEstimate(long jobId, Instant createdAt) {
        jdbcTemplate.update("update estimate set created_at = ? where job_id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), jobId);
    }

    /** 견적 없는 분석 작업 한 건. 상태 배지가 {@code analysis_job.status} 를 보는지 확인할 때 쓴다. */
    private long insertJob(long accidentId, String status) {
        jdbcTemplate.update(
                "insert into analysis_job (accident_id, status) values (?, ?)", accidentId, status);
        return jdbcTemplate.queryForObject(
                "select max(job_id) from analysis_job where accident_id = ?", Long.class, accidentId);
    }

    /** 최신 작업 판정이 created_at 을 먼저 보므로, 오래된 작업을 만들려면 시각을 뒤로 옮긴다. */
    private void backdateJob(long jobId, Instant createdAt) {
        jdbcTemplate.update("update analysis_job set created_at = ? where job_id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), jobId);
    }
}
