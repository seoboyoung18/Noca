package com.ssafy.a307.estimatevalidation.domain;

public enum WorkType {
    REPLACEMENT("교환", StandardRepairMethod.EXCHANGE),
    DETACHMENT("탈착", null),
    SHEET_METAL("판금", StandardRepairMethod.SHEET_METAL),
    PAINTING("도장", StandardRepairMethod.COATING),
    OVERHAUL("오버홀", null),
    REPAIR("수리", StandardRepairMethod.REPAIR);

    private final String displayName;
    private final StandardRepairMethod standardMethod;

    WorkType(String displayName, StandardRepairMethod standardMethod) {
        this.displayName = displayName;
        this.standardMethod = standardMethod;
    }

    public String displayName() {
        return displayName;
    }

    public java.util.Optional<StandardRepairMethod> standardMethod() {
        return java.util.Optional.ofNullable(standardMethod);
    }
}
