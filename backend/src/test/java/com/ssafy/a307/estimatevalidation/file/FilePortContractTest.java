package com.ssafy.a307.estimatevalidation.file;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePortContractTest {

    @Test
    void storageRequestDefensivelyCopiesDocumentBytes() {
        byte[] source = {1, 2, 3};
        DocumentStoragePort.StoreDocument request =
                new DocumentStoragePort.StoreDocument(source, "image/jpeg", "jpg");

        source[0] = 9;
        byte[] returned = request.content();
        returned[1] = 9;

        assertThat(request.content()).containsExactly(1, 2, 3);
    }

    @Test
    void ocrResultExpressesLineAndDocumentConfidence() {
        EstimateOcrPort.OcrLineItem item = new EstimateOcrPort.OcrLineItem(
                1, "프론트 펜더", "판금", 1, 100_000, 50_000, 150_000L, 0.91
        );
        EstimateOcrPort.OcrExtraction result =
                new EstimateOcrPort.OcrExtraction(List.of(item), 150_000L, 0.87);

        assertThat(result.items()).containsExactly(item);
        assertThat(result.documentConfidence()).isEqualTo(0.87);
        assertThat(result.items().getFirst().confidence()).isEqualTo(0.91);
    }

    @Test
    void ocrRejectsManualDocumentsAndInvalidConfidence() {
        assertThatThrownBy(() -> new EstimateOcrPort.OcrDocument("key", EstimateFileType.MANUAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EstimateOcrPort.OcrExtraction(List.of(), null, 1.01))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
