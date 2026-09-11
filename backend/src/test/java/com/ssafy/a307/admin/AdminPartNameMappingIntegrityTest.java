package com.ssafy.a307.admin;

import com.ssafy.a307.admin.dto.PartCodeCreateRequest;
import com.ssafy.a307.admin.dto.PartNameMappingCreateRequest;
import com.ssafy.a307.admin.service.AdminPartCodeService;
import com.ssafy.a307.admin.service.AdminPartNameMappingService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부품명 매핑의 <b>동시성</b>과 <b>쿼리 수</b>.
 *
 * <p>둘을 한 클래스에 둔 이유는 스프링 컨텍스트를 공유하기 위해서다 — N+1 측정에
 * {@code hibernate.generate_statistics} 가 필요하고, 그 속성 때문에 컨텍스트가 하나 더 뜬다.
 *
 * <p><b>⚠️ 동시성 테스트가 증명하는 것과 못 하는 것.</b> "한 건만 남는다" 는 증명한다.
 * 하지만 실측 분포는 {@code DUPLICATE_PART_NAME_MAPPING} ×7 + {@code OK} ×1 로,
 * H2 가 사실상 직렬화해 <b>7건 모두 사전 {@code existsById} 검사에서 걸렸다.</b> INSERT 까지
 * 내려가 UNIQUE 제약이 발동하는 경로는 여기서 재현되지 않는다. 그 번역 규칙은
 * {@code GlobalExceptionHandlerTest} 가 직접 잠근다.
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@DisplayName("부품명 매핑 동시성·쿼리 수")
class AdminPartNameMappingIntegrityTest {

    private static final long ADMIN_ID = 93_201L;
    private static final String PART_A = "ZZ_INTEG_PART_A";
    private static final String REASON = "동시성·쿼리 수 검증";
    private static final String CONTESTED_NAME = "ZZ동시등록원문";

    @Autowired private AdminPartCodeService partCodeService;
    @Autowired private AdminPartNameMappingService mappingService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Authentication admin;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'admin-integ', '동시성관리자', 'ADMIN', 'ACTIVE')
                """, ADMIN_ID);
        Member member = memberRepository.findById(ADMIN_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(member);
        admin = new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(admin);

        partCodeService.create(new PartCodeCreateRequest(PART_A, "동시성", "FRONT", 950, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("delete from part_name_mapping where part_code = ?", PART_A);
        jdbcTemplate.update("delete from part_code where part_code = ?", PART_A);
        jdbcTemplate.update("delete from audit_log where actor_member_id = ?", ADMIN_ID);
        jdbcTemplate.update("delete from member where member_id = ?", ADMIN_ID);
    }

    @Test
    @DisplayName("같은 원문을 8개가 동시에 등록하면 한 건만 저장된다")
    void concurrentDuplicateRegistrationLeavesExactlyOneRow() throws Exception {
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                // SecurityContext 는 스레드 로컬이다. 감사 로그의 행위자를 세션에서만
                // 꺼내므로 각 스레드에 직접 올려 줘야 한다.
                SecurityContextHolder.getContext().setAuthentication(admin);
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                try {
                    mappingService.create(
                            new PartNameMappingCreateRequest(CONTESTED_NAME, PART_A, REASON));
                    return "OK";
                } catch (Exception e) {
                    return e.getClass().getSimpleName()
                            + (e instanceof AdminOperationException admin
                            ? ":" + admin.code().name() : "");
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }

        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) futures.add(pool.submit(task));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        // 어떤 실패로 갈렸는지는 DB 엔진에 따라 다르다(중복 키 vs 잠금 대기). 실제 분포를
        // 남겨 두면 나중에 엔진을 바꿀 때 이 테스트가 무엇을 보고 있었는지 알 수 있다.
        System.out.println("동시 등록 결과 분포 = " + outcomes);

        assertThat(outcomes).as("결과 분포: %s", outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from part_name_mapping where raw_name = ?", Integer.class, CONTESTED_NAME))
                .as("PK 가 마지막 방어선이라 두 건이 될 수 없다")
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> !outcome.equals("OK")).toList())
                .as("실패한 요청은 전부 재시도 가능한 충돌로 번역된다 — 500 이 되면 안 된다. 분포: %s",
                        outcomes)
                .allSatisfy(outcome -> assertThat(outcome)
                        .containsAnyOf("DUPLICATE_PART_NAME_MAPPING", "VERSION_CONFLICT"));
        assertThat(auditRowCount())
                .as("성공한 한 건만 이력을 남긴다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("목록 쿼리 수가 행 수에 따라 늘지 않는다 — join fetch 가 살아 있다")
    void listQueryCountDoesNotGrowWithRows() {
        for (int i = 0; i < 12; i++) {
            mappingService.create(new PartNameMappingCreateRequest("ZZ쿼리수%02d".formatted(i), PART_A, REASON));
        }

        long smallPage = countStatements(() -> mappingService.search("ZZ쿼리수", null, null, 0, 2, null));
        long fullPage = countStatements(() -> mappingService.search("ZZ쿼리수", null, null, 0, 12, null));

        assertThat(fullPage)
                .as("2건 페이지와 12건 페이지의 쿼리 수가 같아야 한다 (2건: %d, 12건: %d)", smallPage, fullPage)
                .isEqualTo(smallPage);
        assertThat(fullPage)
                .as("목록·건수·사전 적재 정도의 고정 쿼리만 돈다")
                .isLessThanOrEqualTo(5);
    }

    private long countStatements(Runnable work) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        work.run();
        return statistics.getPrepareStatementCount();
    }

    private Integer auditRowCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from audit_log
                where target_type = 'PART_NAME_MAPPING' and target_id = ?
                """, Integer.class, CONTESTED_NAME);
    }
}
