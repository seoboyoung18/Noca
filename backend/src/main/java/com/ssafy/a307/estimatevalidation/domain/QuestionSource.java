package com.ssafy.a307.estimatevalidation.domain;

import java.util.Set;

public record QuestionSource(
        int lineNo,
        String rawItemName,
        String standardPartName,
        WorkType workType,
        Set<ValidationFlag> flags
) {
    public QuestionSource {
        if (lineNo < 1) throw new IllegalArgumentException("lineNo must be positive");
        if (rawItemName == null || rawItemName.isBlank()) throw new IllegalArgumentException("rawItemName is required");
        if (workType == null) throw new IllegalArgumentException("workType is required");
        flags = flags == null ? Set.of() : Set.copyOf(flags);
    }
}
