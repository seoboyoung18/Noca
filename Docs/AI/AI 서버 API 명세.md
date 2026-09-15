# AI 서버 API 명세

AI 서버(EC2 2)가 노출하는 엔드포인트입니다.
**백엔드가 호출하는 것은 `/analyze` 하나**이고, 나머지 셋은 같은 내부 함수를 단계별로 열어 둔 개발·검증용입니다.

| 항목 | 내용 |
| --- | --- |
| 작성일 | 2026-09-11 |
| 상태 | 초안 |
| 백엔드 계약 | `Docs/Api/AI 연동 계약 (백엔드 ↔ AI 서버).md` |

```
                ┌→ POST /inference ─┐
공통 내부 함수 ──┼→ POST /search     │  단계별 호출 · 개발용
                └→ POST /estimate ──┘

POST /analyze  →  세 함수를 순서대로 실행  ·  운영에서 쓰는 유일한 엔드포인트
```

**엔드포인트는 함수를 감싸기만 합니다.** 로직은 `services/`에 있고 `/analyze`와 개별 API가 같은 함수를 부릅니다. 두 경로의 결과가 갈라지지 않게 하기 위해서입니다.

```
api/analyze.py     →  run_inference() → search_similar_cases() → calculate_estimate()
api/inference.py   →  run_inference()
api/search.py      →  search_similar_cases()
api/estimate.py    →  calculate_estimate()
```

---

## 공통

| | |
| --- | --- |
| Base URL | `http://{EC2 2 사설IP}:8000` |
| 인증 | `X-Internal-Token` 헤더. 값이 다르면 `401` |
| Content-Type | 요청·응답 모두 `application/json` |
| 인코딩 | UTF-8 |

사설망 안에서만 호출합니다. 외부에 노출하지 않습니다.

### 에러 응답

HTTP 상태 코드와 오류 본문은
[AI 서버 오류·재시도 처리 명세](../Api/AI%20서버%20오류·재시도%20처리%20명세.md)를
따릅니다. `NO_VALID_DETECTION`은 HTTP 오류가 아니며, 검출 결과가 없는 정상 응답으로
처리합니다.

---

## POST /analyze — 운영

백엔드가 부르는 유일한 엔드포인트입니다. **비동기입니다** — 요청을 받으면 바로 `202`를 돌려주고, 분석이 끝나면 `callbackUrl`로 결과를 POST합니다.

동기로 하지 않는 이유는 분석이 수 초~수십 초 걸려서 HTTP 연결을 그동안 잡고 있으면 게이트웨이 타임아웃과 스레드 점유가 생기기 때문입니다. 백엔드는 `analysis_job=PROCESSING`으로 두고 callback 수신 후 완료 또는 실패로 갱신합니다.

### 요청

```json
{
  "jobId": 12,
  "requestId": "a1b2c3d4e5f6",

  "vehicle": {
    "modelId": 41,
    "manufacturer": "현대",
    "modelName": "아반떼",
    "carClass": "Compact",
    "modelYear": 2021
  },

  "images": [
    {
      "imageId": 501,
      "angleCode": "REAR_LEFT",
      "url": "https://a307-service.s3.ap-northeast-2.amazonaws.com/accidents/100/images/501/original.jpg?X-Amz-...",
      "expiresAt": "2026-09-10T16:20:00Z"
    }
  ],

  "callbackUrl": "http://{EC2 사설IP}:8080/internal/analysis-jobs/12/result"
}
```

| 필드 | 필수 | 비고 |
| --- | --- | --- |
| `jobId` | ✓ | 백엔드의 `analysis_job` 식별자 |
| `requestId` | ✓ | 재시도 시 **같은 값**. 콜백에 그대로 실어 보냄 |
| `vehicle` | ✓ | 유사 사례 검색 조건. `modelId`·`carClass`가 완화 단계의 입력 |
| `images[]` | ✓ | 1장 이상. `url`은 원본(`original`)의 presigned GET |
| `callbackUrl` | ✓ | 결과를 POST할 주소 |

### 응답 — 202

```json
{ "accepted": true, "jobId": 12, "requestId": "a1b2c3d4e5f6" }
```

**접수만 확인합니다.** 결과는 여기 없습니다.

### 콜백

분석이 끝나면 AI 서버가 `callbackUrl`로 POST합니다.

