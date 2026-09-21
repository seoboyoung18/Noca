package com.ssafy.a307.estimate.service;

import com.ssafy.a307.estimate.dto.EstimateItemResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse.Box;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 가 보낸 검출 좌표를 사진 위 사각형으로 (S15P21A307-547 · -560).
 *
 * <p><b>리포트 미리보기와 PDF 가 이 한 판독기를 같이 쓴다.</b> 처음엔 PDF 만 박스를 그렸는데,
 * 미리보기가 같은 견적을 다른 모습으로 보여 주면 사용자는 어느 쪽이 맞는지 모른다. 그래서
 * PDF 전용 패키지에서 서비스 계층으로 옮겼다 — 두 곳이 서로의 패키지를 물지 않게.
 *
 * <h2>왜 좌표가 여기 있나</h2>
 *
 * <p>{@code damaged_part} 는 좌표를 버리지만({@link com.ssafy.a307.analysis.entity.DamagedPart}),
 * {@code analysis_image_result.detections} 에는 <b>AI 원문이 그대로</b> 남는다. 계약이 "프론트가
 * 견적을 다시 열 때 이 값으로 다시 그린다" 고 요구했기 때문이다. 화면이 파손 위에 푸른 박스를
 * 그리는 것도 같은 값이고, PDF 도 그것을 쓴다 — 두 문서가 다른 곳을 가리키면 안 된다.
 *
 * <h2>픽셀이 아니라 비율로 돌려준다</h2>
 *
 * <p>좌표는 AI 가 분석한 <b>축소본 픽셀</b>이고 {@code accident_image_asset(RESIZED)} 의 치수와
 * 기준이 같다. PDF 와 화면은 그 사진을 다시 줄여 그리므로 픽셀을 그대로 쓰면 배율이 어긋난다.
 * 사진 크기 대비 퍼센트로 바꿔 두면 얼마로 줄이든 박스가 손상 위에 남는다.
 *
 * <p><b>잘라내기(crop) 보정이 없다.</b> 사진 전체 기준이라, 견적 화면처럼 4:3 틀에
 * {@code object-fit: cover} 로 잘라 넣는 곳에서는 이 값을 그대로 쓸 수 없다. PDF 와 리포트
 * 미리보기는 사진을 통째로 원래 비율대로 싣는다.
 *
 * <h2>모르면 그리지 않는다</h2>
 *
 * <p>치수가 없거나 좌표를 읽지 못하면 <b>빈 목록</b>이다. 사진은 그대로 나가고 박스만 빠진다 —
 * 엉뚱한 자리에 박스를 그리면 "AI 가 저기를 봤다" 는 거짓말이 되고, 그것은 박스가 없는 것보다 나쁘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DetectionBoxReader {

    private final ObjectMapper objectMapper;

    /**
     * 부위 코드 → 예상 수리비 표의 순번 (S15P21A307-547). 박스 번호가 이 값이다.
     *
     * <p>같은 부위가 두 행에 있으면 <b>먼저 나온 행</b>의 번호를 쓴다. 번호는 행마다 하나씩
     * 올라가므로 PDF 템플릿의 {@code stat.count} 와 어긋나지 않는다.
     *
     * <p><b>미리보기와 PDF 가 이 함수 하나로 센다</b>(S15P21A307-560). 따로 세면 한쪽 규칙만
     * 바뀌었을 때 사진의 ① 이 표의 다른 행을 가리킨다.
     */
    public static Map<String, Integer> partNumbers(List<EstimateItemResponse> items) {
        Map<String, Integer> numbers = new HashMap<>();
        int number = 1;
        for (EstimateItemResponse item : items) {
            if (item.partCode() != null) {
                numbers.putIfAbsent(item.partCode(), number);
            }
            number++;
        }
        return numbers;
    }

    /**
     * @param numbers 부위 코드 → 견적 항목 표의 순번. 표에 없는 부위는 번호 없이 박스만 그린다
     * @return 사진 크기 대비 퍼센트 사각형. 그릴 것이 없으면 빈 목록
     */
    public List<Box> read(String detections, Short width, Short height, Map<String, Integer> numbers) {
        if (detections == null || detections.isBlank()
                || width == null || height == null || width <= 0 || height <= 0) {
            return List.of();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(detections);
        } catch (RuntimeException e) {
            // 좌표를 못 읽는다고 PDF 를 실패시키지 않는다. 사진은 그대로 나가야 한다.
            log.warn("검출 좌표를 읽지 못해 박스를 건너뛴다: {}", e.getClass().getSimpleName());
            return List.of();
        }
        if (!root.isArray()) {
            return List.of();
        }

        List<Box> boxes = new ArrayList<>();
        for (JsonNode detection : root) {
            Box box = toBox(detection, width, height, numbers);
            if (box != null) {
                boxes.add(box);
            }
        }
        return List.copyOf(boxes);
    }

    /**
     * 검출 하나를 사각형으로. <b>{@code bbox} 는 XYWH 고정</b>이라 형식을 따지지 않는다 —
     * 계약이 {@code XYWH} 아닌 값을 아예 거절하므로 저장된 것은 전부 XYWH 다
     * ({@code DetectionGeometry}).
     */
    private static Box toBox(JsonNode detection, short width, short height,
                             Map<String, Integer> numbers) {
        JsonNode bbox = detection.path("geometry").path("bbox");
        Double x = number(bbox.path("x"));
        Double y = number(bbox.path("y"));
        Double boxWidth = number(bbox.path("width"));
        Double boxHeight = number(bbox.path("height"));
        if (x == null || y == null || boxWidth == null || boxHeight == null) {
            return null;
        }

        double left = clamp(x / width * 100);
        double top = clamp(y / height * 100);
        double right = clamp((x + boxWidth) / width * 100);
        double bottom = clamp((y + boxHeight) / height * 100);
        if (right - left <= 0 || bottom - top <= 0) {
            return null;   // 사진 밖이거나 넓이가 없는 검출이다
        }

        String partCode = detection.path("partCode").isString()
                ? detection.path("partCode").asString()
                : null;
        return new Box(numbers.get(partCode), round(left), round(top),
                round(right - left), round(bottom - top));
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.doubleValue() : null;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    /** 소수 둘째 자리까지. 인쇄물에서 그 아래는 보이지 않고 style 속성만 길어진다. */
    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
