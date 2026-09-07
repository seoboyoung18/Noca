package com.ssafy.a307.member.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.entity.TermsAgreement;
import com.ssafy.a307.member.entity.TermsType;
import com.ssafy.a307.member.repository.MemberRepository;
import com.ssafy.a307.member.repository.TermsAgreementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 회원 가입·조회·탈퇴. 소셜 인증 자체는 {@code auth} 패키지가 담당하고,
 * 여기서는 인증이 끝난 뒤의 회원 상태만 다룬다.
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final TermsAgreementRepository termsAgreementRepository;

    /** 소셜 로그인 시 회원을 찾는다. 탈퇴 회원은 익명화돼 있어 애초에 걸리지 않는다. */
    @Transactional(readOnly = true)
    public Optional<Member> findBySocial(Provider provider, String providerUserId) {
        return memberRepository.findByProviderAndProviderUserId(provider, providerUserId);
    }

    @Transactional(readOnly = true)
    public Optional<Member> findById(Long memberId) {
        return memberRepository.findById(memberId);
    }

    /**
     * 약관 동의를 마친 신규 회원을 만든다. 회원과 동의 이력을 같은 트랜잭션에서 남긴다 —
     * 회원만 생기고 동의 이력이 빠지면 증빙이 사라지기 때문이다.
     * <p>
     * 동의 버전은 요청에서 받지 않고 {@link TermsPolicy} 가 아는 값을 쓴다.
     * 클라이언트가 보낸 버전을 그대로 기록하면 이력이 증빙 구실을 못 한다.
     */
    @Transactional
    public Member signup(Provider provider, String providerUserId, String nickname,
                         Set<TermsType> agreedTerms) {
        if (!agreedTerms.containsAll(TermsPolicy.REQUIRED)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "필수 약관에 모두 동의해야 합니다.");
        }
        memberRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 계정입니다.");
                });

        Member member = memberRepository.save(Member.register(provider, providerUserId, nickname));

        List<TermsAgreement> agreements = agreedTerms.stream()
                .map(type -> TermsAgreement.of(member.getMemberId(), type, TermsPolicy.currentVersion(type)))
                .toList();
        termsAgreementRepository.saveAll(agreements);

        return member;
    }

    /**
     * 탈퇴. 개인식별정보를 즉시 익명화해 같은 소셜 계정의 재가입 길을 열어 둔다.
     * 견적·검증 데이터는 {@code member_id} 로 남으므로 함께 지워지지 않는다.
     */
    @Transactional
    public void withdraw(Long memberId, Instant now) {
        memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 회원입니다."))
                .withdraw(now);
    }
}
