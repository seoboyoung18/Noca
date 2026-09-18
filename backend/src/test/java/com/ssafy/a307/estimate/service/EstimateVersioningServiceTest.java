package com.ssafy.a307.estimate.service;

import com.ssafy.a307.estimate.entity.ConfidenceGrade;
import com.ssafy.a307.estimate.entity.Estimate;
import com.ssafy.a307.estimate.repository.EstimateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재산정하면 견적 행을 고치지 않고 새 버전을 만든다.
 * <p>
 * 사용자에게 이미 보여 준 견적을 덮어쓰면 "어제 120만원이라더니 오늘 90만원"이 됐을 때
 * 무엇이 바뀌었는지 설명할 수 없다. {@code UNIQUE(job_id, version)} 이 그 규칙을 강제한다.
 *
 * <p>{@code analysis_job} 행은 SQL 로 직접 넣는다. FK 가 걸려 있어 job 없이는 견적을 만들 수
 * 없는데, 분석 도메인은 아직 엔티티가 없기 때문이다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("견적 버전 채번")
class EstimateVersioningServiceTest {

    @Autowired
    private EstimateVersioningService versioningService;

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long jobId;

    @BeforeEach
    void createAnalysisJob() {
        jobId = insertAnalysisJob();
    }

    /**
     * 리포트 요약 접수 (S15P21A307-537). <b>견적 저장과 같은 트랜잭션</b>이라 견적이 남으면
     * 반드시 큐에 들어가 있다 — 이벤트로 접수하면 "견적은 있는데 요약은 영영 안 만들어진"
     * 건이 생긴다. 생성은 워커가 하므로 여기서 LLM 은 불리지 않는다.
     */
    @Test
    @DisplayName("견적을 저장하면 요약이 QUEUED 로 접수된다")
    void savingQueuesNarrative() {
        Estimate estimate = versioningService.append(jobId, amounts(800_000), ConfidenceGrade.HIGH, null);

        assertThat(narrativeStatusOf(estimate.getEstimateId())).isEqualTo("QUEUED");
    }

    /** 금액이 없을수록 "왜 못 냈고 다음에 무엇을 하면 되는지" 를 설명할 문장이 필요하다. */
    @Test
    @DisplayName("산정 불가 견적도 요약을 접수한다")
    void nonEstimableQueuesNarrativeToo() {
        Estimate estimate = versioningService.appendNonEstimable(jobId, "INSUFFICIENT_CASES", null);

        assertThat(narrativeStatusOf(estimate.getEstimateId())).isEqualTo("QUEUED");
    }

    private String narrativeStatusOf(long estimateId) {
        return jdbcTemplate.queryForObject(
                "select status from estimate_narrative where estimate_id = ?",
                String.class, estimateId);
    }

    @Test
    @DisplayName("첫 산정은 버전 1 이다")
    void firstEstimateIsVersionOne() {
        Estimate first = versioningService.append(jobId, amounts(800_000), ConfidenceGrade.HIGH, null);

        assertThat(first.getVersion()).isEqualTo((short) 1);
        assertThat(first.isFirstVersion()).isTrue();
        assertThat(first.getEstimateId()).isNotNull();
    }

    @Test
    @DisplayName("재산정하면 버전이 올라가고 이전 버전은 그대로 남는다")
    void reestimateAppendsNewVersion() {
        Estimate first = versioningService.append(jobId, amounts(800_000), ConfidenceGrade.HIGH, null);
        Estimate second = versioningService.append(jobId, amounts(950_000), ConfidenceGrade.MEDIUM, null);

        assertThat(second.getVersion()).isEqualTo((short) 2);
        assertThat(second.getEstimateId()).isNotEqualTo(first.getEstimateId());

        // 이전 버전을 덮어쓰지 않는다 — 근거가 사라지면 안 된다
        assertThat(estimateRepository.findById(first.getEstimateId()))
                .get()
                .extracting(Estimate::getTotalMedian)
                .isEqualTo(800_000);
    }

    @Test
    @DisplayName("최신 조회는 가장 높은 버전을 준다")
    void findLatestReturnsHighestVersion() {
        versioningService.append(jobId, amounts(800_000), ConfidenceGrade.HIGH, null);
        versioningService.append(jobId, amounts(950_000), ConfidenceGrade.MEDIUM, null);
        versioningService.append(jobId, amounts(910_000), ConfidenceGrade.LOW, null);

        assertThat(versioningService.findLatest(jobId))
                .get()
                .extracting(Estimate::getVersion, Estimate::getTotalMedian)
                .containsExactly((short) 3, 910_000);
    }

