package com.ssafy.a307.member.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.entity.TermsType;
import com.ssafy.a307.member.repository.MemberRepository;
import com.ssafy.a307.member.repository.TermsAgreementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 같은 소셜 계정으로 가입 요청이 동시에 들어와도 회원이 하나만 만들어져야 한다.
 * <p>
 * "먼저 조회하고 없으면 INSERT" 는 동시 요청을 막지 못한다. 두 요청이 같은 순간에 들어오면
 * 둘 다 "없음"을 보고 둘 다 INSERT 를 시도한다. 실제로 중복을 막는 것은
 * {@code uk_member_provider} 유니크 제약이고, 애플리케이션은 그 위반을 409 로 번역해야 한다.
 * 번역하지 않으면 {@code DataIntegrityViolationException} 이 catch-all 핸들러까지 올라가 500 이 된다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 붙이면 모든 스레드가 테스트 트랜잭션을
 * 공유하지 못해 서로의 INSERT 를 보지 못하고, 검증하려는 경합 자체가 일어나지 않는다.
 * 대신 {@link #cleanUp()} 에서 직접 지운다.
 */
@SpringBootTest
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("동시 가입 요청")
class MemberSignupConcurrencyTest {

    private static final String PROVIDER_USER_ID = "concurrent-3812345678";
    private static final int THREADS = 8;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TermsAgreementRepository termsAgreementRepository;

    @AfterEach
    void cleanUp() {
        memberRepository.findAll().stream()
                .filter(m -> PROVIDER_USER_ID.equals(m.getProviderUserId()))
                .forEach(m -> {
                    termsAgreementRepository.deleteAll(
                            termsAgreementRepository.findAllByMemberId(m.getMemberId()));
                    memberRepository.delete(m);
                });
    }

    @Test
    @DisplayName("같은 계정으로 8건이 동시에 들어와도 회원은 하나만 생기고 나머지는 409 다")
    void concurrentSignupCreatesExactlyOneMember() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch readyToStart = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    readyToStart.await();
                    try {
                        return memberService.signup(Provider.KAKAO, PROVIDER_USER_ID, "보영",
                                Set.of(TermsType.SERVICE, TermsType.PRIVACY));
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

            List<Member> created = results.stream()
                    .filter(Member.class::isInstance)
                    .map(Member.class::cast)
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
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 위 테스트만으로는 제약 위반 경로가 실제로 실행됐는지 보장할 수 없다. 스레드가 어긋나면
     * 사전 조회에서 전부 걸러져 catch 를 한 번도 지나지 않고도 통과한다.
     * 그래서 사전 조회를 건너뛰고 INSERT 만 직접 시도해 번역이 되는지 확정한다.
     */
    @Test
    @DisplayName("유니크 제약 위반은 500 이 아니라 409 로 번역된다")
    void constraintViolationIsTranslatedToConflict() {
        memberService.signup(Provider.KAKAO, PROVIDER_USER_ID, "보영",
                Set.of(TermsType.SERVICE, TermsType.PRIVACY));

        assertThatThrownBy(() ->
                memberService.insertMember(Provider.KAKAO, PROVIDER_USER_ID, "보영2"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("경합 후에도 DB 에는 회원 한 건과 약관 이력 두 건만 남는다")
    void databaseKeepsSingleConsistentRow() throws Exception {
        concurrentSignupCreatesExactlyOneMember();

        List<Member> stored = memberRepository.findAll().stream()
                .filter(m -> PROVIDER_USER_ID.equals(m.getProviderUserId()))
                .toList();

        assertThat(stored).hasSize(1);
        // 롤백된 요청이 약관 이력만 남기고 사라지는 일이 없어야 한다.
        assertThat(termsAgreementRepository.findAllByMemberId(stored.get(0).getMemberId()))
                .hasSize(2);
    }
}
