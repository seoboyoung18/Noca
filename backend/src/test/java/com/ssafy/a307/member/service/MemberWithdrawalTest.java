package com.ssafy.a307.member.service;

import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.MemberStatus;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.entity.TermsType;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 = <b>즉시 익명화</b>. 이 동작이 성립해야 같은 소셜 계정으로 재가입할 수 있다.
 * <p>
 * {@code uk_member_provider (provider, provider_user_id)} 가 전체 UNIQUE 라, 탈퇴 행이 원래
 * 회원번호를 들고 있으면 재가입 INSERT 가 제약에 걸린다. H2 도 이 UNIQUE 를 그대로 갖고 있어
 * 여기서 실제로 검증된다.
 *
 * <p>Redis 세션 자동설정을 빼는 이유는 {@code ChecklistPublicAccessTest} 와 같다 —
 * Redis 가 떠 있지 않은 환경에서 컨텍스트가 죽지 않게 하기 위한 것이고, 이 테스트가 보는
 * 영속성 동작에는 영향이 없다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("회원 탈퇴 — 즉시 익명화")
class MemberWithdrawalTest {

    private static final String KAKAO_ID = "3812345678";

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("탈퇴하면 개인식별정보가 지워지고 소셜 계정으로는 더 이상 조회되지 않는다")
    void withdrawAnonymizesImmediately() {
        Member member = signup(KAKAO_ID, "보영");
        Long memberId = member.getMemberId();

        memberService.withdraw(memberId, Instant.now());

        assertThat(memberService.findBySocial(Provider.KAKAO, KAKAO_ID)).isEmpty();

        Member withdrawn = memberRepository.findById(memberId).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(withdrawn.getWithdrawnAt()).isNotNull();
        assertThat(withdrawn.getProviderUserId()).isEqualTo("withdrawn:" + memberId);
        assertThat(withdrawn.getNickname()).isEqualTo("탈퇴회원");
        assertThat(withdrawn.getEmail()).isNull();
    }

    @Test
    @DisplayName("탈퇴한 계정으로 다시 가입할 수 있다 — 새 member_id 를 받는다")
    void canSignupAgainAfterWithdrawal() {
        Long firstId = signup(KAKAO_ID, "보영").getMemberId();
        memberService.withdraw(firstId, Instant.now());

        Member rejoined = signup(KAKAO_ID, "보영2");

        assertThat(rejoined.getMemberId()).isNotEqualTo(firstId);
        assertThat(rejoined.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(rejoined.getNickname()).isEqualTo("보영2");
    }

    /**
     * 탈퇴 행을 지우지 않는 것이 핵심이다. {@code vehicle}·{@code accident}·
     * {@code estimate_validation} 이 {@code ON DELETE RESTRICT} 로 참조하고 있어
     * 물리 삭제하면 견적·검증 이력이 함께 막힌다.
     */
    @Test
    @DisplayName("탈퇴해도 회원 행 자체는 남는다 — 견적·검증 데이터의 FK 가 끊기지 않게")
    void keepsRowSoForeignKeysSurvive() {
        Long memberId = signup(KAKAO_ID, "보영").getMemberId();

        memberService.withdraw(memberId, Instant.now());

        assertThat(memberRepository.findById(memberId)).isPresent();
    }

    @Test
    @DisplayName("두 번 탈퇴해도 익명화 값이 덮어써지지 않는다")
    void withdrawIsIdempotent() {
        Long memberId = signup(KAKAO_ID, "보영").getMemberId();

        memberService.withdraw(memberId, Instant.now());
        Instant firstWithdrawnAt = memberRepository.findById(memberId).orElseThrow().getWithdrawnAt();
        memberService.withdraw(memberId, Instant.now().plusSeconds(60));

        Member withdrawn = memberRepository.findById(memberId).orElseThrow();
        assertThat(withdrawn.getWithdrawnAt()).isEqualTo(firstWithdrawnAt);
        assertThat(withdrawn.getProviderUserId()).isEqualTo("withdrawn:" + memberId);
    }

    private Member signup(String providerUserId, String nickname) {
        return memberService.signup(Provider.KAKAO, providerUserId, nickname,
                Set.of(TermsType.SERVICE, TermsType.PRIVACY));
    }
}
