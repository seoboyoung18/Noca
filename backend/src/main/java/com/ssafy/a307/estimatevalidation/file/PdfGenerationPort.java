package com.ssafy.a307.estimatevalidation.file;

import java.util.List;

public interface PdfGenerationPort {

    GeneratedPdf generate(PdfReport report);

    record PdfReport(
            long validationId,
            String grade,
            String summary,
            long claimedTotal,
            List<PdfLineItem> items,
            String disclaimer
    ) {
        public PdfReport {
            if (validationId < 1) throw new IllegalArgumentException("validationId must be positive");
            if (grade == null || grade.isBlank()) throw new IllegalArgumentException("grade is required");
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
            if (claimedTotal < 0) throw new IllegalArgumentException("claimedTotal must not be negative");
            items = items == null ? List.of() : List.copyOf(items);
            if (disclaimer == null || disclaimer.isBlank()) {
                throw new IllegalArgumentException("disclaimer is required");
            }
        }
    }

    record PdfLineItem(int lineNo, String itemName, long subtotal, String decision, String reason) {
        public PdfLineItem {
            if (lineNo < 1) throw new IllegalArgumentException("lineNo must be positive");
            if (itemName == null || itemName.isBlank()) throw new IllegalArgumentException("itemName is required");
            if (subtotal < 0) throw new IllegalArgumentException("subtotal must not be negative");
            if (decision == null || decision.isBlank()) throw new IllegalArgumentException("decision is required");
        }
    }

    record GeneratedPdf(byte[] content) {
        public GeneratedPdf {
            content = content == null ? null : content.clone();
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("content must not be empty");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
