package com.ssafy.a307.estimatevalidation.domain;

import java.util.Set;

public record ValidatedLine(EstimateLine line, CostReference reference, Set<ValidationFlag> flags) {
    public ValidatedLine {
        flags = Set.copyOf(flags);
    }

    public boolean reviewRecommended() {
        return !flags.isEmpty();
    }
}
