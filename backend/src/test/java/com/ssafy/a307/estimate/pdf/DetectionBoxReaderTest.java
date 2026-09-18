package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.estimate.pdf.EstimatePdfDocument.Box;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검출 좌표 → 사진 위 사각형 (S15P21A307-547).
 *
 * <p>여기서 틀리면 박스가 엉뚱한 곳을 가리키고, 그것은 "AI 가 저기를 봤다" 는 거짓말이 된다.
 * 박스가 없는 것보다 나쁘므로 <b>모르면 그리지 않는</b> 쪽을 함께 검증한다.
 */
@DisplayName("파손 위치 박스 읽기")
class DetectionBoxReaderTest {

    private static final Map<String, Integer> NUMBERS = Map.of("FRONT_BUMPER", 1, "HEAD_LAMP_L", 2);

    private final DetectionBoxReader reader = new DetectionBoxReader(new ObjectMapper());

    /** 1000×500 사진의 (100,50)에서 400×100 → 왼쪽 10%, 위 10%, 폭 40%, 높이 20%. */
    @Test
    @DisplayName("픽셀 좌표를 사진 크기 대비 퍼센트로 바꾼다")
    void convertsToPercent() {
        List<Box> boxes = reader.read(detections("FRONT_BUMPER", 100, 50, 400, 100),
                (short) 1000, (short) 500, NUMBERS);

        assertThat(boxes).singleElement().satisfies(box -> {
            assertThat(box.left()).isEqualTo(10.0);
            assertThat(box.top()).isEqualTo(10.0);
            assertThat(box.width()).isEqualTo(40.0);
            assertThat(box.height()).isEqualTo(20.0);
            assertThat(box.number()).isEqualTo(1);
        });
    }

    /**
     * 번호는 예상 수리비 표의 순번이다. 표에 없는 부위가 검출됐으면 <b>번호 없이 박스만</b>
     * 그린다 — 표에서 찾을 수 없는 번호를 붙이면 사용자가 그 번호를 찾아 헤맨다.
     */
    @Test
    @DisplayName("표에 없는 부위는 번호 없이 박스만 남는다")
    void leavesNumberBlankWhenNotInTable() {
        List<Box> boxes = reader.read(detections("SIDE_MIRROR_R", 0, 0, 100, 100),
                (short) 1000, (short) 500, NUMBERS);

        assertThat(boxes).singleElement().satisfies(box -> {
            assertThat(box.hasNumber()).isFalse();
            assertThat(box.width()).isEqualTo(10.0);
        });
    }

    /** 치수를 모르면 픽셀을 비율로 바꿀 수 없다. 사진은 그대로 나가고 박스만 빠진다. */
    @Test
    @DisplayName("축소본 치수를 모르면 그리지 않는다")
    void skipsWithoutDimensions() {
        String json = detections("FRONT_BUMPER", 100, 50, 400, 100);

        assertThat(reader.read(json, null, (short) 500, NUMBERS)).isEmpty();
        assertThat(reader.read(json, (short) 1000, null, NUMBERS)).isEmpty();
        assertThat(reader.read(json, (short) 0, (short) 500, NUMBERS)).isEmpty();
    }

    @Test
    @DisplayName("좌표가 없는 검출은 건너뛴다")
    void skipsDetectionWithoutBbox() {
        String json = """
                [{"partCode":"FRONT_BUMPER","geometry":{"segmentation":{"polygons":[]}}},
                 {"partCode":"HEAD_LAMP_L","geometry":{"bbox":{"x":0,"y":0,"width":100,"height":50}}}]
                """;

        assertThat(reader.read(json, (short) 1000, (short) 500, NUMBERS))
                .singleElement()
                .satisfies(box -> assertThat(box.number()).isEqualTo(2));
    }

    /** 사진 밖으로 나간 부분은 잘라 내고, 통째로 밖이면 그리지 않는다. */
    @Test
    @DisplayName("사진 밖 좌표는 잘라 내고 넓이가 없으면 버린다")
    void clampsToPhoto() {
        List<Box> clamped = reader.read(detections("FRONT_BUMPER", 800, 400, 400, 200),
                (short) 1000, (short) 500, NUMBERS);
        assertThat(clamped).singleElement().satisfies(box -> {
            assertThat(box.left()).isEqualTo(80.0);
            assertThat(box.width()).isEqualTo(20.0);   // 100% 에서 멈춘다
        });

        assertThat(reader.read(detections("FRONT_BUMPER", 2000, 2000, 100, 100),
                (short) 1000, (short) 500, NUMBERS)).isEmpty();
    }

    /** 좌표를 못 읽는다고 PDF 를 실패시키지 않는다. 사진은 그대로 나가야 한다. */
    @Test
    @DisplayName("빈 값이나 깨진 JSON 은 빈 목록이다")
    void toleratesBrokenInput() {
        assertThat(reader.read(null, (short) 1000, (short) 500, NUMBERS)).isEmpty();
        assertThat(reader.read("  ", (short) 1000, (short) 500, NUMBERS)).isEmpty();
        assertThat(reader.read("{not json", (short) 1000, (short) 500, NUMBERS)).isEmpty();
        assertThat(reader.read("{\"detections\":[]}", (short) 1000, (short) 500, NUMBERS)).isEmpty();
    }

    private static String detections(String partCode, int x, int y, int width, int height) {
        return """
                [{"detectionId":"1:a","partCode":"%s","damageType":"Scratched",
                  "geometry":{"coordinate_system":"PIXEL_XY_TOP_LEFT","bbox_format":"XYWH",
                              "bbox":{"x":%d,"y":%d,"width":%d,"height":%d}}}]
                """.formatted(partCode, x, y, width, height);
    }
}