```
POST {callbackUrl}
Content-Type: application/json
Header: X-Internal-Token, X-Request-Id
```

본문 형식은 **`Docs/Api/AI 연동 계약 (백엔드 ↔ AI 서버).md` ⑥** 을 따릅니다.
실패 callback·재시도·멱등성은
[AI 서버 오류·재시도 처리 명세](../Api/AI%20서버%20오류·재시도%20처리%20명세.md)를
따릅니다.

---

## POST /inference — 개발용

YOLO 추론과 정규화만 합니다. 검색·산정을 하지 않습니다.

### raw JSON adapter

부품 모델과 손상 모델은 각각 모델 개발자 계약의 raw JSON을 반환합니다.
AI 서버는 두 raw 결과를 직접 `normalize_inference()`에 넣지 않고, 다음 adapter 단계를 거칩니다.

```text
부품 raw JSON (task=detect) ─┐
                             ├→ 같은 image_id의 prediction 매칭
손상 raw JSON (task=segment) ─┘   → xyxy를 XYWH로 변환
                                  → 모델 버전별 class_id·class_name 매핑 검증
                                  → AI-Hub 원천 class_name을 표준 코드로 변환
                                  → PAIRED / UNPAIRED / AMBIGUOUS 판정
                                  → normalize_inference()
```

매칭은 같은 이미지 안에서만 수행합니다. 다른 이미지나 같은 사례의 annotation을
전파하지 않습니다. `PAIRED`일 때만 확정 `partCode`를 만들고, `UNPAIRED`·`AMBIGUOUS`는
`partCode: null`인 `VECTOR_ONLY` 후보로 남깁니다.

매칭 점수는 `intersection(damage_bbox, part_bbox) / area(damage_bbox)`인 damage
coverage를 사용하고, 기본 임계값은 `0.5`입니다. 서로 다른 `partCode` 후보가 둘
이상이면 점수가 가장 높은 후보를 임의 확정하지 않고 `AMBIGUOUS`로 남깁니다.

모델 raw `class_name`은 `Front bumper`, `Scratched`처럼 AI-Hub annotation 원문 표기를
사용합니다. 모델 버전별 `class_id → class_name` 표는 모델 artifact와 함께 관리하며,
adapter가 그 표와 원문 라벨 → 표준 코드 매핑을 모두 검증합니다.

### 요청

```json
{
  "images": [
    { "imageId": 501, "url": "https://...original.jpg?X-Amz-..." }
  ]
}
```

`jobId`·`callbackUrl`은 받지 않습니다. 동기로 결과를 돌려줍니다.

### 응답 — 200

```json
{
      "models": {
    "part":   { "name": "vehicle-part-detection",      "version": "1.0.0", "task": "detect" },
    "damage": { "name": "vehicle-damage-segmentation", "version": "1.0.0", "task": "segment" }
  },
  "normalization": {
    "schemaVersion": "1.2.0",
    "workRuleVersion": "damage-default-v1",
    "pipelineVersionId": 3
  },
  "imageResults": [
    {
          "imageId": 501,
          "width": 1600,
          "height": 1200,
      "excluded": false,
      "exclusionReason": null,
      "detections": [
        {
          "detectionId": "501:damage:damage-001",
          "partCode": "REAR_BUMPER",
          "pairStatus": "PAIRED",
          "searchability": "STRICT",
          "partRawLabel": "Rear bumper",
          "damageType": "Scratched",
          "damageRawLabel": "Scratched",
          "confidence": { "part": 0.9612, "damage": 0.9321 },
          "geometry": {
            "coordinateSystem": "PIXEL_XY_TOP_LEFT",
            "bboxFormat": "XYWH",
            "bbox": { "x": 460, "y": 628, "width": 694, "height": 282 },
            "polygons": [[{ "x": 460, "y": 628 }, { "x": 1154, "y": 628 }, { "x": 1154, "y": 910 }]],
            "areaPx": 195708,
            "areaRatio": 0.10193
          }
        }
      ],
      "normalizationStats": { "detectionCount": 1, "droppedCount": 0, "dropReasons": [] }
    }
  ]
}
```

이 응답의 `imageResults`를 `/search`에 사용합니다. 단, `/search`에는 백엔드에서 받은
`vehicle` 조건도 함께 전달해야 합니다. `/inference` 응답 전체를 그대로 넣는 방식은 아닙니다.

