package com.ssafy.a307.member.service;

import com.ssafy.a307.member.entity.TermsType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 현재 시행 중인 약관 버전과 필수 동의 항목.
 * <p>
 * 버전을 클라이언트가 보내게 하지 않는다. 임의 값을 기록할 수 있게 되고, 그러면 동의 이력이
 * 증빙 구실을 못 한다. 서버가 아는 값만 남긴다.
 * <p>
 * 약관을 개정하면 여기 버전을 올린다. 외부 설정으로 뺄 만큼 자주 바뀌지 않고,
 * 상수로 두면 값이 바뀐 사실이 커밋 이력에 남는다.
 */
public final class TermsPolicy {

    private static final Map<TermsType, String> VERSIONS = new EnumMap<>(TermsType.class);

    static {
        VERSIONS.put(TermsType.SERVICE, "1.0");
        VERSIONS.put(TermsType.PRIVACY, "1.0");
    }

    /** 가입에 반드시 필요한 약관. 하나라도 빠지면 가입을 거절한다. */
    public static final Set<TermsType> REQUIRED = Set.of(TermsType.SERVICE, TermsType.PRIVACY);

    private TermsPolicy() {
    }

    public static String currentVersion(TermsType type) {
        return VERSIONS.get(type);
    }

    /**
     * 재동의 대상 항목. 필수 약관 중 <b>가장 최근 동의 버전</b>이 현행과 다른 것들을 돌려준다.
     * <p>
     * 개정 일자도 회원 가입 시점도 보지 않는다. 개정 뒤에 가입한 회원은 그 자리에서 현행 버전으로
     * 동의하므로 버전이 일치해 자연히 빠진다 — "개정 시점에 이미 가입해 있던 회원"을 따로
     * 골라낼 필요가 없다는 뜻이다.
     * <p>
     * 개정된 항목만 걸린다. {@code SERVICE} 만 올리면 {@code PRIVACY} 는 양쪽이 같아 대상이 아니다.
     * 그래서 반환값이 불리언이 아니라 <b>항목 집합</b>이다 — 화면이 개정된 약관만 다시 보여줄 수 있다.
     * <p>
     * 맵에 없는 항목은 현행과 "다름"으로 취급돼 대상이 된다. 가입과 동의 이력은 한 트랜잭션이라
     * ({@link MemberService#signup}) 정상 흐름에서는 이력 없는 회원이 생기지 않지만, 없다고 단정하면
     * 그런 행이 생겼을 때 조용히 "재동의 불필요" 로 분류된다.
     *
     * @param latestAgreedVersions 항목별 가장 최근 동의 버전. 동의한 적 없는 항목은 넣지 않는다
     * @return 다시 동의받아야 할 항목. 비어 있으면 재동의가 필요 없다
     */
    public static Set<TermsType> reagreementTargets(Map<TermsType, String> latestAgreedVersions) {
        return REQUIRED.stream()
                .filter(type -> !currentVersion(type).equals(latestAgreedVersions.get(type)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
