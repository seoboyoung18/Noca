# 부품명·작업명 공통 스키마

## 결정 사항

- 코드값은 변경하지 않는 영문 대문자 `UPPER_SNAKE_CASE`로 저장한다.
- `raw_label`을 함께 보존해 모델/라벨링 버전 변경을 추적한다.
- 부품은 AI-Hub `damage_part`의 32종을 기준으로 하며 좌·우를 별도 코드로 유지한다.
- **작업 어휘는 두 벌이다.** 사진 손상에서 만드는 작업 후보는 라벨링 데이터의 4종(`coating`, `repair`, `sheet_metal`, `exchange`)이고, 견적서에 실제로 기재된 작업은 `ESTIMATE_WORKS` 13종이다. 두 어휘를 같은 필드나 의미로 섞지 않는다.
- 표준 출력 좌표는 원본 이미지 픽셀, 좌상단 원점, bbox는 `[x,y,width,height]`로 고정한다.
- 세그멘테이션 마스크는 `segmentation.polygons`에 복수 폴리곤으로 저장한다. 서로 떨어진 마스크 조각을 하나로 합치지 않는다.
- 추론 요청 정보에는 실제 작업이 없으므로 `work_candidates`는 손상 기반 후보일 뿐이다. `work_decision=CANDIDATE`로 명시하며 견적 확정 작업으로 사용하지 않는다.
- 알 수 없는 라벨이나 이미지 밖 좌표는 조용히 통과시키지 않고 `NormalizationError`로 격리한다.

## 표준 코드

부품 32종과 영문 원본 라벨·한글 표시명·그룹·방향은 `catalog.py`의 `PARTS`가 단일 기준이다. 손상은 `SCRATCHED`, `SEPARATED`, `CRUSHED`, `BREAKAGE`다.

### 작업 후보 — `WORKS` (사진 기반)

`COATING`(도장), `REPAIR`(수리), `SHEET_METAL`(판금), `EXCHANGE`(교환) 4종. 손상 유형에서 만드는 **후보**이며 확정 작업이 아니다.

### 견적 작업 — `ESTIMATE_WORKS` (견적서 기반)

견적서 `작업` 필드의 원문을 정규화한다. `category`가 행의 성격을 결정한다.

| category | 코드 | 원문 |
|---|---|---|
| `WORK` | `COATING` `REPAIR` `SHEET_METAL` `EXCHANGE` `REMOVE_INSTALL` `OVERHAUL` | 도장·수리·판금·교환·탈착·오버홀 |
| `WORK` | `OVERHAUL_HALF` `OVERHAUL_THIRD` `OVERHAUL_QUARTER` | 1/2OH·1/3OH·1/4OH — 부분 오버홀 |
| `WORK` | `ADJUSTMENT` | 조정 — 휠 얼라인먼트·헤드램프 에이밍 등 공임이 붙는 정비 작업 |
| `ANCILLARY` | `TOWING` `RESCUE` | 견인·구난·탁송 — 수리 작업이 아닌 부대 비용 |
| `STATUS` | `NOT_APPROVED` | 불인정 — 작업 유형이 아니라 손해사정 상태 |

`불인정`은 원천이 `작업` 필드를 덮어쓴 형태라 **원래 작업 유형(판금·도장 등)은 복구할 수 없다.** 행 종류가 아니라 상태로 다루며, `line_type`에 넣으면 부품명·공임 정보를 잃는다.

별칭(`1/2오버홀`, `견인비`, `구난비`)은 `ESTIMATE_WORK_ALIASES`에 둔다. 견적서 표본 6,000건(수리내역 82,803행) 기준으로 이 어휘가 작업이 기재된 행 전부를 덮는다.

```python
from standardization import normalize_estimate_work

normalize_estimate_work("1/2OH")
# {'code': 'OVERHAUL_HALF', 'raw': '1/2OH', 'name': '1/2 오버홀', 'category': 'WORK'}
```

빈 값과 알 수 없는 값은 모두 `NormalizationError`로 격리한다. 빈 값은 작업 행이 아닌 다른 종류의 행(부품가격·참고가)이므로 호출부가 먼저 걸러야 한다.

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
