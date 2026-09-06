package com.ssafy.a307.estimatevalidation.domain;

public enum ValidationFlag {
    OVER_P75(10),
    NOT_IN_ANALYSIS(20),
    DUPLICATE_LABOR(30),
    UNMAPPED_ITEM(40),
    INSUFFICIENT_REFERENCE(50);

    private final int questionOrder;

    ValidationFlag(int questionOrder) {
        this.questionOrder = questionOrder;
    }

    public int questionOrder() {
        return questionOrder;
    }
}
