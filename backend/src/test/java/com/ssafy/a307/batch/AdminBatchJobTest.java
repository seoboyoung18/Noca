package com.ssafy.a307.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.a307.admin.dto.BatchJobDetailResponse;
import com.ssafy.a307.admin.dto.BatchJobExecutionResponse;
import com.ssafy.a307.admin.service.AdminBatchJobService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.batch.entity.BatchJobStatus;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 배치 작업 조회 (S15P21A307-355).
 *
 * <p>행을 만드는 것은 파이프라인이라 테스트가 {@code JdbcTemplate} 으로 직접 넣는다 —
 * 백엔드에 쓰기 경로가 없기 때문이다. 그것이 이 기능의 설계이기도 하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("배치 작업 조회")
class AdminBatchJobTest {

    private static final long ADMIN_ID = 94_701L;
    private static final long USER_ID = 94_702L;
    private static final String JOB_A = "zz_batch_test_alpha";
    private static final String JOB_B = "zz_batch_test_beta";

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminBatchJobService service;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Authentication admin;
    private Authentication user;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'admin-batchjob', '배치관리자', 'ADMIN', 'ACTIVE')
                """, ADMIN_ID);
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'user-batchjob', '배치일반', 'USER', 'ACTIVE')
                """, USER_ID);
        admin = tokenOf(ADMIN_ID);
        user = tokenOf(USER_ID);
        // SecurityContextHolder 를 여기서 채우지 않는다. 채우면 MockMvc 가 그 컨텍스트를
        // 주워 비로그인 요청까지 200 이 된다 — AdminSecurityTest 도 같은 이유로 비워 둔다.
        // 인증이 필요한 요청은 .with(authentication(...)) 으로 건마다 준다.
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("""
                delete from data_validation_error
                 where batch_job_execution_id in (select batch_job_execution_id
                                                    from batch_job_execution where job_name like 'zz_batch_test_%')
                """);
        jdbcTemplate.update("delete from batch_job_execution where job_name like 'zz_batch_test_%'");
        jdbcTemplate.update("delete from member where member_id in (?, ?)", ADMIN_ID, USER_ID);
    }

    private Authentication tokenOf(long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(member);
        return new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    /** 파이프라인이 남기는 것과 같은 모양의 행. */
    private long insertExecution(String jobName, BatchJobStatus status,
                                 String startedAt, String completedAt, String summary) {
        jdbcTemplate.update("""
                insert into batch_job_execution
                       (job_name, job_version, status, input_ref, started_at, completed_at, summary, error_message)
                values (?, 'v1', ?, 'accident.actual_repair_cost', ?, ?, ?, null)
                """, jobName, status.name(), startedAt, completedAt, summary);
        return jdbcTemplate.queryForObject("""
                select max(batch_job_execution_id) from batch_job_execution where job_name = ?
                """, Long.class, jobName);
    }

    @Nested
    @DisplayName("목록")
    class ListJobs {

        @Test
        @DisplayName("실행 기록이 없으면 빈 목록이다 — 404 가 아니다")
        void emptyIsNotAnError() {
            List<BatchJobExecutionResponse> all = service.findLatestPerJob();
            assertThat(all).noneMatch(r -> r.jobName().startsWith("zz_batch_test_"));
        }

        @Test
        @DisplayName("job 별로 가장 최근 실행 한 건씩만 나온다")
        void latestPerJob() {
            insertExecution(JOB_A, BatchJobStatus.SUCCEEDED,
                    "2026-09-14 10:00:00+09", "2026-09-14 10:05:00+09", null);
            insertExecution(JOB_A, BatchJobStatus.FAILED,
                    "2026-09-15 10:00:00+09", "2026-09-15 10:01:00+09", null);
            insertExecution(JOB_B, BatchJobStatus.SUCCEEDED,
                    "2026-09-13 10:00:00+09", "2026-09-13 10:02:00+09", null);

            List<BatchJobExecutionResponse> rows = service.findLatestPerJob().stream()
                    .filter(r -> r.jobName().startsWith("zz_batch_test_"))
                    .toList();

            assertThat(rows).hasSize(2);
            assertThat(rows).filteredOn(r -> r.jobName().equals(JOB_A))
                    .singleElement()
                    .satisfies(r -> assertThat(r.status()).isEqualTo(BatchJobStatus.FAILED));
        }
    }

    @Nested
    @DisplayName("소요 시간")
    class Duration {

        @Test
        @DisplayName("RUNNING 이면 null 이다 — 0 이 아니다")
        void runningHasNoDuration() {
            long id = insertExecution(JOB_A, BatchJobStatus.RUNNING,
                    "2026-09-15 10:00:00+09", null, null);

            BatchJobDetailResponse detail = service.findDetail(id);

            assertThat(detail.execution().completedAt()).isNull();
            assertThat(detail.execution().durationSeconds()).isNull();
        }

        @Test
        @DisplayName("끝났으면 초 단위로 계산된다")
        void completedHasDuration() {
            long id = insertExecution(JOB_A, BatchJobStatus.SUCCEEDED,
                    "2026-09-15 10:00:00+09", "2026-09-15 10:02:30+09", null);

            assertThat(service.findDetail(id).execution().durationSeconds()).isEqualTo(150L);
        }
    }

    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("null 이어도 죽지 않는다")
        void nullSummaryIsFine() {
            long id = insertExecution(JOB_A, BatchJobStatus.SUCCEEDED,
                    "2026-09-15 10:00:00+09", "2026-09-15 10:01:00+09", null);

            assertThat(service.findDetail(id).execution().summary()).isNull();
        }

        @Test
        @DisplayName("원문을 해석하지 않고 그대로 내린다")
        void summaryIsRaw() {
            long id = insertExecution(JOB_A, BatchJobStatus.SUCCEEDED,
                    "2026-09-15 10:00:00+09", "2026-09-15 10:01:00+09",
                    "{\"inserted\": 12, \"skipped\": 3}");

            assertThat(service.findDetail(id).execution().summary()).contains("inserted");
        }
    }

    @Nested
    @DisplayName("데이터 검증 격리 건")
    class ValidationErrors {

        @Test
        @DisplayName("없으면 0 이고 분포가 빈 목록이다")
        void noneIsZero() {
            long id = insertExecution(JOB_A, BatchJobStatus.SUCCEEDED,
                    "2026-09-15 10:00:00+09", "2026-09-15 10:01:00+09", null);

            BatchJobDetailResponse detail = service.findDetail(id);

            assertThat(detail.validationErrorCount()).isZero();
            assertThat(detail.validationErrorTypes()).isEmpty();
        }

        @Test
        @DisplayName("사유별 건수가 많은 순으로 나온다")
        void countedByType() {
            long id = insertExecution(JOB_A, BatchJobStatus.PARTIAL,
                    "2026-09-15 10:00:00+09", "2026-09-15 10:01:00+09", null);
            insertError(id, "MISSING_PART");
            insertError(id, "MISSING_PART");
            insertError(id, "BAD_AMOUNT");

            BatchJobDetailResponse detail = service.findDetail(id);

            assertThat(detail.validationErrorCount()).isEqualTo(3);
            assertThat(detail.validationErrorTypes()).hasSize(2);
            assertThat(detail.validationErrorTypes().getFirst().errorType()).isEqualTo("MISSING_PART");
            assertThat(detail.validationErrorTypes().getFirst().count()).isEqualTo(2);
        }

        private void insertError(long executionId, String type) {
            jdbcTemplate.update("""
                    insert into data_validation_error (batch_job_execution_id, error_type, source_ref)
                    values (?, ?, 'test')
                    """, executionId, type);
        }
    }

    @Nested
    @DisplayName("HTTP")
    class Http {

        @Test
        @DisplayName("관리자는 200")
        void adminCanRead() throws Exception {
            insertExecution(JOB_A, BatchJobStatus.SUCCEEDED,
                    "2026-09-15 10:00:00+09", "2026-09-15 10:01:00+09", null);

            mockMvc.perform(get("/api/admin/batch-jobs").with(authentication(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("일반 사용자는 403")
        void userIsForbidden() throws Exception {
            mockMvc.perform(get("/api/admin/batch-jobs").with(authentication(user)))
                    .andExpect(status().isForbidden());
        }

        /**
         * {@code @BeforeEach} 가 넣어 둔 관리자 컨텍스트를 <b>먼저 비운다.</b> 비우지 않으면
         * MockMvc 가 스레드의 {@code SecurityContext} 를 그대로 주워 200 이 된다 —
         * 실제 비로그인 요청과 다른 상태를 보게 된다.
         */
        @Test
        @DisplayName("비로그인은 401")
        void anonymousIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/admin/batch-jobs"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("없는 실행은 404")
        void unknownExecutionIsNotFound() throws Exception {
            mockMvc.perform(get("/api/admin/batch-jobs/{id}", 987_654_321L)
                            .with(authentication(admin)))
                    .andExpect(status().isNotFound());
        }
    }
}
