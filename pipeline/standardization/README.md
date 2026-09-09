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
| `ANCILLARY` | `TOWING` `RESCUE` | 견인·구난 — 수리 작업이 아닌 부대 비용 |
| `STATUS` | `NOT_APPROVED` | 불인정 — 작업 유형이 아니라 손해사정 상태 |

`불인정`은 원천이 `작업` 필드를 덮어쓴 형태라 **원래 작업 유형(판금·도장 등)은 복구할 수 없다.** 행 종류가 아니라 상태로 다루며, `line_type`에 넣으면 부품명·공임 정보를 잃는다.

`탁송`은 `작업` 필드 값이 **아니다.** 원천 1,716,713행(AS 883,756 · SC 832,957)에
`작업=탁송`은 0건이며, `탁송비`는 `작업항목 및 부품명`으로 나타나고 그 행의 `작업`은
견인 또는 구난이다. 따라서 ANCILLARY는 `TOWING`·`RESCUE` 두 코드로만 만들어진다.

`불인정`은 SC 전용이다 — AS 883,756행에 0건, SC 832,957행에 7,568건(0.909%).

## 견적 행 종류 — `line_type` 4종

`ESTIMATE_WORKS.category`와 원천 필드에서 만들어지는 적재 행 종류다. 출처
(`AIHUB_AS` / `AIHUB_SC`)를 몰라도 `line_type`별 집계가 해석되어야 한다.

| `line_type` | 만들어지는 곳 | 정산 | `part_code` |
|---|---|---|---|
| `WORK` | `category=WORK`, 그리고 `category=STATUS`(불인정) | 포함 | 필수 |
| `PART_PRICE` | SC의 `작업`이 빈 부품 명세 행 | 포함 | 필수 |
| `REFERENCE_PRICE` | AS 부품명의 `신품가 <금액>` 표기 | **제외** | 필수 |
| `ANCILLARY` | `category=ANCILLARY` (견인·구난) | 포함 | **없음** |

`ANCILLARY`에 `part_code`가 없는 이유는 원문 부품명이 `견인비`·`구난료`·`탁송비`처럼
부품이 아니어서다. 표본 매핑률은 견인 10.6%, 구난 2.8%, 견인비·구난비 0.0%다.

`ANCILLARY`는 정산에 **포함된다.** 견인·구난 행이 있는 AS 300건에서
`Σ(견인·구난 행 공임 + 부품가격) = 정산.공임.견인구난`이 300/300(100.00%) 일치했다.
따라서 `item_total`에 합산한다 — 정산에서 빠지는 것은 `REFERENCE_PRICE`뿐이다.

### 손해사정 상태 — `assessment_status`

`불인정`을 `line_type`에 넣으면 그 행의 부품명과 손해사정전 금액을 잃는다. 별도
상태 컬럼으로 보존한다.

| 값 | 뜻 |
|---|---|
| `NULL` | 손해사정 개념이 없는 출처(`AIHUB_AS`) |
| `APPROVED` | 손해사정을 거쳐 인정된 행(`AIHUB_SC`) |
| `NOT_APPROVED` | 원천 `작업`이 `불인정`인 행 |

`NOT_APPROVED` 행은 `work_type`·`work_code`가 NULL이다. **적재 누락이 아니라 원천에
정보가 없다** — 원천이 `작업` 필드를 덮어써서 원래 작업 유형을 복구할 수 없다. 원문
`불인정`은 `assessment_status`로 복구된다.

같은 이유로 `NOT_APPROVED` 행의 `부품가격`은 부품비·도장 재료비 어느 쪽으로도 확정할
수 없어 `part_cost`·`paint_material_cost`를 비운다. 원천 금액은
`pre_adjustment_part_cost`·`pre_adjustment_labor_cost`에 그대로 남는다.

따라서 `NOT_APPROVED` 행에서는 `part_cost + paint_material_cost + labor_cost`가
`item_total`과 다르다. **버그가 아니다** — 합계(`부품가격 + 공임`)는 원천에서 확정되지만
그 합계를 부품비·재료비로 나누는 것이 확정되지 않는다. 비용 열 합과 `item_total`을
대조하는 검증은 `assessment_status <> 'NOT_APPROVED'`로 걸러야 한다.

### `부품가격`의 두 가지 뜻 — `part_cost` / `paint_material_cost`

원천 `부품가격`은 `작업=도장`인 행에서 부품비가 아니라 **도장 재료비**이고, 정산상
공임 측 `재료대`에 들어간다. 출처가 아니라 `작업`으로 갈린다 — SC 도장 행도 재료비다.

무작위 3,000건씩 표본에서 `SUM(도장 행 부품가격) = 정산.공임.재료대` 일치율:

| 라우팅 규칙 | AS | SC(손해사정전) | SC(손해사정후) |
|---|---:|---:|---:|
| `작업=도장`만 | **3000/3000 (100.00%)** | 2896/3000 (96.53%) | **2997/3000 (99.90%)** |
| 도장+수리+판금 | 2457/3000 (81.90%) | — | — |
| 전체 행 | 1967/3000 (65.57%) | — | — |

SC의 잔여 불일치 104건은 손해사정에서 재료비가 깎인 행이다(`재료대`는 손해사정 후
금액). AS에서 `부품가격`이 채워진 165,597행 중 135,535행(81.8%)이 `작업=도장`이므로,
분리하지 않으면 `SUM(part_cost)`가 부품 비용으로 읽히지 않는다.

재료비는 `공임소계`에 포함되므로 `item_total`에서는 빼지 않는다.

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

## 검색 메타데이터 계약

`search_metadata_schema.json`은 정규화 출력에서 검색에 사용할 ROI 단위 메타데이터를 만든다. 한 손상 ROI마다 하나의 레코드다.

`part_code`·`damage_type`은 1차 후보 필터의 강한 조건, `car_class`는 약한 조건이다. 심각도와 수리 방식은 검색 축에서 제외한다 — 수리 방식은 설계 2절 전제 5번대로 결과 통계·평가 지표로만 쓴다. `pipeline_version_id`는 필수이며 검색은 같은 값끼리만 비교한다.

`quality_status`는 `GOOD` / `LOW_CONFIDENCE` / `PARTIAL_PART` / `INVALID` 4종이고 사유는 `quality_reasons` 목록에 남긴다. `PARTIAL_PART`는 부품 bbox가 이미지 경계에 닿은 경우(`part_clipped`)이며 규칙 버전은 `PART_CLIP_RULE_VERSION`이다. 좌표 비교만 하고 화면 밖 면적을 추정하지 않으므로 임계값을 정당화할 필요가 없고 표본으로 정오를 셀 수 있다. 대신 가려진 부품은 잡지 못한다. 설계의 `part_visibility_ratio`는 산출법이 정해질 때까지 null로 둔다.

검색 메타데이터에는 임베딩 벡터를 넣지 않는다. ROI 전처리 버전과 모델·정규화 버전만 provenance로 보존하고, 벡터와 모델 버전은 `repair_case_roi_embedding`에서 별도로 관리한다. AI-Hub 원본 `level`은 `aihub_damage_annotation.severity_level`에 참고값으로만 남기고 검색 메타데이터에는 넣지 않는다.
