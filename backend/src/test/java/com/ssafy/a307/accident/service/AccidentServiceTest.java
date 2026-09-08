package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
import com.ssafy.a307.accident.dto.AccidentPageResponse;
import com.ssafy.a307.accident.dto.AccidentResponse;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

            AccidentPageResponse first = accidentService.findMinePaged(ME, 0, 2);

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

            AccidentPageResponse last = accidentService.findMinePaged(ME, 1, 2);

            assertThat(last.accidents()).hasSize(1);
            assertThat(last.hasNext()).isFalse();
        }

        @Test
        @DisplayName("페이지네이션 — 범위를 넘는 page 는 404 가 아니라 빈 목록이다")
        void pagedListReturnsEmptyBeyondLastPage() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            flushAndClear();

            AccidentPageResponse beyond = accidentService.findMinePaged(ME, 99, 20);

            assertThat(beyond.accidents()).isEmpty();
            assertThat(beyond.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("페이지네이션 — 파라미터가 없으면 첫 페이지 20건이다")
        void pagedListAppliesDefaults() {
            AccidentPageResponse defaults = accidentService.findMinePaged(ME, null, null);

            assertThat(defaults.page()).isZero();
            assertThat(defaults.size()).isEqualTo(AccidentService.DEFAULT_PAGE_SIZE);
        }

        @Test
        @DisplayName("페이지네이션 — size 상한을 넘기면 100 으로 줄이고, 음수 page 는 0 으로 보정한다")
        void pagedListClampsOutOfRangeParams() {
            AccidentPageResponse clamped = accidentService.findMinePaged(ME, -5, 500);

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

            assertThat(accidentService.findMinePaged(ME, 0, 20).totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("페이지네이션 — 폐차 차량의 사고도 포함되고 스냅샷 값을 그대로 준다")
        void pagedListKeepsSoftDeletedVehicleSnapshot() {
            VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            accidentService.create(ME, new AccidentCreateRequest(vehicle.vehicleId()));
            vehicleService.delete(ME, vehicle.vehicleId());
            flushAndClear();

            AccidentPageResponse paged = accidentService.findMinePaged(ME, 0, 20);

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

    /** @CreatedDate 가 잡는 시각을 고정해 정렬 2차 키(accidentId)를 검증할 수 있게 한다. */
    private void setCreatedAt(long accidentId, Instant createdAt) {
        jdbcTemplate.update("update accident set created_at = ? where accident_id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), accidentId);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
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
}
