package com.ssafy.a307.estimate.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * 산정하지 못한 부위의 사유 코드와 화면 표시 문구 (S15P21A307-534).
 *
 * <p>AI 연동 계약(2026-09-17)이 정한 값은 지금 {@code INSUFFICIENT_CASES} 하나다. 문구를 서버가
 * 주는 이유는 {@link RepairMethodDisplay} 와 같다 — 프론트와 PDF 가 각자 하드코딩하면 어휘가
 * 바뀔 때 여러 곳을 고쳐야 한다.
 */
public enum UnresolvedReasonDisplay {

    INSUFFICIENT_CASES("근거 사례 부족");

    private final String displayName;

    UnresolvedReasonDisplay(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 사유 코드를 표시 문구로 바꾼다.
     *
     * <p><b>모르는 코드면 {@code null} 이다.</b> {@link RepairMethodDisplay#displayNameOf} 처럼
     * 원문을 돌려주지 않는 이유는, 이 값이 문장 안에 끼워지기 때문이다 — "INSUFFICIENT_DATA 로
     * 총액에서 제외했습니다" 같은 문장이 사용자 문서에 나가는 것보다, 사유 없이 "산정하지 못해
     * 제외했습니다" 가 낫다. 원래 코드는 응답의 {@code reason} 에 그대로 남는다.
     */
    public static String displayNameOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(display -> display.name().equals(normalized))
                .map(UnresolvedReasonDisplay::displayName)
                .findFirst()
                .orElse(null);
    }
}
