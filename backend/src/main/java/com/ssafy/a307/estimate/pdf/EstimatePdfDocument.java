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

    /**
     * 사진을 그릴 크기 (S15P21A307-547).
     *
     * <p><b>박스를 그리려면 틀이 먼저 정해져 있어야 한다.</b> 박스는 사진 크기에 대한
     * 퍼센트인데, 틀의 높이가 {@code auto} 면 CSS 가 퍼센트 높이를 0 으로 계산해 박스가
     * 선 하나로 납작해진다. 그래서 서버가 원본 비율을 지켜 mm 로 계산해 넘긴다.
     */
    public record Frame(double widthMm, double heightMm) {
    }

    /**
     * 사진 위에 그릴 파손 위치 (S15P21A307-547).
     *
     * <p><b>사진 크기에 대한 퍼센트다.</b> PDF 는 사진을 지면 폭에 맞춰 줄이므로 픽셀로
     * 두면 배율이 어긋난다. 비율로 두면 얼마로 줄이든 박스가 손상 위에 남는다.
     *
     * @param number 견적 항목 표의 순번과 같은 번호. 표에 없는 부위가 검출됐으면 {@code null}
     *               이고 번호 없이 박스만 그린다 — 표에 없는 번호를 붙이면 찾을 수 없다
     */
    public record Box(Integer number, double left, double top, double width, double height) {

        public boolean hasNumber() {
            return number != null;
        }
    }

    /**
     * @param frame   사진을 그릴 크기. 치수를 모르면 {@code null} 이고 그때는 박스도 없다
     * @param boxes   사진 위에 그릴 파손 위치. 좌표나 치수를 모르면 빈 목록이고 사진만 나간다
     * @param dataUri {@code data:image/jpeg;base64,...}. 실을 사진이 아예 없으면 null
     * @param overlay 실은 것이 <b>분석 오버레이</b>인가. false 면 사용자가 올린 사진이다 —
     *                캡션이 둘을 구분해 적는다. 파손 표시가 없는 사진을 "분석 이미지" 라고
     *                부르면 없는 근거가 있는 것처럼 보인다 (S15P21A307-547)
     */
    public record Image(Long imageId, String angleCode, String dataUri, boolean overlay,
                        Frame frame, List<Box> boxes) {

        public Image {
            boxes = boxes == null ? List.of() : List.copyOf(boxes);
        }

        /** 틀도 박스도 없는 사진. 오버레이와 "읽지 못한" 칸이 이 모양이다. */
        public Image(Long imageId, String angleCode, String dataUri, boolean overlay) {
            this(imageId, angleCode, dataUri, overlay, null, List.of());
        }

        /** 실을 사진이 있는가. 오버레이든 업로드 사진이든 상관없다. */
        public boolean hasImage() {
            return dataUri != null;
        }

        public boolean hasOverlay() {
            return dataUri != null && overlay;
        }

        public boolean hasBoxes() {
            return hasImage() && !boxes.isEmpty();
        }

        public boolean hasFrame() {
            return frame != null;
        }
    }
}
