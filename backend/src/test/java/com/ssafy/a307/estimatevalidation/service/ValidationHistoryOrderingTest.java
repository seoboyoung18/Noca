package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationItemRequest;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 이력 정렬이 <b>결정적인지</b> 고정한다.
 *
 * <p><b>왜 필요한가</b> — {@code created_at} 하나로만 정렬하면 같은 시각에 만들어진 두 건의
 * 상대 순서를 DB 가 마음대로 정한다. 그러면 이력 목록의 페이지네이션이 깨지고
 * (1페이지에 보인 항목이 2페이지에 또 나오거나 사라진다), 비용 비교에서 "가장 최근 견적서" 가
 * 뒤바뀐다 — 최신 견적으로 협상하려는 사용자에게 이전 견적을 먼저 보여 준다.
 *
 * <p><b>동점은 어떻게 생기는가 — 실측해서 확인한 것.</b> {@code created_at} 은 DDL 에
 * {@code DEFAULT now()} 가 있지만 엔티티가 {@code @CreatedDate} 라 <b>값을 애플리케이션이
 * 넣는다.</b> Hibernate 가 INSERT 컬럼 목록에 {@code created_at} 을 포함하므로 DB 기본값은
 * 쓰이지 않는다. 즉 "PostgreSQL 의 {@code now()} 가 트랜잭션 시각이라 한 트랜잭션의 행은
 * 반드시 같다" 는 설명은 <b>JPA 로 만든 행에는 해당하지 않는다.</b>
 *
 * <p>그래도 동점은 난다.
 * <ul>
 *   <li>두 번의 {@code persist} 가 <b>같은 시계 눈금</b>에 걸리면 같아진다. 실행 환경에 따라
 *       시계 해상도가 밀리초까지 떨어지므로 드물지 않다 — 기존 테스트가 <b>간헐적으로만</b>
 *       실패한 이유가 이것이다</li>
 *   <li><b>원시 SQL 로 넣은 행은 DB 기본값 {@code now()} 를 쓴다.</b> PostgreSQL 의
 *       {@code now()} 는 트랜잭션 시작 시각이므로, 배치·마이그레이션·적재 파이프라인이
 *       한 트랜잭션에서 만든 행들은 <b>반드시</b> 같아진다</li>
 * </ul>
 *
 * <p>그래서 아래 테스트는 {@code created_at} 을 <b>JDBC 로 직접 같게 맞춰</b> 동점을 만든다
 * ({@code AccidentServiceTest} 의 backdate 패턴과 같다). {@code Thread.sleep} 으로 시간차를
 * 만들지 않는다 — 그건 동점을 <b>피하는</b> 것이고, 우리가 보려는 것은 <b>동점일 때</b>의 동작이다.
 */
@SpringBootTest
@Transactional
@DisplayName("검증 이력 정렬")
class ValidationHistoryOrderingTest {

    private static final long ME = 921L;
    private static final long ACCIDENT = 923L;
    private static final long OTHER_ACCIDENT = 924L;

