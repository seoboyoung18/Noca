package com.ssafy.a307.estimatevalidation.domain;

public enum ValidationGrade {
    APPROPRIATE("적정 범위"),
    CAUTION("주의"),
    NEEDS_REVIEW("확인 필요");

    private final String displayName;

    ValidationGrade(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
