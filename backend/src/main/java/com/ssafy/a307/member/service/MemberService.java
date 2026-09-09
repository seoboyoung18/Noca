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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 회원 가입·조회·탈퇴. 소셜 인증 자체는 {@code auth} 패키지가 담당하고,
 * 여기서는 인증이 끝난 뒤의 회원 상태만 다룬다.
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final TermsAgreementRepository termsAgreementRepository;
    private final ForbiddenNicknamePolicy forbiddenNicknamePolicy;

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
     * 세션이 가리키는 회원을 꺼낸다. 없거나 탈퇴 상태면 401 로 끊는다.
     * <p>
     * 인가 규칙이 {@code ROLE_USER} 이상을 요구하므로 여기 도달하면 회원이 확정돼 있어야
     * 정상이다. 그래도 확인하는 이유는 세션은 살아 있는데 회원 행이 사라진 상태
     * (다른 기기에서 탈퇴 등)가 가능하기 때문이다.
     */
    @Transactional(readOnly = true)
    public Member activeMember(Long memberId) {
        return memberRepository.findById(memberId)
                .filter(Member::isActive)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED, "다시 로그인해 주세요."));
    }

    /**
     * 닉네임을 바꾼다. <b>중복 검사를 하지 않는다</b> — 소셜 전용이라 닉네임이 식별자가 아니고
     * DDL 에도 유니크 제약이 없다. 값이 겹쳐도 로그인·조회에 영향이 없다.
     */
    @Transactional
    public Member changeNickname(Long memberId, String nickname) {
        Member member = activeMember(memberId);
        member.changeNickname(normalizeNickname(nickname));
        return member;
    }

    /**
     * 닉네임을 다듬고 규칙 위반을 400 으로 바꾼다. 길이와 금칙어를 한자리에서 보므로
     * 가입과 수정이 같은 판정을 받는다.
     * <p>
     * {@link NicknamePolicy} 는 웹 계층을 모르는 순수 정책이라 {@code IllegalArgumentException}
     * 을 던진다. 그대로 두면 전역 핸들러의 catch-all 에 걸려 500 이 나간다.
     */
    private String normalizeNickname(String nickname) {
        String normalized;
        try {
            normalized = NicknamePolicy.normalize(nickname);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }

        // 어떤 단어가 걸렸는지 알려주지 않는다 — 목록을 역추적해 우회법을 학습시킨다
        if (forbiddenNicknamePolicy.isForbidden(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사용할 수 없는 닉네임입니다.");
        }
        return normalized;
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

        Member member = insertMember(provider, providerUserId, normalizeNickname(nickname));

        List<TermsAgreement> agreements = agreedTerms.stream()
                .map(type -> TermsAgreement.of(member.getMemberId(), type, TermsPolicy.currentVersion(type)))
                .toList();
        termsAgreementRepository.saveAll(agreements);

        return member;
    }

    /**
     * 위의 조회는 동시 요청을 막지 못한다. 두 요청이 같은 순간에 들어오면 둘 다 "없음"을 보고
     * 둘 다 INSERT 를 시도한다. 중복을 실제로 막는 것은 {@code uk_member_provider} 제약이다.
     * <p>
     * 그래서 제약 위반을 잡아 409 로 바꾼다. 이 처리가 없으면 catch-all 예외 핸들러에 걸려
     * 500 이 나간다 — 가입 버튼을 두 번 누르는 정도로도 재현된다.
     * <p>
     * {@code saveAndFlush} 를 쓰는 이유는 위반을 <b>이 자리에서</b> 받기 위해서다.
     * {@code save} 만 하면 트랜잭션 커밋 시점까지 미뤄져 이 catch 를 지나쳐 버린다.
     * <p>
     * 트랜잭션은 통째로 롤백된다. 회원만 남고 약관 이력이 빠지는 어중간한 상태가 생기지 않는다.
     * <p>
     * {@code private} 이 아닌 이유는 테스트에서 직접 부르기 위해서다. 동시 요청 테스트만으로는
     * 이 경로가 실제로 실행됐는지 보장할 수 없다 — 스레드가 어긋나면 위의 사전 조회에서 걸러져
     * 이 catch 를 한 번도 지나지 않고도 테스트가 통과한다.
     */
    Member insertMember(Provider provider, String providerUserId, String nickname) {
        try {
            return memberRepository.saveAndFlush(Member.register(provider, providerUserId, nickname));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 계정입니다.");
        }
    }

    /**
     * 재동의 대상 항목. 약관이 개정되면 기존 동의는 옛 버전에 대한 것이라 증빙 구실을 못 한다.
     * 판별 규칙 자체는 {@link TermsPolicy#reagreementTargets} 에 있다.
     * <p>
     * 전용 쿼리를 만들지 않고 이력을 통째로 읽어 메모리에서 집계한다. 회원당 행 수가
     * (필수 약관 2종 × 개정 횟수)라 정렬·집계를 DB 로 미룰 근거가 아직 없다.
     * <p>
     * 정렬을 붙이는 이유 — 조회 순서는 보장되지 않는다. 같은 항목에 이력이 여러 개 쌓였을 때
     * (1.0 동의 → 개정 → 1.1 재동의) 어느 쪽이 "최신"인지가 조회 순서에 좌우되면 안 된다.
     * {@code agreedAt} 이 같을 수 있어({@code @CreatedDate} 가 한 트랜잭션에서 같은 값을 넣는다)
     * {@code agreementId} 로 한 번 더 가른다.
     * <p>
     * 회원 상태는 보지 않는다. 탈퇴 회원을 걸러내는 것은 이 판별이 아니라 부르는 쪽의 일이다.
     *
     * @return 다시 동의받아야 할 항목. 비어 있으면 재동의가 필요 없다
     */
    @Transactional(readOnly = true)
    public Set<TermsType> reagreementTargets(Long memberId) {
        Map<TermsType, String> latestAgreed = termsAgreementRepository.findAllByMemberId(memberId).stream()
                .sorted(Comparator.comparing(TermsAgreement::getAgreedAt)
                        .thenComparing(TermsAgreement::getAgreementId))
                .collect(Collectors.toMap(
                        TermsAgreement::getTermsType,
                        TermsAgreement::getVersion,
                        (earlier, later) -> later,
                        () -> new EnumMap<>(TermsType.class)));

        return TermsPolicy.reagreementTargets(latestAgreed);
    }

    /**
     * 탈퇴. 개인식별정보를 즉시 익명화해 같은 소셜 계정의 재가입 길을 열어 둔다.
     * 견적·검증 데이터는 {@code member_id} 로 남으므로 함께 지워지지 않는다.
     *
     * @return 지워진 프로필 이미지 키. 없었으면 {@code null} 이다.
     *         <b>DB 컬럼을 비우는 것만으로는 S3 파일이 남는다</b> — 실제 객체 삭제는
     *         트랜잭션 밖 best-effort 라 호출부가 이 값을 받아 처리한다
     *         ({@code MemberProfileService#withdraw}).
     */
    @Transactional
    public String withdraw(Long memberId, Instant now) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 회원입니다."));

        // withdraw() 가 컬럼을 비우므로 그 전에 들고 있어야 한다
        String removedImageKey = member.getProfileImageKey();
        member.withdraw(now);
        return removedImageKey;
    }
}