`normalizationStats`는 정규화에서 떨어진 검출 수입니다. 모델이 본 개수와 `detections` 길이가 다른 이유가 여기 있습니다. **콜백 본문에는 들어가지 않습니다** — 백엔드가 쓸 값이 아닙니다.

---

## POST /search — 개발용

검출 결과로 유사 사례를 찾습니다. 산정은 하지 않습니다.

### 요청

```json
{
  "vehicle": { "modelId": 41, "carClass": "Compact", "modelYear": 2021 },
  "images": [
    { "imageId": 501, "url": "https://...original.jpg?X-Amz-..." }
  ],
  "imageResults": [ { "imageId": 501, "detections": ["/inference 응답의 detections 그대로"] } ]
}
```

**`vehicle`이 필요합니다.** 메타데이터 필터의 축이라 검출만으로는 검색이 안 됩니다.
`images[]`는 개발용 단독 `/search` 호출에서 ROI crop과 query embedding을 만들 때
사용합니다. `/analyze` 내부 호출은 이미 내려받은 원본 이미지와 embedding을
재사용해 다시 다운로드하지 않습니다.

`vehicle.modelId`는 요청에 포함하지만, 검색 corpus의 `repair_case.model_id`가
채워진 경우에만 `MODEL` 단계에서 사용합니다. 현재 DEV corpus는 AI-Hub 모델 매핑을
하지 않아 `carClass` 기준인 `CAR_CLASS` 단계부터 검색합니다. 모델 매핑 적재가
완료되기 전에는 `MODEL`이라고 응답하지 않습니다.

### 응답 — 200

`STRICT` 결과는 부품 단위로 묶어 돌려줍니다. 같은 부품이 여러 사진에 잡히면
**여기서 병합됩니다.** `VECTOR_ONLY` 결과는 부품을 확정할 수 없으므로 별도의
`vectorOnly` 배열에 참고 사례로만 반환하며 견적 대상 `parts[]`에는 넣지 않습니다.

```json
{
  "parts": [
    {
      "partCode": "REAR_BUMPER",
      "damageType": "Scratched",
      "confidence": 0.9321,
      "detectionIds": ["501:damage:damage-001", "502:damage:damage-004"],
      "pairStatus": "PAIRED",
      "searchability": "STRICT",
      "fallbackStage": "CAR_CLASS",
      "searchHitCount": 18,
      "referencedCaseIds": [121381, 121414],
      "cases": [
        { "caseId": 121381, "similarity": 0.8412, "repairYear": 2021, "itemTotal": 312000 }
      ]
    }
  ],
  "vectorOnly": [
    {
      "detectionId": "501:damage:d-0002",
      "partCode": null,
      "damageType": "Scratched",
      "pairStatus": "UNPAIRED",
      "searchability": "VECTOR_ONLY",
      "searchHitCount": 7,
      "cases": [
        { "caseId": 121500, "similarity": 0.7931, "repairYear": 2021, "itemTotal": null }
      ]
    }
  ]
}
```

| 필드 | 뜻 |
| --- | --- |
| `detectionIds` | 이 부품을 만든 검출들. 화면에서 항목 ↔ 영역을 잇는 열쇠 |
| `detectionId` | `vectorOnly` 결과의 원본 손상 검출 식별자 |
| `pairStatus` | `PAIRED` · `UNPAIRED` · `AMBIGUOUS` — 부품·손상 geometry 매칭 상태 |
| `searchability` | `STRICT` · `VECTOR_ONLY` · `EXCLUDED` — 검색 사용 범위 |
| `fallbackStage` | `MODEL`(차종 일치) · `CAR_CLASS`(차종 완화) · `ALL`(전체) |
| `searchHitCount` | 벡터 검색 결과에서 확보한 고유 사례 수. 비용 통계 건수가 아니다 |
| `referencedCaseIds` · `cases` | 화면에 보여줄 대표 사례. **항목당 최대 10건** |

`searchHitCount`와 `cases` 길이는 다릅니다. 검색 결과가 18건이어도 화면에는 10건만 보여줍니다.

모든 `detectionId`는 한 분석 작업 안에서 유일해야 합니다. raw 모델의 ID를 그대로
반환하지 않고 `{imageId}:damage:{rawDetectionId}` 형식으로 만든 값을
`imageResults[].detections[]`와 `items[].detectionIds`에서 공통으로 사용합니다.

