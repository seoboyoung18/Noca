package com.ssafy.a307.vehicle.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.vehicle.dto.VehicleCreateRequest;
import com.ssafy.a307.vehicle.dto.VehicleResponse;
import com.ssafy.a307.vehicle.dto.VehicleUpdateRequest;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.Vehicle;
import com.ssafy.a307.vehicle.entity.VehicleType;
import com.ssafy.a307.vehicle.repository.VehicleRepository;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 인증이 아직 없어도 memberId 를 넘기기만 하면 5개 API 의 로직을 전부 검증할 수 있다.
 */
@SpringBootTest
@Transactional
@DisplayName("VehicleService")
class VehicleServiceTest {

    private static final long ME = 1L;
    private static final long OTHER = 2L;

    @Autowired
    private VehicleService vehicleService;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private long avante;
    private long k5;
    private long discontinued;

    @BeforeEach
    void setUp() {
        insertMember(ME, "kakao-me");
        insertMember(OTHER, "kakao-other");
        avante = insertModel("현대", "아반떼", "SEDAN", "Compact", true);
        k5 = insertModel("기아", "K5", "SEDAN", "Mid-size", true);
        discontinued = insertModel("기아", "프라이드", "SEDAN", "Compact", false);
    }

    @Nested
    @DisplayName("등록")
    class Create {

        @Test
        @DisplayName("모델 정보를 함께 담아 돌려준다")
        void returnsModelInfo() {
            VehicleResponse response =
                    vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));

