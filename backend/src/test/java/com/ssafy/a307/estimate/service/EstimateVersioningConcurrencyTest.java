package com.ssafy.a307.estimate.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.entity.ConfidenceGrade;
import com.ssafy.a307.estimate.entity.Estimate;
import com.ssafy.a307.estimate.repository.EstimateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 분석 작업에 재산정 요청이 동시에 들어와도 견적은 하나만 생겨야 한다.
 * <p>
 * "마지막 버전을 조회하고 +1 로 INSERT" 는 동시 요청을 막지 못한다. 두 요청이 같은 순간에
 * 들어오면 둘 다 같은 다음 버전을 보고 둘 다 INSERT 를 시도한다. 실제로 막는 것은
 * {@code uk_est(job_id, version)} 이고, 애플리케이션은 그 위반을 409 로 번역해야 한다.
 * 번역하지 않으면 {@code DataIntegrityViolationException} 이 catch-all 핸들러까지 올라가 500 이 된다.
 *
 * <p><b>버전을 하나 더 올려 재시도하지 않는 이유</b> — 같은 분석 결과로 견적이 두 개 생긴다.
 * 사용자에게는 의미 없는 중복이고 어느 쪽이 맞는지도 알 수 없다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 붙이면 스레드들이 서로의 INSERT 를 보지
 * 못해 경합 자체가 일어나지 않는다. 대신 {@link #cleanUp()} 에서 직접 지운다.
 */
@SpringBootTest
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("동시 재산정 요청")
class EstimateVersioningConcurrencyTest {

    private static final int THREADS = 8;

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

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from estimate where job_id = ?", jobId);
        jdbcTemplate.update("delete from analysis_job where job_id = ?", jobId);
    }

    @Test
    @DisplayName("8건이 동시에 들어와도 견적은 하나만 생기고 나머지는 409 다")
    void concurrentReestimateCreatesExactlyOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch readyToStart = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    readyToStart.await();
                    try {
                        return versioningService.append(jobId, amounts(), ConfidenceGrade.HIGH);
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            readyToStart.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            List<Estimate> created = results.stream()
                    .filter(Estimate.class::isInstance)
                    .map(Estimate.class::cast)
                    .toList();
            List<Exception> failures = results.stream()
                    .filter(Exception.class::isInstance)
                    .map(Exception.class::cast)
                    .toList();

            assertThat(created).hasSize(1);
            assertThat(failures).hasSize(THREADS - 1);

            // 핵심 — 실패는 전부 409 여야 한다. DataIntegrityViolationException 이 그대로
            // 새어 나오면 사용자에게 500 이 나간다.
            assertThat(failures).allSatisfy(e -> {
                assertThat(e).isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            });

            // 버전을 올려 재시도하지 않았다는 확인 — 행이 하나뿐이고 버전은 1 이다
            assertThat(estimateRepository.findFirstByJobIdOrderByVersionDesc(jobId))
                    .get()
                    .extracting(Estimate::getVersion)
                    .isEqualTo((short) 1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Estimate.Amounts amounts() {
        return new Estimate.Amounts(52_000, new BigDecimal("3.20"),
                640_000, 800_000, 1_040_000, 12);
    }

    private long insertAnalysisJob() {
        jdbcTemplate.update("""
                insert into analysis_job (accident_id, status)
                values (?, 'COMPLETED')
                """, insertAccident());
        return jdbcTemplate.queryForObject("select max(job_id) from analysis_job", Long.class);
    }

    private long insertAccident() {
        jdbcTemplate.update("""
                insert into member (provider, provider_user_id, nickname, role, status)
                values ('KAKAO', ?, '동시견적', 'USER', 'ACTIVE')
                """, "est-conc-" + System.nanoTime());
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
