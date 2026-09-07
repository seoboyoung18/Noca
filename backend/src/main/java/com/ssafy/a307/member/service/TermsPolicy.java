package com.ssafy.a307.member.service;

import com.ssafy.a307.member.entity.TermsType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

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
}
