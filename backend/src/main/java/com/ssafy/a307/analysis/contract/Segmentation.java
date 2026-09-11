package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;

/**
 * 계약의 {@code geometry.segmentation}.
 *
 * <p><b>폴리곤이 여러 개일 수 있다.</b> 손상이 떨어진 조각으로 잡히면 조각마다 하나씩 온다.
 * <b>합치지 않는다</b> — 합치면 실제로 성한 부분까지 손상 영역에 들어간다.
 *
 * <p>각 폴리곤은 <b>점 3개 이상</b>이다. 두 점은 선이지 면이 아니라 넓이를 가질 수 없다.
 */
public record Segmentation(

        @NotNull(message = "segmentation.format 은 필수입니다.")
        @Pattern(regexp = "POLYGONS", message = "segmentation.format 은 POLYGONS 고정입니다.")
        @JsonProperty("format")
        String format,

        @NotEmpty(message = "segmentation.polygons 는 최소 하나여야 합니다.")
        @JsonProperty("polygons")
        List<List<@Valid PolygonPoint>> polygons,

        @NotNull(message = "segmentation.area_px 는 필수입니다.")
        @DecimalMin(value = "0", inclusive = false, message = "segmentation.area_px 는 0보다 커야 합니다.")
        @JsonProperty("area_px")
        BigDecimal areaPx,

        @NotNull(message = "segmentation.area_ratio 는 필수입니다.")
        @DecimalMin(value = "0", inclusive = false, message = "segmentation.area_ratio 는 0보다 커야 합니다.")
        @DecimalMax(value = "1", message = "segmentation.area_ratio 는 1 이하여야 합니다.")
        @JsonProperty("area_ratio")
        BigDecimal areaRatio
) {

    /** 계약의 폴리곤 최소 점 수. 이보다 적으면 면이 아니다. */
    public static final int MIN_POINTS_PER_POLYGON = 3;

    /**
     * 폴리곤마다 점이 3개 이상인가.
     *
     * <p>중첩 제네릭에 {@code @Size} 를 다는 대신 이 방법을 쓴 이유는, 컨테이너 원소 제약이
     * 검증기 설정에 따라 조용히 적용되지 않을 수 있어 <b>계약의 핵심 규칙이 소리 없이
     * 꺼지는 것</b>을 막기 위해서다.
     */
    @JsonIgnore
    @AssertTrue(message = "segmentation.polygons 의 각 폴리곤은 점이 3개 이상이어야 합니다.")
    public boolean isEveryPolygonClosable() {
        if (polygons == null) {
            return true;   // @NotEmpty 가 따로 잡는다
        }
        return polygons.stream()
                .allMatch(polygon -> polygon != null && polygon.size() >= MIN_POINTS_PER_POLYGON);
    }
}
