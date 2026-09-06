package com.ssafy.a307.estimatevalidation.domain;

public enum StandardRepairMethod {
    EXCHANGE("exchange"),
    SHEET_METAL("sheet_metal"),
    COATING("coating"),
    REPAIR("repair");

    private final String code;

    StandardRepairMethod(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
