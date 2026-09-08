package com.ssafy.a307.member.service;

import com.ssafy.a307.member.entity.TermsType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 약관 개정 시 재동의 대상 판별 규칙.
 * <p>
 * 현행 버전 상수를 그대로 읽어 쓴다. {@code "1.0"} 처럼 값을 박아 두면 약관을 개정할 때
 * 테스트가 같이 깨져, 정작 검증하려는 규칙이 아니라 상수 값을 검증하는 테스트가 된다.
 * <p>
 * 개정 상황은 <b>옛 버전 동의 이력</b>으로 재현한다. 현행 버전 쪽을 흔들 필요가 없어
 * {@link TermsPolicy} 를 테스트용으로 주입 가능하게 바꾸지 않아도 된다.
 */
@DisplayName("약관 재동의 대상 판별")
class TermsPolicyTest {

    /** 현행보다 낮은, 이미 지나간 버전. */
    private static final String OLD_VERSION = "0.9";

    @Test
    @DisplayName("필수 약관이 모두 현행 버전이면 재동의 대상이 없다")
    void noTargetWhenEveryRequiredTermIsCurrent() {
        Map<TermsType, String> agreed = new EnumMap<>(TermsType.class);
        TermsPolicy.REQUIRED.forEach(type -> agreed.put(type, TermsPolicy.currentVersion(type)));

        assertThat(TermsPolicy.reagreementTargets(agreed)).isEmpty();
    }

    /**
     * 이 스토리의 핵심이다. 이용약관만 개정했는데 개인정보 처리방침까지 다시 받으면
     * 사용자는 바뀐 것이 무엇인지 알 수 없다.
     */
    @Test
    @DisplayName("개정된 항목만 재동의 대상이 된다")
    void onlyTheRevisedTermBecomesTarget() {
        Map<TermsType, String> agreed = new EnumMap<>(TermsType.class);
        agreed.put(TermsType.SERVICE, OLD_VERSION);
        agreed.put(TermsType.PRIVACY, TermsPolicy.currentVersion(TermsType.PRIVACY));

        assertThat(TermsPolicy.reagreementTargets(agreed))
                .containsExactly(TermsType.SERVICE);
    }

    @Test
    @DisplayName("둘 다 옛 버전이면 둘 다 대상이다")
    void bothBecomeTargetsWhenBothAreOutdated() {
        Map<TermsType, String> agreed = new EnumMap<>(TermsType.class);
        TermsPolicy.REQUIRED.forEach(type -> agreed.put(type, OLD_VERSION));

        assertThat(TermsPolicy.reagreementTargets(agreed))
                .containsExactlyInAnyOrderElementsOf(TermsPolicy.REQUIRED);
    }

    /**
     * 가입과 동의 이력은 한 트랜잭션이라 정상 흐름에서는 나오지 않는 입력이다.
     * 그래도 "동의한 적 없음" 이 "재동의 불필요" 로 뒤집히면 안 되므로 고정해 둔다.
     */
    @Test
    @DisplayName("동의 이력이 없으면 필수 약관 전체가 대상이다")
    void everyRequiredTermIsTargetWhenNothingAgreed() {
        assertThat(TermsPolicy.reagreementTargets(Map.of()))
                .containsExactlyInAnyOrderElementsOf(TermsPolicy.REQUIRED);
    }
}