            assertThat(response.vehicleId()).isNotNull();
            assertThat(response.modelId()).isEqualTo(avante);
            assertThat(response.manufacturer()).isEqualTo("현대");
            assertThat(response.modelName()).isEqualTo("아반떼");
            assertThat(response.vehicleType()).isEqualTo(VehicleType.SEDAN);
            assertThat(response.carClass()).isEqualTo(CarClass.COMPACT);
            assertThat(response.modelYear()).isEqualTo(2020);
        }

        @Test
        @DisplayName("같은 모델·연식을 두 번 등록해도 둘 다 성공한다")
        void allowsDuplicates() {
            VehicleResponse first = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            VehicleResponse second = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));

            assertThat(first.vehicleId()).isNotEqualTo(second.vehicleId());
            assertThat(vehicleService.findMine(ME)).hasSize(2);
        }

        @Test
        @DisplayName("연식 경계값 1980·2100 은 DB CHECK 제약을 통과한다")
        void acceptsBoundaryYears() {
            vehicleService.create(ME, new VehicleCreateRequest(avante, 1980));
            vehicleService.create(ME, new VehicleCreateRequest(avante, 2100));
            entityManager.flush();

            assertThat(vehicleService.findMine(ME))
                    .extracting(VehicleResponse::modelYear)
                    .containsExactlyInAnyOrder(1980, 2100);
        }

        @Test
        @DisplayName("Bean Validation 을 우회해 1979 가 들어오면 DB CHECK 제약이 막는다")
        void databaseRejectsOutOfRangeYear() {
            assertThatThrownBy(() -> {
                vehicleService.create(ME, new VehicleCreateRequest(avante, 1979));
                entityManager.flush();
            }).hasStackTraceContaining("CK_V_YEAR");
        }

        @Test
        @DisplayName("없는 모델은 400 INVALID_REQUEST")
        void rejectsUnknownModel() {
            assertThatThrownBy(() -> vehicleService.create(ME, new VehicleCreateRequest(999999L, 2020)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("is_active = false 인 모델은 400 INVALID_REQUEST")
        void rejectsInactiveModel() {
            assertThatThrownBy(() -> vehicleService.create(ME, new VehicleCreateRequest(discontinued, 2020)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_REQUEST);
        }
    }

    @Nested
    @DisplayName("내 차량 목록")
    class FindMine {

        @Test
        @DisplayName("차량이 없으면 빈 목록")
        void emptyWhenNone() {
            assertThat(vehicleService.findMine(ME)).isEmpty();
        }

        @Test
        @DisplayName("삭제된 차량과 남의 차량은 빠진다")
        void excludesDeletedAndOthers() {
            VehicleResponse mine = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            VehicleResponse toDelete = vehicleService.create(ME, new VehicleCreateRequest(k5, 2019));
            vehicleService.create(OTHER, new VehicleCreateRequest(k5, 2018));
            vehicleService.delete(ME, toDelete.vehicleId());
            flushAndClear();

            assertThat(vehicleService.findMine(ME))
                    .extracting(VehicleResponse::vehicleId)
                    .containsExactly(mine.vehicleId());
        }

        @Test
        @DisplayName("created_at DESC 로 최근 등록한 차가 먼저 온다")
        void sortedByCreatedAtDesc() {
            VehicleResponse older = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            VehicleResponse newer = vehicleService.create(ME, new VehicleCreateRequest(k5, 2021));
            flushAndClear();
            // created_at 은 애플리케이션이 채우므로 두 건의 시각이 같을 수 있다. 순서를 명시적으로 벌린다.
            jdbcTemplate.update("update vehicle set created_at = ? where vehicle_id = ?",
                    Instant.now().minusSeconds(60), older.vehicleId());

            assertThat(vehicleService.findMine(ME))
                    .extracting(VehicleResponse::vehicleId)
                    .containsExactly(newer.vehicleId(), older.vehicleId());
        }

        @Test
        @DisplayName("차량이 몇 대든 쿼리는 1번 — N+1 이 없다")
        void noNPlusOne() {
            vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            vehicleService.create(ME, new VehicleCreateRequest(k5, 2021));
            vehicleService.create(ME, new VehicleCreateRequest(avante, 2022));
            flushAndClear();

            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();

            List<VehicleResponse> vehicles = vehicleService.findMine(ME);

            assertThat(vehicles).hasSize(3);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("수정")
    class Update {

        @Test
        @DisplayName("연식을 바꾸고 updated_at 을 갱신한다 — DB 트리거가 없으므로 애플리케이션이 채운다")
        void updatesModelYearAndTimestamp() throws InterruptedException {
            VehicleResponse created = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            flushAndClear();
            Instant before = updatedAtOf(created.vehicleId());

            // H2 TIMESTAMP WITH TIME ZONE은 마이크로초 정밀도라 생성·수정이 같은 틱이면
            // 서로 다른 @LastModifiedDate 값도 동일하게 반올림된다.
            Thread.sleep(1);

            VehicleResponse updated = vehicleService.update(
                    ME, created.vehicleId(), new VehicleUpdateRequest(2021, null));
            flushAndClear();

            assertThat(updated.modelYear()).isEqualTo(2021);
            assertThat(updated.modelName()).isEqualTo("아반떼");
            assertThat(updatedAtOf(created.vehicleId())).isAfter(before);
        }

        @Test
        @DisplayName("남의 차량은 404 NOT_FOUND")
        void otherMembersVehicleIsNotFound() {
            VehicleResponse others = vehicleService.create(OTHER, new VehicleCreateRequest(avante, 2020));
            flushAndClear();

            assertThatThrownBy(() -> vehicleService.update(
                    ME, others.vehicleId(), new VehicleUpdateRequest(2021, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("삭제된 차량은 404 NOT_FOUND")
        void deletedVehicleIsNotFound() {
            VehicleResponse created = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            vehicleService.delete(ME, created.vehicleId());
            flushAndClear();

            assertThatThrownBy(() -> vehicleService.update(
                    ME, created.vehicleId(), new VehicleUpdateRequest(2021, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("없는 차량은 404 NOT_FOUND")
        void unknownVehicleIsNotFound() {
            assertThatThrownBy(() -> vehicleService.update(
                    ME, 999999L, new VehicleUpdateRequest(2021, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("목록에서만 사라지고 행은 남는다")
        void softDeletesOnly() {
            VehicleResponse created = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            vehicleService.delete(ME, created.vehicleId());
            flushAndClear();

            assertThat(vehicleService.findMine(ME)).isEmpty();

            Vehicle row = vehicleRepository.findById(created.vehicleId()).orElseThrow();
            assertThat(row.isDeleted()).isTrue();
            assertThat(countVehicleRows(created.vehicleId())).isEqualTo(1);
        }

        @Test
        @DisplayName("사고 이력이 있어도 삭제된다 — accident 의 ON DELETE RESTRICT 에 걸리지 않는다")
        void deletesEvenWithAccidentHistory() {
            VehicleResponse created = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            flushAndClear();
            jdbcTemplate.update("""
                    insert into accident(
                        vehicle_id, vehicle_input_type,
                        snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                        snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                    values (?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Mid-size', 2020)
                    """, created.vehicleId(), avante);

            vehicleService.delete(ME, created.vehicleId());
            flushAndClear();

            assertThat(vehicleService.findMine(ME)).isEmpty();
            assertThat(countVehicleRows(created.vehicleId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from accident where vehicle_id = ?", Integer.class,
                    created.vehicleId())).isEqualTo(1);
        }

        @Test
        @DisplayName("이미 삭제된 차량을 다시 삭제해도 성공한다 — 멱등")
        void isIdempotent() {
            VehicleResponse created = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
            vehicleService.delete(ME, created.vehicleId());
            flushAndClear();
            Instant firstDeletedAt = vehicleRepository.findById(created.vehicleId())
                    .orElseThrow().getDeletedAt();

            vehicleService.delete(ME, created.vehicleId());
            flushAndClear();

            assertThat(vehicleRepository.findById(created.vehicleId()).orElseThrow().getDeletedAt())
                    .isEqualTo(firstDeletedAt);
        }

        @Test
        @DisplayName("남의 차량은 404 NOT_FOUND")
        void otherMembersVehicleIsNotFound() {
            VehicleResponse others = vehicleService.create(OTHER, new VehicleCreateRequest(avante, 2020));
            flushAndClear();

            assertThatThrownBy(() -> vehicleService.delete(ME, others.vehicleId()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("없는 차량은 404 NOT_FOUND")
        void unknownVehicleIsNotFound() {
            assertThatThrownBy(() -> vehicleService.delete(ME, 999999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Instant updatedAtOf(Long vehicleId) {
        return jdbcTemplate.queryForObject(
                "select updated_at from vehicle where vehicle_id = ?", Instant.class, vehicleId);
    }

    private int countVehicleRows(Long vehicleId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from vehicle where vehicle_id = ?", Integer.class, vehicleId);
    }

    private void insertMember(long memberId, String providerUserId) {
        jdbcTemplate.update(
                "insert into member (member_id, provider, provider_user_id, nickname) values (?, 'KAKAO', ?, ?)",
                memberId, providerUserId, "tester" + memberId);
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
}
