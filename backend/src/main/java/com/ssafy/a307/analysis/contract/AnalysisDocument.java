package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 표준화 계약 {@code inference-standardized-1.1.0} 문서 하나.
 * 정본은 {@code shared/vision/common_schema.json} 이며 <b>공용 vision 계층 소유</b>다 —
 * 이 레코드는 그 스키마를 자바 쪽에 옮겨 적은 것이고, 계약을 바꾸는 곳이 아니다.
 *
 * <p><b>문서 하나가 이미지 한 장이다.</b> 사고 한 건에 이미지가 여러 장이므로 적재는 문서를
 * 여러 번 받는다. 봉투로 감싸 사고 단위로 묶지 않는 이유는, 그렇게 하려면 파이프라인이 소유한
 * 스키마를 바꿔야 하고 사고–이미지 연결은 이미 {@code accident_image} 가 갖고 있기 때문이다.
 *
 * <p><b>{@code detections} 가 비어 있는 것은 정상이다.</b> 손상이 없는 이미지이고 적재 실패가 아니다.
 * 그래서 {@link NotEmpty} 가 아니라 {@link NotNull} 이다.
 */
public record AnalysisDocument(

        @NotNull(message = "schema_version 은 필수입니다.")
        @Pattern(regexp = "1\\.1\\.0", message = "지원하는 계약 버전은 1.1.0 뿐입니다.")
        @JsonProperty("schema_version")
        String schemaVersion,

        @NotNull(message = "image 는 필수입니다.")
        @Valid
        @JsonProperty("image")
        AnalysisImageRef image,

        @NotNull(message = "detections 는 필수입니다. 손상이 없으면 빈 배열입니다.")
        @Valid
        @JsonProperty("detections")
        List<Detection> detections
) {

    /** 계약이 고정한 버전. {@code "schema_version": {"const": "1.1.0"}}. */
    public static final String SCHEMA_VERSION = "1.1.0";

    public AnalysisDocument {
        detections = detections == null ? null : List.copyOf(detections);
    }
}
