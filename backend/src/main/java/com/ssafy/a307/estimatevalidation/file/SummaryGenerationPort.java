package com.ssafy.a307.estimatevalidation.file;

import java.util.List;

public interface SummaryGenerationPort {

    GeneratedSummary generate(SummaryRequest request);

    record SummaryRequest(long validationId, String grade, long claimedTotal, List<SummaryIssue> issues) {
        public SummaryRequest {
            if (validationId < 1) throw new IllegalArgumentException("validationId must be positive");
            if (grade == null || grade.isBlank()) throw new IllegalArgumentException("grade is required");
            if (claimedTotal < 0) throw new IllegalArgumentException("claimedTotal must not be negative");
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    record SummaryIssue(int lineNo, String itemName, String flag, String reason) {
        public SummaryIssue {
            if (lineNo < 1) throw new IllegalArgumentException("lineNo must be positive");
            if (itemName == null || itemName.isBlank()) throw new IllegalArgumentException("itemName is required");
            if (flag == null || flag.isBlank()) throw new IllegalArgumentException("flag is required");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        }
    }

    record GeneratedSummary(String text, String model) {
        public GeneratedSummary {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("text is required");
            if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
        }
    }
}