    @Test
    @DisplayName("버전은 분석 작업마다 따로 매겨진다")
    void versionIsScopedToJob() {
        long otherJobId = insertAnalysisJob();

        versioningService.append(jobId, amounts(800_000), ConfidenceGrade.HIGH, null);
        Estimate other = versioningService.append(otherJobId, amounts(500_000), ConfidenceGrade.HIGH, null);

        // 다른 job 의 견적이 있다고 해서 2번부터 시작하면 안 된다
        assertThat(other.getVersion()).isEqualTo((short) 1);
    }

    /**
     * 참조할 사례가 모자라면 금액 없이 사유만 남긴다. 행을 아예 만들지 않으면 화면이
     * "분석 중"과 "분석은 끝났는데 산정이 안 됨"을 구분하지 못한다.
     */
    @Test
    @DisplayName("산정 불가도 버전을 차지한다 — 금액은 비고 사유만 남는다")
    void nonEstimableTakesAVersion() {
        versioningService.append(jobId, amounts(800_000), ConfidenceGrade.HIGH, null);
        Estimate failed = versioningService.appendNonEstimable(jobId, "참조할 유사 사례가 부족합니다.", null);

        assertThat(failed.getVersion()).isEqualTo((short) 2);
        assertThat(failed.isEstimable()).isFalse();
        assertThat(failed.getNonEstimableReason()).isEqualTo("참조할 유사 사례가 부족합니다.");
        assertThat(failed.getTotalMedian()).isNull();
        assertThat(failed.getConfidenceGrade()).isNull();
    }

    @Test
    @DisplayName("산정 불가 사유가 비어 있으면 만들지 않는다")
    void nonEstimableRequiresReason() {
        assertThatThrownBy(() -> versioningService.appendNonEstimable(jobId, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("총액 범위가 뒤집혀 있으면 만들지 않는다")
    void rejectsInvertedTotals() {
        assertThatThrownBy(() -> new Estimate.Amounts(
                52_000, new BigDecimal("3.20"), 900_000, 800_000, 1_000_000, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalMin");

        assertThatThrownBy(() -> new Estimate.Amounts(
                52_000, new BigDecimal("3.20"), 700_000, 900_000, 800_000, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalMedian");
    }

    private Estimate.Amounts amounts(int median) {
        return new Estimate.Amounts(
                52_000, new BigDecimal("3.20"),
                (int) (median * 0.8), median, (int) (median * 1.3),
                12);
    }

    private long insertAnalysisJob() {
        jdbcTemplate.update("""
                insert into analysis_job (accident_id, status)
                values (?, 'COMPLETED')
                """, insertAccident());
        return jdbcTemplate.queryForObject("select max(job_id) from analysis_job", Long.class);
    }

    /** 사고는 차량을, 차량은 회원과 차종을 요구한다. FK 사슬을 최소로 채운다. */
    private long insertAccident() {
        jdbcTemplate.update("""
                insert into member (provider, provider_user_id, nickname, role, status)
                values ('KAKAO', ?, '견적테스트', 'USER', 'ACTIVE')
                """, "est-" + System.nanoTime());
        Long memberId = jdbcTemplate.queryForObject("select max(member_id) from member", Long.class);

        jdbcTemplate.update("""
                insert into vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)
                values ('현대', ?, 'SEDAN', 'Compact', true)
                """, "아반떼" + System.nanoTime());
        Long modelId = jdbcTemplate.queryForObject("select max(model_id) from vehicle_model", Long.class);

        jdbcTemplate.update("""
                insert into vehicle (member_id, model_id, model_year) values (?, ?, 2020)
                """, memberId, modelId);
        Long vehicleId = jdbcTemplate.queryForObject("select max(vehicle_id) from vehicle", Long.class);

        jdbcTemplate.update("""
                insert into accident (vehicle_id, vehicle_input_type, snapshot_model_id,
                    snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                    snapshot_car_class, snapshot_model_year)
                values (?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Compact', 2020)
                """, vehicleId, modelId);
        return jdbcTemplate.queryForObject("select max(accident_id) from accident", Long.class);
    }
}
