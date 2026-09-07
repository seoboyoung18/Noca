# 부품명·작업명 공통 스키마

## 결정 사항

- 코드값은 변경하지 않는 영문 대문자 `UPPER_SNAKE_CASE`로 저장한다.
- `raw_label`을 함께 보존해 모델/라벨링 버전 변경을 추적한다.
- 부품은 AI-Hub `damage_part`의 32종을 기준으로 하며 좌·우를 별도 코드로 유지한다.
- 작업은 라벨링 데이터의 4종(`coating`, `repair`, `sheet_metal`, `exchange`)을 기준으로 한다.
- 표준 출력 좌표는 원본 이미지 픽셀, 좌상단 원점, bbox는 `[x,y,width,height]`로 고정한다.
- 세그멘테이션 마스크는 `segmentation.polygons`에 복수 폴리곤으로 저장한다. 서로 떨어진 마스크 조각을 하나로 합치지 않는다.
- 추론 요청 정보에는 실제 작업이 없으므로 `work_candidates`는 손상 기반 후보일 뿐이다. `work_decision=CANDIDATE`로 명시하며 견적 확정 작업으로 사용하지 않는다.
- 알 수 없는 라벨이나 이미지 밖 좌표는 조용히 통과시키지 않고 `NormalizationError`로 격리한다.

## 표준 코드

부품 32종과 영문 원본 라벨·한글 표시명·그룹·방향은 `catalog.py`의 `PARTS`가 단일 기준이다. 손상은 `SCRATCHED`, `SEPARATED`, `CRUSHED`, `BREAKAGE`, 작업은 `COATING`(도장), `REPAIR`(수리), `SHEET_METAL`(판금), `EXCHANGE`(교환)이다.

## 모델 개발자 전달 계약 — raw YOLO 출력

모델 개발자에게 전달한 계약은 노션의 [YOLO 출력 형식 (공유용)](https://app.notion.com/p/3cf576ae278480f28463ef8e183945a3) 페이지를 기준으로 한다. 로컬 검증 스키마는 `raw_yolo_schema.json`이며, 다음 형식을 그대로 받는다.

- 최상위: `model`, `image`, `predictions`
- 이미지 키: `image_id`, `original_width`, `original_height`
- bbox: 원본 이미지 기준 픽셀 `xyxy` 배열
- segmentation: polygon 배열 또는 Detection 모델의 `null`
- 검출 결과가 없으면 `predictions: []`
- 모델명·버전·task·class_id·class_name·confidence를 보존한다.

이 raw 계약은 모델 개발자와의 입력 계약이다. `common_schema.json`은 raw 결과를 부품·손상 코드로 정규화한 **downstream 표준 출력 계약**이며, raw 계약과 동일한 JSON 구조가 아니다.

## 정규화 출력 계약

입력 예시:

```json
{
  "image": {"id": "sample.jpg", "width": 800, "height": 600},
  "coordinate_space": "NORMALIZED_XY",
  "detections": [{
    "part": "Front bumper",
    "damage": "Scratched",
    "bbox": [0.2875, 0.5233, 0.43375, 0.235],
    "polygons": [
      [[0.2875, 0.5233], [0.72125, 0.5233], [0.72125, 0.7583], [0.2875, 0.7583]]
    ],
    "part_confidence": 0.91,
    "damage_confidence": 0.87
  }]
}
```

`coordinate_space`는 `PIXEL_XY` 또는 `NORMALIZED_XY`다. 생략하면 AI-Hub 원천 라벨과 같은 픽셀 좌표로 해석한다. 표준 출력은 항상 픽셀 좌표이며, `geometry.segmentation`에 `polygons`, `area_px`, 이미지 대비 `area_ratio`가 포함된다. 모델이 confidence를 아직 제공하지 않으면 두 값은 `null`이다.

```python
from standardization import normalize_inference, normalize_repair_label

normalized = normalize_inference(yolo_output)
repair = normalize_repair_label("Front bumper:coating,exchange")
```

검증:

```powershell
python -m unittest standardization.test_normalizer -v
```

## 모델 개발자에게 추가로 고정 요청할 항목

raw 계약은 이미 확정되었으므로 bbox 포맷과 좌표계를 추가 협의 항목으로 두지 않는다. `normalize_inference` 내부 입력은 raw 모델 결과를 부품·손상 매칭한 뒤 사용하는 정규화 어댑터 입력이며, 모델 개발자에게 raw 결과 형식을 다시 요구하지 않는다.