    @Autowired EstimateValidationService service;
    @Autowired CostComparisonService costComparisonService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','order-me','tester','USER','ACTIVE')", ME);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(920,'현대','아반떼','SEDAN','Mid-size',true)");
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(920,?,920,2024)", ME);
        insertAccident(ACCIDENT);
        insertAccident(OTHER_ACCIDENT);
        jdbc.update("insert into part_code(part_code,name_ko,layout_zone,display_order,is_active)"
                + " values('P-FENDER','프론트 펜더','FRONT',1,true)");
        jdbc.update("insert into part_name_mapping(raw_name,part_code) values('프론트 펜더','P-FENDER')");
    }

    private void insertAccident(long accidentId) {
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, 920, 'REGISTERED', 920, '현대', '아반떼', 'SEDAN', 'Mid-size', 2024)
                """, accidentId);
    }

    private long register(long accidentId, int claimedTotal) {
        return service.registerManual(ME, new ManualValidationRequest(
                accidentId, null, EstimateFileType.MANUAL, null,
                List.of(new ManualValidationItemRequest(1, "프론트 펜더", "판금", 1, claimedTotal, 0))))
                .validationId();
    }

    /** 저장한 값을 확실히 읽도록 영속성 컨텍스트를 비운다. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** {@code created_at} 을 직접 지정한다. {@code AccidentServiceTest} 의 backdate 패턴과 같다. */
    private void setCreatedAt(long validationId, Instant at) {
        jdbc.update("update estimate_validation set created_at = ? where validation_id = ?",
                Timestamp.from(at), validationId);
    }

    /** 주어진 건들의 {@code created_at} 을 첫 번째 것에 맞춘다 — 동점 상황을 만든다. */
    private void tieCreatedAt(long... validationIds) {
        Instant shared = createdAtOf(validationIds[0]);
        for (long id : validationIds) setCreatedAt(id, shared);
        flushAndClear();
        for (long id : validationIds) {
            assertThat(createdAtOf(id)).as("동점 전제").isEqualTo(shared);
        }
    }

    private Instant createdAtOf(long validationId) {
        return jdbc.queryForObject(
                "select created_at from estimate_validation where validation_id = ?",
                Timestamp.class, validationId).toInstant();
    }

    private List<Long> historyIds(int page, int size) {
        return service.history(ME, page, size).content().stream()
                .map(entry -> entry.validationId()).toList();
    }

    // ------------------------------------------------------------------ 동점

    @Nested
    @DisplayName("같은 시각에 만들어진 건")
    class SameInstant {

        @Test
        @DisplayName("동점이면 validationId 내림차순이 순서를 정한다 — 나중에 만든 것이 먼저다")
        void tieIsBrokenByValidationIdDescending() {
            long first = register(ACCIDENT, 150_000);
            long second = register(ACCIDENT, 150_001);
            flushAndClear();
            tieCreatedAt(first, second);

            assertThat(second).isGreaterThan(first);
            assertThat(historyIds(0, 50)).containsExactly(second, first);
        }

        @Test
        @DisplayName("여러 번 조회해도 같은 순서다 — 실행마다 달라지지 않는다")
        void repeatedQueriesGiveTheSameOrder() {
            long first = register(ACCIDENT, 150_000);
            long second = register(ACCIDENT, 150_001);
            long third = register(OTHER_ACCIDENT, 150_002);
            flushAndClear();
            tieCreatedAt(first, second, third);

            List<Long> expected = List.of(third, second, first);
            for (int attempt = 0; attempt < 20; attempt++) {
                assertThat(historyIds(0, 50))
                        .as("조회 %d 회차", attempt + 1)
                        .containsExactlyElementsOf(expected);
                entityManager.clear();
            }
        }
    }

    // ------------------------------------------------------------------ 1차 키 우선

    @Test
    @DisplayName("created_at 이 다르면 그것이 여전히 1차 키다 — 나중에 만든 건이라도 시각이 오래됐으면 뒤로 간다")
    void createdAtStaysThePrimaryKey() {
        long older = register(ACCIDENT, 150_000);
        long newer = register(ACCIDENT, 150_001);   // 더 큰 id
        flushAndClear();

        // 더 큰 id 를 가진 쪽을 과거로 되돌린다. id 만으로 정렬하면 이 단언이 깨진다.
        setCreatedAt(newer, createdAtOf(older).minusSeconds(3600));
        flushAndClear();

        assertThat(historyIds(0, 50)).containsExactly(older, newer);
    }

    // ------------------------------------------------------------------ 페이지네이션

    @Test
    @DisplayName("페이지 크기 1로 넘겨도 중복·누락이 없다")
    void pagingWithSizeOneHasNoDuplicateOrGap() {
        long first = register(ACCIDENT, 150_000);
        long second = register(ACCIDENT, 150_001);
        long third = register(OTHER_ACCIDENT, 150_002);
        flushAndClear();
        tieCreatedAt(first, second, third);

        List<Long> paged = List.of(
                historyIds(0, 1).getFirst(),
                historyIds(1, 1).getFirst(),
                historyIds(2, 1).getFirst());

        assertThat(paged).containsExactly(third, second, first).doesNotHaveDuplicates();
        assertThat(paged).containsExactlyInAnyOrderElementsOf(List.of(first, second, third));
        assertThat(service.history(ME, 0, 1).totalElements()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 사고별 목록 (비용 비교)

    @Test
    @DisplayName("비용 비교의 정비소 견적도 최신순이다 — 같은 시각이면 나중 건이 먼저다")
    void costComparisonListsShopEstimatesNewestFirst() {
        long first = register(ACCIDENT, 150_000);
        long second = register(ACCIDENT, 150_001);
        register(OTHER_ACCIDENT, 150_002);          // 다른 사고 건은 섞이지 않아야 한다
        flushAndClear();
        tieCreatedAt(first, second);

        var shops = costComparisonService.compare(ME, ACCIDENT).shopEstimates();

        assertThat(shops).extracting(shop -> shop.validationId()).containsExactly(second, first);
    }

    @Test
    @DisplayName("비용 비교에서도 created_at 이 1차 키다")
    void costComparisonKeepsCreatedAtPrimary() {
        long older = register(ACCIDENT, 150_000);
        long newer = register(ACCIDENT, 150_001);
        flushAndClear();
        setCreatedAt(newer, createdAtOf(older).minusSeconds(3600));
        flushAndClear();

        var shops = costComparisonService.compare(ME, ACCIDENT).shopEstimates();

        assertThat(shops).extracting(shop -> shop.validationId()).containsExactly(older, newer);
    }
}
