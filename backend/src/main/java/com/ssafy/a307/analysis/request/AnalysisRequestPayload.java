package com.ssafy.a307.analysis.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.entity.ImageVariant;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.analysis.entity.AnalysisJob;

import java.util.List;

/**
 * {@code POST {AI}/analyze} 요청 본문 — 계약 ④ (Docs/Api/AI 연동 계약).
 *
 * <p><b>AI 에 보내는 모양은 이 파일에만 있다.</b> AI 쪽에서 입력 형식이 바뀔 수 있다고 알려 왔다
 * (2026-09-15, 검색 성능 검토 중). 바뀌면 이 파일과 {@code AiAnalysisClientTest} 만 고친다.
 *
 * <p><b>사진은 {@link #ANALYSIS_VARIANT} 를 준다 — 원본이 아니다.</b> 2026-09-15 AI 담당과
 * 확정했다. 모델이 긴 변 960px 로 추론하므로 1600px 축소본으로 충분하고, 축소본은 확대하지
 * 않아 800x600 사진은 원본과 해상도가 같다. 원본은 EXIF(GPS)를 달고 staging 에서 7일 뒤
 * 사라지며, 저장소 어댑터가 조회 URL 발급을 거절한다.
 *
 * @param callbackUrl      AI 가 결과를 보낼 주소. 계약 ⑥ 경로다
 * @param selectedPartCode 사용자가 고른 부위 (S15P21A307-570). 부품을 찾지 못한 손상에 이 부위를
 *                         붙여 달라는 뜻이다. <b>값이 있을 때만 싣는다</b> — 부위를 고르지 않은
 *                         분석의 본문은 이 필드가 생기기 전과 같다. 필드 이름은 AI 담당 확인 전이다
 */
public record AnalysisRequestPayload(
        Long jobId,
        String requestId,
        Vehicle vehicle,
        List<Image> images,
        String callbackUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String selectedPartCode) {

    /** 분석에 보내는 사진 종류. {@code AccidentImageProperties} 주석의 "분석용 리사이즈본" 이다. */
    public static final ImageVariant ANALYSIS_VARIANT = ImageVariant.RESIZED;

    /**
     * 유사 사례 검색의 완화 단계 입력이다(차종 → 차급 → 전체).
     *
     * @param carClass {@code Compact} 처럼 데이터셋 표기 그대로다. 열거형 이름이 아니다
     */
    public record Vehicle(Long modelId, String manufacturer, String modelName, String carClass,
                          Integer modelYear) {

        static Vehicle from(Accident accident) {
            return new Vehicle(
                    accident.getSnapshotModelId(),
                    accident.getSnapshotManufacturer(),
                    accident.getSnapshotModelName(),
                    accident.getSnapshotCarClass() == null ? null : accident.getSnapshotCarClass().getCode(),
                    accident.getSnapshotModelYear() == null ? null : accident.getSnapshotModelYear().intValue());
        }
    }

    /**
     * @param angleCode 촬영 가이드 각도. 가이드를 건너뛴 사진은 {@code null} 이다
     * @param url       presigned GET URL. 버킷이 비공개라 AI 가 이것으로만 읽는다
     * @param expiresAt URL 만료 시각(ISO-8601)
     */
    public record Image(Long imageId, String angleCode, String url, String expiresAt) {

        static Image of(AccidentImage image, AccidentImageStoragePort.PresignedDownload download) {
            return new Image(image.getImageId(), image.getAngleCode(),
                    download.url().toString(), download.expiresAt().toString());
        }
    }

    /** 이 사진을 분석에 보낼 수 있는가. 업로드 완료 통보가 끝나야 축소본이 생긴다. */
    public static boolean isAnalyzable(AccidentImage image) {
        return image.hasVariant(ANALYSIS_VARIANT);
    }

    static AnalysisRequestPayload of(AnalysisJob job, Accident accident, List<Image> images,
                                     String callbackUrl) {
        return new AnalysisRequestPayload(
                job.getJobId(), job.getRequestId(), Vehicle.from(accident), List.copyOf(images), callbackUrl,
                job.getSelectedPartCode());
    }
}
