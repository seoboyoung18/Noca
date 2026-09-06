package com.ssafy.a307.estimatevalidation.entity;

public record ReferenceSnapshot(
        int costMin,
        int costMedian,
        int costP75,
        int costMax,
        int caseCount) {
}
