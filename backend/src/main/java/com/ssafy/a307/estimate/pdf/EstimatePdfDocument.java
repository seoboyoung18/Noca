package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.estimate.dto.EstimateReportResponse;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * PDF 한 장에 그릴 것. 내용은 리포트 조립 결과({@link EstimateReportResponse})이고, 여기에 번호와
 * <b>이미지 바이트</b>만 더한다 — 리포트의 {@code overlayUrl} 은 5분짜리 서명 URL 이라 파일에 박을 수 없다.
 *
 * @param images 리포트의 이미지와 같은 순서. 오버레이가 없는 사진은 {@code dataUri} 가 null 이고
 *               그 칸에 "분석 이미지 없음" 이 그려진다
 */
public record EstimatePdfDocument(
        String reportNo,
        EstimateReportResponse report,
        List<Image> images,
        Instant generatedAt) {

    public EstimatePdfDocument {
        Objects.requireNonNull(reportNo, "reportNo");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(generatedAt, "generatedAt");
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** @param dataUri {@code data:image/jpeg;base64,...}. 오버레이가 없으면 null */
    public record Image(Long imageId, String angleCode, String dataUri) {

        public boolean hasOverlay() {
            return dataUri != null;
        }
    }
}
