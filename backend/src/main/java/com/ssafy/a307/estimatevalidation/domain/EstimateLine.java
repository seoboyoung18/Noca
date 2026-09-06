package com.ssafy.a307.estimatevalidation.domain;

import java.util.Objects;

public record EstimateLine(
        int lineNo,
        String rawItemName,
        String partCode,
        String standardPartName,
        WorkType workType,
        long quantity,
        long partCost,
        long laborCost
) {
    public EstimateLine {
        if (lineNo < 1) throw new IllegalArgumentException("lineNo must be positive");
        if (rawItemName == null || rawItemName.isBlank()) throw new IllegalArgumentException("rawItemName is required");
        Objects.requireNonNull(workType, "workType");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        if (partCost < 0 || laborCost < 0) throw new IllegalArgumentException("cost must not be negative");
        if (partCost == 0 && laborCost == 0) throw new IllegalArgumentException("partCost or laborCost must be positive");
        partCode = blankToNull(partCode);
        standardPartName = blankToNull(standardPartName);
    }

    public long subtotal() {
        return Math.multiplyExact(Math.addExact(partCost, laborCost), quantity);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
