package com.ssafy.a307.estimatevalidation.domain;

public record CostReference(long costP75, int sampleCount) {
    public CostReference {
        if (costP75 < 0) throw new IllegalArgumentException("costP75 must not be negative");
        if (sampleCount < 1) throw new IllegalArgumentException("sampleCount must be positive");
    }
}
