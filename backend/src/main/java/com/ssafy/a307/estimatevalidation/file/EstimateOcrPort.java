package com.ssafy.a307.estimatevalidation.file;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;

import java.util.List;

public interface EstimateOcrPort {
    OcrExtraction extract(OcrDocument document);

    record OcrDocument(String storageKey, EstimateFileType fileType) {
        public OcrDocument {
            if (storageKey == null || storageKey.isBlank()) throw new IllegalArgumentException("storageKey is required");
            if (fileType == null || fileType == EstimateFileType.MANUAL) {
                throw new IllegalArgumentException("OCR requires IMAGE or PDF");
            }
        }
    }

    record OcrLineItem(
            int lineNo, String rawItemName, String workType, long quantity,
            long partCost, long laborCost, Long subtotal, double confidence) {
        public OcrLineItem {
            if (lineNo < 1 || rawItemName == null || rawItemName.isBlank() || quantity < 1) {
                throw new IllegalArgumentException("invalid OCR line");
            }
            if (partCost < 0 || laborCost < 0 || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("invalid OCR line values");
            }
        }
    }

    record OcrExtraction(List<OcrLineItem> items, Long claimedTotal, double documentConfidence) {
        public OcrExtraction {
            items = items == null ? List.of() : List.copyOf(items);
            if (documentConfidence < 0 || documentConfidence > 1) {
                throw new IllegalArgumentException("documentConfidence must be between 0 and 1");
            }
        }
    }
}