---

## POST /estimate — 개발용

사례로 비용을 산정합니다.

### 요청

`/search`의 `STRICT` 결과와 원래 `vehicle` 조건을 넣습니다. 비용 산출 모듈은
`repair_cost_stat`을 일괄 조회해 비용 분포를 만들며, `/search`의 화면용 `cases[]`를
다시 집계하지 않습니다.

```json
{
  "vehicle": { "modelId": 41, "carClass": "Compact", "modelYear": 2021 },
  "parts": [ { "partCode": "REAR_BUMPER", "damageType": "Scratched", "...": "/search STRICT 결과" } ]
}
```

`parts[]`에는 `STRICT` 결과만 넣습니다. `vectorOnly[]`는 참고 사례 표시용이며
`partCode`가 없으므로 견적 항목으로 변환하지 않습니다. `parts[]`가 비어 있으면
`estimable: false`, `nonEstimableReason: "PART_NOT_RESOLVED"`로 반환합니다.

`refCaseCount`는 `/search`의 `searchHitCount`가 아니라 선택된 비용 통계의 `case_count`다.
`referencedCaseIds`는 화면에 보여 줄 벡터 검색 대표 사례를 그대로 보존한다.

### 응답 — 200

```json
{
  "estimable": true,
  "nonEstimableReason": null,
  "confidenceGrade": "MEDIUM",
  "totals": { "min": 480000, "median": 550000, "max": 610000 },
  "refCaseTotal": 30,
  "refYearFrom": 2021,
  "refYearTo": 2021,
  "items": [
    {
      "partCode": "REAR_BUMPER",
      "damageType": "Scratched",
      "confidence": 0.9321,
      "repairMethod": "coating",
      "standardHq": 1.8,
      "partCost": null,
      "laborCost": 250000,
      "paintMaterialCost": 85500,
      "itemTotal": 335500,
      "detectionIds": ["501:damage:damage-001", "502:damage:damage-004"],
      "refCaseCount": 18,
      "referencedCaseIds": [121381, 121414],
      "costDistribution": { "p25": 300000, "median": 335500, "p75": 380000 },
      "fallbackStage": "CAR_CLASS",
      "repairMethodReason": { "candidates": ["coating", "exchange"], "reasonCode": "..." }
    }
  ]
}
```

`items[]`는 **콜백 본문의 `items[]`와 같은 형식**입니다. `/analyze`는 여기에 `/inference`의 `imageResults`와 `jobId`·`requestId`를 붙여 콜백을 만듭니다.

사례가 부족하면 `estimable: false`이고 `totals`·`items`를 비웁니다. **이때도 `/analyze`는 `imageResults`를 채워 콜백합니다** — 산정은 못 해도 손상 부위는 화면에 보여줄 수 있어야 합니다.

---

## GET /health

```json
{
  "status": "ok",
  "modelsLoaded": true,
  "embeddingModelLoaded": true,
  "dbReachable": true
}
```

`X-Internal-Token` 없이 호출할 수 있습니다. YOLO와 DINOv2는 지연 로딩하므로,
각 모델을 아직 실제 요청에 사용하지 않았으면 해당 loaded 필드는 `false`일 수 있습니다.
`dbReachable`은 읽기 전용 `SELECT 1` probe 결과입니다. 배포 헬스체크는 `status`를
기본 생존 신호로 사용하고, 의존성 준비 여부는 세 상세 필드를 함께 확인합니다.

---

## 값 규칙

백엔드 계약과 같습니다. 여기서 다시 정의하지 않습니다.

| 필드 | 허용 값 |
| --- | --- |
| `partCode` | 표준 부품 코드 32종 |
| `damageType` | `Scratched` · `Separated` · `Crushed` · `Breakage` |
| `repairMethod` | `coating` · `sheet_metal` · `exchange` · `repair` |
| `fallbackStage` | `MODEL` · `CAR_CLASS` · `ALL` |
| `confidenceGrade` | `HIGH` · `MEDIUM` · `LOW` · `null` |
| `nonEstimableReason` | `PART_NOT_RESOLVED` · `INSUFFICIENT_CASES` · `null` |
| 좌표 | 원본 이미지 픽셀 · 좌상단 원점 · bbox는 XYWH |
| 금액 | 원 단위 정수 · 부가세 미포함 |

