package com.ssafy.a307.estimatevalidation.domain;

import java.util.Set;

public record AnalysisSnapshot(boolean present, Set<String> analyzedPartCodes) {
    public AnalysisSnapshot {
        analyzedPartCodes = analyzedPartCodes == null ? Set.of() : Set.copyOf(analyzedPartCodes);
    }

    public static AnalysisSnapshot absent() {
        return new AnalysisSnapshot(false, Set.of());
    }
}
