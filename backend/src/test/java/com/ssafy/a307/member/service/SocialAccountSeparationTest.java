package com.ssafy.a307.member.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.MemberStatus;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.entity.TermsType;
import com.ssafy.a307.member.repository.TermsAgreementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>카카오 계정과 구글 계정은 서로 별개의 회원이다.</b>
 * <p>
 * 소셜 계정 간 연동을 하지 않기로 했다. 같은 사람이 두 경로로 들어오면 회원이 둘 생긴다.
 * 이 분리는 코드가 아니라 스키마가 보장한다 — {@code uk_member_provider} 가
 * {@code (provider, provider_user_id)} 복합 유니크라 provider 가 다르면 애초에 충돌하지 않는다.
 *
 * <p>이메일로 계정이 합쳐질 여지도 없다. 두 provider 모두 이메일 scope 를 요청하지 않아
 * {@code member.email} 은 항상 NULL 이다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("소셜 계정 분리 (카카오 · 구글)")
class SocialAccountSeparationTest {

    private static final String KAKAO_ID = "3812345678";
    private static final String GOOGLE_SUB = "104729384756102938475";

    @Autowired
    private MemberService memberService;

    @Autowired
    private TermsAgreementRepository termsAgreementRepository;

    @Test
    @DisplayName("같은 사람이 카카오·구글로 각각 가입하면 회원이 둘 생긴다")
    void kakaoAndGoogleBecomeSeparateMembers() {
        Member kakao = signup(Provider.KAKAO, KAKAO_ID, "보영");
        Member google = signup(Provider.GOOGLE, GOOGLE_SUB, "보영");

        assertThat(google.getMemberId()).isNotEqualTo(kakao.getMemberId());
        assertThat(kakao.getProvider()).isEqualTo(Provider.KAKAO);
        assertThat(google.getProvider()).isEqualTo(Provider.GOOGLE);
    }

    /**
     * 카카오는 숫자 회원번호, 구글은 {@code sub} 문자열이라 현실에서 겹칠 일은 없다.
     * 그래도 겹친다고 가정하고 확인한다 — 유니크 키가 provider 를 포함하는지가 요점이다.
     */
    @Test
    @DisplayName("provider 가 다르면 provider_user_id 가 같아도 충돌하지 않는다")
    void sameProviderUserIdIsAllowedAcrossProviders() {
        String sharedId = "1234567890";

        Member kakao = signup(Provider.KAKAO, sharedId, "보영");
        Member google = signup(Provider.GOOGLE, sharedId, "보영");

        assertThat(kakao.getMemberId()).isNotEqualTo(google.getMemberId());
    }

    @Test
    @DisplayName("카카오로 가입해도 구글 조회는 비어 있다 — 구글로는 신규 가입 흐름을 탄다")
    void kakaoMemberIsNotFoundByGoogle() {
        signup(Provider.KAKAO, KAKAO_ID, "보영");

        assertThat(memberService.findBySocial(Provider.GOOGLE, GOOGLE_SUB)).isEmpty();
        assertThat(memberService.findBySocial(Provider.KAKAO, KAKAO_ID)).isPresent();
    }

    @Test
    @DisplayName("같은 provider 로 같은 계정을 두 번 가입할 수는 없다")
    void sameProviderAndIdCannotSignupTwice() {
        signup(Provider.KAKAO, KAKAO_ID, "보영");

        assertThatThrownBy(() -> signup(Provider.KAKAO, KAKAO_ID, "보영2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 가입된 계정");
    }

    /** 닉네임에는 유니크 제약이 없다. 두 계정이 같은 닉네임을 쓰는 것을 막지 않는다. */
    @Test
    @DisplayName("두 계정이 같은 닉네임을 써도 된다")
    void duplicateNicknameIsAllowed() {
        Member kakao = signup(Provider.KAKAO, KAKAO_ID, "보영");
        Member google = signup(Provider.GOOGLE, GOOGLE_SUB, "보영");

        assertThat(kakao.getNickname()).isEqualTo(google.getNickname());
    }

    @Test
    @DisplayName("약관 동의 이력도 계정별로 따로 남는다")
    void termsAgreementsAreRecordedPerAccount() {
        Member kakao = signup(Provider.KAKAO, KAKAO_ID, "보영");
        Member google = signup(Provider.GOOGLE, GOOGLE_SUB, "보영");

        assertThat(termsAgreementRepository.findAllByMemberId(kakao.getMemberId())).hasSize(2);
        assertThat(termsAgreementRepository.findAllByMemberId(google.getMemberId())).hasSize(2);
    }

    /**
     * 연동이 없으므로 한쪽 탈퇴가 다른 쪽에 영향을 주면 안 된다.
     * 익명화는 탈퇴한 회원의 행에만 적용된다.
     */
    @Test
    @DisplayName("카카오 계정을 탈퇴해도 구글 계정은 그대로 로그인된다")
    void withdrawingOneAccountDoesNotAffectTheOther() {
        Member kakao = signup(Provider.KAKAO, KAKAO_ID, "보영");
        Member google = signup(Provider.GOOGLE, GOOGLE_SUB, "보영");

        memberService.withdraw(kakao.getMemberId(), Instant.now());

        assertThat(memberService.findBySocial(Provider.KAKAO, KAKAO_ID)).isEmpty();
        assertThat(memberService.findBySocial(Provider.GOOGLE, GOOGLE_SUB))
                .get()
                .satisfies(m -> {
                    assertThat(m.getMemberId()).isEqualTo(google.getMemberId());
                    assertThat(m.getStatus()).isEqualTo(MemberStatus.ACTIVE);
                    assertThat(m.getNickname()).isEqualTo("보영");
                });
    }

    /** 이메일 scope 를 어느 쪽도 요청하지 않으므로 이메일로 계정이 합쳐질 수 없다. */
    @Test
    @DisplayName("두 계정 모두 이메일이 비어 있다 — 이메일로 합쳐질 여지가 없다")
    void emailIsNeverStored() {
        Member kakao = signup(Provider.KAKAO, KAKAO_ID, "보영");
        Member google = signup(Provider.GOOGLE, GOOGLE_SUB, "보영");

        assertThat(kakao.getEmail()).isNull();
        assertThat(google.getEmail()).isNull();
    }

    private Member signup(Provider provider, String providerUserId, String nickname) {
        return memberService.signup(provider, providerUserId, nickname,
                Set.of(TermsType.SERVICE, TermsType.PRIVACY));
    }
}