`damageType`은 DB 표기(`Scratched`)입니다. 파이프라인 내부 표준 코드는 대문자(`SCRATCHED`)이고, **변환은 응답을 만들 때 한 번만** 합니다.

---

## 타임아웃 · 동시성

| | |
| --- | --- |
| 백엔드 → `/analyze` 연결 | 짧게 잡아도 됩니다. 접수만 하고 `202`를 바로 돌려줍니다 |
| 이미지 다운로드 | presigned URL 만료 전에 끝나야 합니다 |
| 전체 분석 | 유사 사례 검색 응답 2초 이내가 요구사항이지만, 그건 검색 구간 기준입니다 |
| 동시 요청 | GPU 메모리 때문에 무제한으로 받을 수 없습니다. 큐 또는 세마포어 필요 |

---

## 운영 기본값

| # | 내용 |
| --- | --- |
| 1 | 동시성은 `MAX_INFERENCE_CONCURRENCY=1` 세마포어로 제한하고 초과 요청은 `429 BUSY` |
| 2 | callback 재시도·`X-Request-Id` 멱등성은 [오류·재시도 처리 명세](../Api/AI%20서버%20오류·재시도%20처리%20명세.md)를 따른다 |
| 3 | `pipelineVersionId`는 AI 서버가 활성 `feature_pipeline_version`에서 startup 시 조회·검증 |
| 4 | corpus의 모델 매핑 전에는 `CAR_CLASS`부터 검색하고, 기준 건수는 설정값으로 관리 |
| 5 | `confidenceGrade`는 산정 규칙 확정 전까지 `null` 허용. 임의 점수로 만들지 않음 |
| 6 | `/inference`·`/search`·`/estimate`는 개발 프로필·내부망에서만 노출. 운영은 `/analyze`만 노출 |

### 5번 권장안 — 검출 없음과 계약 오류를 분리

- 검출 없음은 정상 결과로 처리한다.
- `/inference`: `200` + `imageResults[].detections=[]`
- `/analyze`: `202` 접수 후 `imageResults[].detections=[]`를 포함한 callback
- `422`는 `INVALID_MODEL_OUTPUT`처럼 모델 출력 구조 자체를 해석할 수 없을 때만 사용한다.
- 사진 다운로드 실패는 `404 IMAGE_FETCH_FAILED`, 모델 실행 실패는 `500 MODEL_ERROR`로 구분한다.

이렇게 해야 손상이 없는 정상 사진과 AI 서버 장애를 백엔드가 구분할 수 있습니다.

### 6번 권장안 — `pipelineVersionId`를 기준으로 하고 기존 `modelVersion`은 유지

현재 백엔드 callback 계약을 깨지 않으려면 필드를 당장 바꾸지 않는 것이 좋습니다.

- `pipelineVersionId`: DB에서 재현성과 검색 호환성을 판단하는 canonical 값
- `modelVersion`: 사람이 읽는 AI 추론 bundle 식별자
- `/inference` 응답의 `models.part`·`models.damage`: 실제 개별 모델명·버전 기록

예시는 다음처럼 둘 수 있습니다.

```json
{
  "modelVersion": "a307-ai-pipeline-20260912-v1",
  "pipelineVersionId": 3
}
```

개별 부품·손상 모델 버전을 callback에도 보존해야 하는 요구가 생기면, 기존
`modelVersion`을 삭제하지 말고 다음 버전 계약에서 선택 필드로 `modelVersions` 객체를
추가하는 방식을 권장합니다.

---

## 함께 보는 문서

| 문서 | 무엇이 있나 |
| --- | --- |
| `Docs/Api/AI 연동 계약 (백엔드 ↔ AI 서버).md` | 콜백 본문 형식, 백엔드 저장 위치, 값 규칙 |
| `Docs/Api/AI 서버 오류·재시도 처리 명세.md` | HTTP 응답 코드, 실패 callback, 재시도·멱등성 |
| `YOLO 출력 형식 (모델 개발자 계약).md` | 모델이 내는 raw 출력 |
| `pipeline/standardization/README.md` | raw 출력을 표준 코드로 바꾸는 어댑터와 표준화 계약 |
