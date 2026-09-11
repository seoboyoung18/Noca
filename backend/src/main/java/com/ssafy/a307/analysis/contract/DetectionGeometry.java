package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 계약의 {@code detection.geometry}.
 *
 * <p><b>적재가 이 값을 저장하지 않는다.</b> {@code damaged_part} 에 좌표 컬럼이 없기 때문이다
 * ({@link com.ssafy.a307.analysis.entity.DamagedPart} Javadoc 참고). 그래도 계약대로 받아
 * <b>검증은 한다</b> — 여기서 걸러야 잘못된 좌표가 오버레이 생성 쪽으로 흘러가지 않고,
 * 나중에 컬럼이 생겼을 때 파싱 계층을 다시 쓰지 않아도 된다.
 *
 * <p>{@code coordinate_system}·{@code bbox_format} 은 계약이 상수로 고정했다. 값이 다르면
 * 좌표 해석이 통째로 달라지므로 <b>조용히 받아들이지 않고 거절</b>한다.
 */
public record DetectionGeometry(

        @NotNull(message = "geometry.coordinate_system 은 필수입니다.")
        @Pattern(regexp = "PIXEL_XY_TOP_LEFT",
                message = "geometry.coordinate_system 은 PIXEL_XY_TOP_LEFT 고정입니다.")
        @JsonProperty("coordinate_system")
        String coordinateSystem,

        @NotNull(message = "geometry.bbox_format 은 필수입니다.")
        @Pattern(regexp = "XYWH", message = "geometry.bbox_format 은 XYWH 고정입니다.")
        @JsonProperty("bbox_format")
        String bboxFormat,

        @NotNull(message = "geometry.bbox 는 필수입니다.")
        @Valid
        @JsonProperty("bbox")
        BoundingBox bbox,

        @NotNull(message = "geometry.segmentation 은 필수입니다.")
        @Valid
        @JsonProperty("segmentation")
        Segmentation segmentation
) {
}
