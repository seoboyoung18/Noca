package com.ssafy.a307.member.service;

import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.entity.TermsAgreement;
import com.ssafy.a307.member.entity.TermsType;
import com.ssafy.a307.member.repository.MemberRepository;
import com.ssafy.a307.member.repository.TermsAgreementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동의 이력에서 재동의 대상을 뽑아내는 부분. 규칙 자체는 {@link TermsPolicyTest} 가 보고,
 * 여기서는 <b>이력이 여러 개 쌓였을 때 어느 것이 "최신" 인가</b>를 실제 저장소로 확인한다.
 *
 * <p>Redis 세션 자동설정을 빼는 이유는 {@code MemberWithdrawalTest} 와 같다 — Redis 가 떠 있지
 * 않은 환경에서 컨텍스트가 죽지 않게 하려는 것이고, 여기서 보는 동작에는 영향이 없다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("약관 재동의 대상 — 동의 이력 집계")
class TermsReagreementTest {

    private static final String KAKAO_ID = "3877777777";
    private static final String OLD_VERSION = "0.9";

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TermsAgreementRepository termsAgreementRepository;

    /** 가입 흐름이 현행 버전으로 이력을 남기므로, 방금 가입한 회원은 대상이 될 수 없다. */
    @Test
    @DisplayName("가입 직후 회원은 재동의 대상이 아니다")
    void freshlySignedUpMemberIsNotTarget() {
        Member member = memberService.signup(
                Provider.KAKAO, KAKAO_ID, "보영", Set.of(TermsType.SERVICE, TermsType.PRIVACY));

        assertThat(memberService.reagreementTargets(member.getMemberId())).isEmpty();
    }

    @Test
    @DisplayName("옛 버전으로만 동의한 항목이 대상이 된다")
    void outdatedAgreementBecomesTarget() {
        Member member = memberRepository.save(
                Member.register(Provider.KAKAO, KAKAO_ID, "보영"));
        termsAgreementRepository.save(
                TermsAgreement.of(member.getMemberId(), TermsType.SERVICE, OLD_VERSION));
        termsAgreementRepository.save(TermsAgreement.of(member.getMemberId(), TermsType.PRIVACY,
                TermsPolicy.currentVersion(TermsType.PRIVACY)));

        assertThat(memberService.reagreementTargets(member.getMemberId()))
                .containsExactly(TermsType.SERVICE);
    }

    /**
     * 재동의를 마친 회원이 계속 대상으로 남으면 동의 화면을 벗어날 수 없다.
     * 옛 이력은 지우지 않고 쌓아 두므로, 같은 항목의 여러 행 중 최신을 골라야 한다.
     */
    @Test
    @DisplayName("옛 이력이 남아 있어도 현행 버전으로 다시 동의했으면 대상이 아니다")
    void reagreedMemberIsNoLongerTarget() {
        Member member = memberRepository.save(
                Member.register(Provider.KAKAO, KAKAO_ID, "보영"));
        termsAgreementRepository.save(
                TermsAgreement.of(member.getMemberId(), TermsType.SERVICE, OLD_VERSION));
        termsAgreementRepository.save(TermsAgreement.of(member.getMemberId(), TermsType.PRIVACY,
                TermsPolicy.currentVersion(TermsType.PRIVACY)));

        termsAgreementRepository.save(TermsAgreement.of(member.getMemberId(), TermsType.SERVICE,
                TermsPolicy.currentVersion(TermsType.SERVICE)));

        assertThat(memberService.reagreementTargets(member.getMemberId())).isEmpty();
    }

    /** 정상 흐름에서는 생기지 않는 상태다. 그래도 "동의한 적 없음" 이 통과로 뒤집히면 안 된다. */
    @Test
    @DisplayName("동의 이력이 하나도 없는 회원은 필수 약관 전체가 대상이다")
    void memberWithoutAnyAgreementIsTargetForAll() {
        Member member = memberRepository.save(
                Member.register(Provider.KAKAO, KAKAO_ID, "보영"));

        assertThat(memberService.reagreementTargets(member.getMemberId()))
                .containsExactlyInAnyOrderElementsOf(TermsPolicy.REQUIRED);
    }
}
