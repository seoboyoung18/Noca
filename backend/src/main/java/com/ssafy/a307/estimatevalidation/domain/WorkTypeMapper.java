package com.ssafy.a307.estimatevalidation.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class WorkTypeMapper {

    private static final Map<String, WorkType> BY_NAME = Map.ofEntries(
            Map.entry("교환", WorkType.REPLACEMENT),
            Map.entry("exchange", WorkType.REPLACEMENT),
            Map.entry("탈착", WorkType.DETACHMENT),
            Map.entry("판금", WorkType.SHEET_METAL),
            Map.entry("sheet_metal", WorkType.SHEET_METAL),
            Map.entry("도장", WorkType.PAINTING),
            Map.entry("coating", WorkType.PAINTING),
            Map.entry("오버홀", WorkType.OVERHAUL),
            Map.entry("수리", WorkType.REPAIR),
            Map.entry("repair", WorkType.REPAIR));

    private WorkTypeMapper() {
    }

    public static Optional<WorkType> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NAME.get(value.strip().toLowerCase(Locale.ROOT)));
    }
}
