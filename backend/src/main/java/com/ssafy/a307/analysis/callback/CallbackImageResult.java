package com.ssafy.a307.analysis.callback;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 이미지 한 장의 분석 결과. 계약 {@code imageResults[]} 의 원소다.
 *
 * <p><b>{@code estimable: false} 여도 이것은 온다.</b> 검출은 됐는데 사례가 부족해 산정만 못 한
 * 경우, 화면에 손상 부위는 보여 줄 수 있어야 하기 때문이다(계약 "값 규칙").
 *
 * <h2>{@code detections} 를 {@link JsonNode} 로 받는 이유</h2>
 *
 * <p>이 값은 <b>해석하지 않고 그대로 {@code analysis_image_result.detections} JSONB 에 넣는다.</b>
 * 계약이 "받은 detections[] 를 키 이름·구조를 바꾸지 말고 그대로 넣습니다. 프론트가 견적을
 * 다시 열 때 이 값으로 다시 그리므로, 필드를 덜어내면 그리기가 깨집니다" 라고 요구한다.
 *
 * <p>자바 레코드로 낱낱이 매핑하면 <b>매핑하지 않은 필드가 저장에서 조용히 사라진다.</b>
 * 실제로 2차 수정본이 {@code pairStatus}·{@code searchability} 를 더했는데, 그때 여기를 같이
 * 고치지 않았다면 프론트는 이유도 모른 채 그 값을 잃었을 것이다. 구조를 모른 채 보존하는 것이
 * 이 필드의 요구사항이다.
 *
 * <p>Jackson 3({@code tools.jackson})의 {@link JsonNode} 다. 이 프로젝트의 HTTP 컨버터와 주입되는
 * {@code ObjectMapper} 가 Jackson 3 이고, Jackson 2({@code com.fasterxml}) 타입을 쓰면 컨버터가
 * 그 타입을 몰라 역직렬화가 500 으로 죽는다.
 *
 * <p>그 대신 <b>좌표를 쓰는 쪽이 검증한다</b> — 화면이 그릴 때 필요한 것은 원본 크기
 * ({@link #width}·{@link #height})와 좌표계이고, 원본 크기는 여기서 필수로 받는다.
 *
 * @param imageId         {@code accident_image.image_id}
 * @param width           원본 픽셀 너비. 화면이 resized 위에 그릴 때 비율 환산에 쓴다
 * @param excluded        분석에서 제외된 사진인가
 * @param exclusionReason {@code NOT_VEHICLE}·{@code RATIO_BELOW_THRESHOLD}
 * @param detections      검출 배열 원문. 비어 있을 수 있다(손상 없는 정상 사진)
 */
public record CallbackImageResult(

        @NotNull(message = "imageResults[].imageId 는 필수입니다.")
        Long imageId,

        @NotNull(message = "imageResults[].width 는 필수입니다. 프론트 좌표 환산에 쓰입니다.")
        Integer width,

        @NotNull(message = "imageResults[].height 는 필수입니다. 프론트 좌표 환산에 쓰입니다.")
        Integer height,

        boolean excluded,

        String exclusionReason,

        JsonNode detections) {
}
