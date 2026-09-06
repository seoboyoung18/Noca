package com.ssafy.a307.estimatevalidation.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class EstimateValidationEngine {

    public List<ValidatedLine> validate(ValidationInput input) {
        List<EstimateLine> lines = input.lines().stream()
                .sorted(Comparator.comparingInt(EstimateLine::lineNo))
                .toList();
        Set<LaborKey> seenPositiveLabor = new HashSet<>();
        List<ValidatedLine> results = new ArrayList<>(lines.size());

        for (EstimateLine line : lines) {
            LinkedHashSet<ValidationFlag> flags = new LinkedHashSet<>();
            CostReference reference = input.referencesByLineNo().get(line.lineNo());
            StandardRepairMethod method = line.workType().standardMethod().orElse(null);

            if (line.partCode() == null) {
                flags.add(ValidationFlag.UNMAPPED_ITEM);
            } else if (method == null || reference == null) {
                flags.add(ValidationFlag.INSUFFICIENT_REFERENCE);
            } else if (line.subtotal() > reference.costP75()) {
                flags.add(ValidationFlag.OVER_P75);
            }

            if (input.analysis().present()
                    && method == StandardRepairMethod.EXCHANGE
                    && line.partCode() != null
                    && !input.analysis().analyzedPartCodes().contains(line.partCode())) {
                flags.add(ValidationFlag.NOT_IN_ANALYSIS);
            }

            if (line.partCode() != null && method != null && line.laborCost() > 0) {
                LaborKey key = new LaborKey(line.partCode(), method);
                if (!seenPositiveLabor.add(key)) {
                    flags.add(ValidationFlag.DUPLICATE_LABOR);
                }
            }
            results.add(new ValidatedLine(line, reference, flags));
        }
        return List.copyOf(results);
    }

    private record LaborKey(String partCode, StandardRepairMethod method) {
    }
}
