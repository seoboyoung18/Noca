package com.ssafy.a307.analysis;

import com.ssafy.a307.analysis.contract.AnalysisDocument;
import com.ssafy.a307.analysis.contract.AnalysisDocumentReader;
import com.ssafy.a307.analysis.contract.Detection;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 표준화 계약 {@code inference-standardized-1.1.0} 파싱.
 *
 * <p>픽스처의 출처는 {@code src/test/resources/analysis/README.md} 에 적었다.
 * <b>실제 파이프라인 출력이 아니라 계약 스키마와 {@code catalog.py} 에서 유도한 것</b>이다.
 */
@SpringBootTest
@DisplayName("분석 결과 계약 파싱")
class AnalysisDocumentReaderTest {

    @Autowired
    private AnalysisDocumentReader reader;

    private static String fixture() throws IOException {
        try (InputStream in = AnalysisDocumentReaderTest.class
                .getResourceAsStream("/analysis/sample-analysis-document.json")) {
            assertThat(in).as("픽스처를 찾을 수 없다").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("계약 샘플이 그대로 역직렬화된다")
    void readsFixture() throws IOException {
        AnalysisDocument document = reader.read(fixture());

        assertThat(document.schemaVersion()).isEqualTo(AnalysisDocument.SCHEMA_VERSION);
        assertThat(document.image().width()).isEqualTo(1600);
        assertThat(document.image().height()).isEqualTo(1200);
        assertThat(document.detections()).hasSize(3);

        Detection first = document.detections().getFirst();
        assertThat(first.part().code()).isEqualTo("FRONT_BUMPER");
        assertThat(first.damage().code()).isEqualTo("SCRATCHED");
        assertThat(first.workDecision()).isEqualTo("CANDIDATE");
        assertThat(first.hasSingleWorkCandidate()).isTrue();
    }

    @Test
    @DisplayName("bbox 는 배열이 아니라 객체다 — x·y·width·height 를 이름으로 읽는다")
    void bboxIsAnObject() throws IOException {
        var bbox = reader.read(fixture()).detections().getFirst().geometry().bbox();

        assertThat(bbox.x()).isEqualByComparingTo("120.5");
        assertThat(bbox.y()).isEqualByComparingTo("340.0");
        assertThat(bbox.width()).isEqualByComparingTo("260.0");
        assertThat(bbox.height()).isEqualByComparingTo("90.0");
    }

    @Test
    @DisplayName("폴리곤이 여러 개면 그대로 여러 개다 — 합치지 않는다")
    void keepsPolygonsSeparate() throws IOException {
        var segmentation = reader.read(fixture()).detections().get(1).geometry().segmentation();

        assertThat(segmentation.polygons()).hasSize(2);
        assertThat(segmentation.polygons().get(0)).hasSize(4);
        assertThat(segmentation.polygons().get(1)).hasSize(3);
    }

    @Test
    @DisplayName("detections 가 비어 있는 문서는 정상이다 — 손상이 없는 이미지이지 실패가 아니다")
    void emptyDetectionsAreValid() {
        AnalysisDocument document = reader.read("""
                { "schema_version": "1.1.0",
                  "image": { "id": "img-empty", "width": 800, "height": 600 },
                  "detections": [] }
                """);

        assertThat(document.detections()).isEmpty();
    }

    @Test
    @DisplayName("image.id 는 정수로 와도 읽힌다 — 계약이 string·integer·null 을 모두 허용한다")
    void imageIdAcceptsInteger() {
        AnalysisDocument document = reader.read("""
                { "schema_version": "1.1.0",
                  "image": { "id": 4821, "width": 800, "height": 600 },
                  "detections": [] }
                """);

        assertThat(document.image().id()).isEqualTo("4821");
    }

    @Test
    @DisplayName("image.id 가 null 이어도 읽힌다")
    void imageIdAcceptsNull() {
        AnalysisDocument document = reader.read("""
                { "schema_version": "1.1.0",
                  "image": { "id": null, "width": 800, "height": 600 },
                  "detections": [] }
                """);

        assertThat(document.image().id()).isNull();
    }

    @Nested
    @DisplayName("계약 위반을 거절한다")
    class Rejects {

        @Test
        @DisplayName("계약에 없는 키가 있으면 거절한다 (additionalProperties: false)")
        void unknownProperty() {
            BusinessException e = read("""
                    { "schema_version": "1.1.0",
                      "image": { "id": "x", "width": 800, "height": 600 },
                      "detections": [],
                      "severity": 0.7 }
                    """);

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("schema_version 이 1.1.0 이 아니면 거절한다")
        void wrongSchemaVersion() {
            BusinessException e = read("""
                    { "schema_version": "1.0.0",
                      "image": { "id": "x", "width": 800, "height": 600 },
                      "detections": [] }
                    """);

            assertThat(e.getMessage()).contains("1.1.0");
        }

        @Test
        @DisplayName("image.width 가 0 이면 거절한다")
        void zeroImageWidth() {
            BusinessException e = read("""
                    { "schema_version": "1.1.0",
                      "image": { "id": "x", "width": 0, "height": 600 },
                      "detections": [] }
                    """);

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("폴리곤의 점이 3개 미만이면 거절한다 — 두 점은 면이 아니다")
        void polygonWithTwoPoints() {
            BusinessException e = read(detectionDocument("""
                    "polygons": [[{ "x": 1, "y": 1 }, { "x": 2, "y": 2 }]],
                    "area_px": 10, "area_ratio": 0.1
                    """, """
                    { "part": 0.9, "damage": 0.9 }
                    """));

            assertThat(e.getMessage()).contains("3개 이상");
        }

        @Test
        @DisplayName("bbox.width 가 0 이면 거절한다 — 넓이 0 인 상자는 검출이 아니다")
        void zeroBboxWidth() {
            BusinessException e = read("""
                    { "schema_version": "1.1.0",
                      "image": { "id": "x", "width": 800, "height": 600 },
                      "detections": [{
                        "part": { "code": "BONNET", "name_en": "Bonnet", "name_ko": "보닛",
                                  "group": "BODY_PANEL", "side": "CENTER", "raw_label": "bonnet" },
                        "damage": { "code": "CRUSHED", "name_en": "Crushed", "name_ko": "찌그러짐",
                                    "raw_label": "crushed" },
                        "geometry": {
                          "coordinate_system": "PIXEL_XY_TOP_LEFT", "bbox_format": "XYWH",
                          "bbox": { "x": 1, "y": 1, "width": 0, "height": 5 },
                          "segmentation": { "format": "POLYGONS",
                            "polygons": [[{ "x": 1, "y": 1 }, { "x": 2, "y": 1 }, { "x": 2, "y": 2 }]],
                            "area_px": 10, "area_ratio": 0.1 } },
                        "confidence": { "part": 0.9, "damage": 0.9 },
                        "work_candidates": [{ "code": "EXCHANGE", "name_en": "exchange", "name_ko": "교환" }],
                        "work_decision": "CANDIDATE",
                        "work_rule_version": "1.0.0" }] }
                    """);

            assertThat(e.getMessage()).contains("0보다");
        }

        @Test
        @DisplayName("정의되지 않은 손상 코드는 거절한다")
        void unknownDamageCode() {
            BusinessException e = read("""
                    { "schema_version": "1.1.0",
                      "image": { "id": "x", "width": 800, "height": 600 },
                      "detections": [{
                        "part": { "code": "BONNET", "name_en": "Bonnet", "name_ko": "보닛",
                                  "group": "BODY_PANEL", "side": "CENTER", "raw_label": "bonnet" },
                        "damage": { "code": "DENTED", "name_en": "Dented", "name_ko": "눌림",
                                    "raw_label": "dented" },
                        "geometry": {
                          "coordinate_system": "PIXEL_XY_TOP_LEFT", "bbox_format": "XYWH",
                          "bbox": { "x": 1, "y": 1, "width": 5, "height": 5 },
                          "segmentation": { "format": "POLYGONS",
                            "polygons": [[{ "x": 1, "y": 1 }, { "x": 2, "y": 1 }, { "x": 2, "y": 2 }]],
                            "area_px": 10, "area_ratio": 0.1 } },
                        "confidence": { "part": 0.9, "damage": 0.9 },
                        "work_candidates": [{ "code": "EXCHANGE", "name_en": "exchange", "name_ko": "교환" }],
                        "work_decision": "CANDIDATE",
                        "work_rule_version": "1.0.0" }] }
                    """);

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("confidence 키 자체가 없으면 거절한다 — 값 null 과 키 없음은 다르다")
        void missingConfidenceKey() {
            BusinessException e = read("""
                    { "schema_version": "1.1.0",
                      "image": { "id": "x", "width": 800, "height": 600 },
                      "detections": [{
                        "part": { "code": "BONNET", "name_en": "Bonnet", "name_ko": "보닛",
                                  "group": "BODY_PANEL", "side": "CENTER", "raw_label": "bonnet" },
                        "damage": { "code": "CRUSHED", "name_en": "Crushed", "name_ko": "찌그러짐",
                                    "raw_label": "crushed" },
                        "geometry": {
                          "coordinate_system": "PIXEL_XY_TOP_LEFT", "bbox_format": "XYWH",
                          "bbox": { "x": 1, "y": 1, "width": 5, "height": 5 },
                          "segmentation": { "format": "POLYGONS",
                            "polygons": [[{ "x": 1, "y": 1 }, { "x": 2, "y": 1 }, { "x": 2, "y": 2 }]],
                            "area_px": 10, "area_ratio": 0.1 } },
                        "work_candidates": [{ "code": "EXCHANGE", "name_en": "exchange", "name_ko": "교환" }],
                        "work_decision": "CANDIDATE",
                        "work_rule_version": "1.0.0" }] }
                    """);

            assertThat(e.getMessage()).contains("confidence");
        }

        @Test
        @DisplayName("work_decision 이 CANDIDATE 가 아니면 거절한다")
        void wrongWorkDecision() {
            BusinessException e = read(detectionDocumentWithDecision("CONFIRMED"));

            assertThat(e.getMessage()).contains("CANDIDATE");
        }

        private BusinessException read(String json) {
            return catchThrowableOfType(BusinessException.class, () -> reader.read(json));
        }
    }

    @Test
    @DisplayName("confidence 값이 null 이어도 파싱은 통과한다 — 적재 단계가 판단한다")
    void nullConfidenceParsesButHasNoEffectiveValue() throws IOException {
        Detection third = reader.read(fixture()).detections().get(2);

        assertThat(third.confidence().part()).isNull();
        assertThat(third.confidence().damage()).isNull();
        assertThat(third.confidence().effective()).isEmpty();
    }

    @Test
    @DisplayName("confidence 는 둘 중 작은 값을 쓴다 — 한 쌍의 주장은 약한 쪽을 넘지 못한다")
    void effectiveConfidenceIsTheWeakerSide() throws IOException {
        Detection first = reader.read(fixture()).detections().getFirst();

        assertThat(first.confidence().effective()).contains(new BigDecimal("0.87"));
    }

    private static String detectionDocument(String segmentationBody, String confidenceBody) {
        return """
                { "schema_version": "1.1.0",
                  "image": { "id": "x", "width": 800, "height": 600 },
                  "detections": [{
                    "part": { "code": "BONNET", "name_en": "Bonnet", "name_ko": "보닛",
                              "group": "BODY_PANEL", "side": "CENTER", "raw_label": "bonnet" },
                    "damage": { "code": "CRUSHED", "name_en": "Crushed", "name_ko": "찌그러짐",
                                "raw_label": "crushed" },
                    "geometry": {
                      "coordinate_system": "PIXEL_XY_TOP_LEFT", "bbox_format": "XYWH",
                      "bbox": { "x": 1, "y": 1, "width": 5, "height": 5 },
                      "segmentation": { "format": "POLYGONS", %s } },
                    "confidence": %s,
                    "work_candidates": [{ "code": "EXCHANGE", "name_en": "exchange", "name_ko": "교환" }],
                    "work_decision": "CANDIDATE",
                    "work_rule_version": "1.0.0" }] }
                """.formatted(segmentationBody, confidenceBody);
    }

    private static String detectionDocumentWithDecision(String decision) {
        return """
                { "schema_version": "1.1.0",
                  "image": { "id": "x", "width": 800, "height": 600 },
                  "detections": [{
                    "part": { "code": "BONNET", "name_en": "Bonnet", "name_ko": "보닛",
                              "group": "BODY_PANEL", "side": "CENTER", "raw_label": "bonnet" },
                    "damage": { "code": "CRUSHED", "name_en": "Crushed", "name_ko": "찌그러짐",
                                "raw_label": "crushed" },
                    "geometry": {
                      "coordinate_system": "PIXEL_XY_TOP_LEFT", "bbox_format": "XYWH",
                      "bbox": { "x": 1, "y": 1, "width": 5, "height": 5 },
                      "segmentation": { "format": "POLYGONS",
                        "polygons": [[{ "x": 1, "y": 1 }, { "x": 2, "y": 1 }, { "x": 2, "y": 2 }]],
                        "area_px": 10, "area_ratio": 0.1 } },
                    "confidence": { "part": 0.9, "damage": 0.9 },
                    "work_candidates": [{ "code": "EXCHANGE", "name_en": "exchange", "name_ko": "교환" }],
                    "work_decision": "%s",
                    "work_rule_version": "1.0.0" }] }
                """.formatted(decision);
    }
}
