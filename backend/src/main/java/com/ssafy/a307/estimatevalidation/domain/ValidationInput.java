package com.ssafy.a307.estimatevalidation.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ValidationInput(
        List<EstimateLine> lines,
        Map<Integer, CostReference> referencesByLineNo,
        AnalysisSnapshot analysis
) {
    public ValidationInput {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.isEmpty()) throw new IllegalArgumentException("at least one line is required");
        referencesByLineNo = referencesByLineNo == null ? Map.of() : Map.copyOf(referencesByLineNo);
        analysis = analysis == null ? AnalysisSnapshot.absent() : analysis;
    }
}
