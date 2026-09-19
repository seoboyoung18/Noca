> **목적** — 백엔드가 AI 서버에 무엇을 보내고 무엇을 받을지 정합니다.
> 

1차 수정본, 오버레이 관련해서 프론트엔드와 협의 후 확정
2차 수정본, AI 서버 구조화 후 항목 반영

| 항목 | 내용 |
| --- | --- |
| 작성일 | 2026-09-10 |
| 수정일 | 2026-09-11 (김경연 — ⚠ 주요 수정 사항 : 오버레이 제거, DDL 대조, 진행 상황 폴링 삭제) |
| 수정일 | 2026-09-12 (김경연 — ⚠ 주요 수정 사항 : 응답 계약 확정, `pairStatus`·`searchability` 반영, 오류·재시도 명세 분리) |
| 관련 이슈 | S15P21A307-236 · -256 · -257 · -258 · -336 |
| 역할 분담 | **AI**: 부위 검출 · 유사 사례 검색 · 견적 산정 / **백엔드**: 수신 · 저장 · 조회 · 리포트 |

---

## 전체 흐름

```
사용자                    백엔드                         AI 서버            S3
  │ ① 사진 업로드          │                              │                 │
  ├────────────────────>│ presigned URL 발급           │                 │
  ├─ ② 브라우저가 직접 PUT ────────────────────────────────────────>│ staging
  ├─ ③ 완료 통보 ──────────>│ 검증 후 service 복사 ────────────────────>│ service
  │                        │ **④ 분석 요청 ─────────────>**│ 이미지 받기 ──>│
  │                        │   analysis_job=PROCESSING     │ 부위 검출      │
  │ ⑤ ~~진행 상황 폴링~~ <─────>│                              │ 사례 검색      │
  │                        │ ⑥ **결과 수신 (JSON)** <───────┤ 견적 산정      │
  │                        │   requestId 중복 검사         │                 │
  │                        │   저장 · analysis_job=COMPLETED               │
  │ ⑦ 견적 화면 <──────────>│                              │                 │
  │   원본 + 좌표로 직접 그림 │                              │                 │
  │ ⑧ 리포트 생성 ─────────>│ LLM 요약 + 체크리스트 → PDF ──────────────>│
  │ ⑨ PDF 다운로드 <───────>│ presigned GET 302             │                 │
```

**AI 연동은 ④와 ⑥ 둘뿐입니다.**

**분석 경로에서 S3에 새로 쓰는 것은 없습니다.** AI 서버는 이미지를 읽기만 하므로 S3 쓰기 권한이 필요 없습니다.

---

## ④ 백엔드 → AI (분석 요청)

```
POST {AI_SERVER_URL}/analyze
Header: X-Internal-Token
```

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

**`vehicle`을 보내는 이유** — 유사 사례 검색 조건입니다. 완화 단계의 입력입니다.

**이미지는 presigned URL** — 버킷이 비공개라 AI 서버가 직접 읽을 수 없습니다. 분석에는 원본(`original`)을 줍니다.

---

## ⑥ AI → 백엔드 (결과)

```
POST /internal/analysis-jobs/{jobId}/result
Content-Type: application/json
Header: X-Internal-Token, X-Request-Id
```

```json
{
  "requestId": "a1b2c3d4e5f6",
  "jobId": 12,
  "modelVersion": "yolo-v8-20260901",
  "pipelineVersionId": 3,

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
      "fallbackStage": "PRICE_TIER",
      "repairMethodReason": {
        "candidates": ["coating", "exchange"],
        "reasonCode": "..."
      }
    }
  ],

  "unresolvedParts": [],

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
          "damageType": "Scratched",
          "pairStatus": "PAIRED",
          "searchability": "STRICT",
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
      ]
    }
  ]
}
```

실패 callback 본문, HTTP 응답 코드, 재시도 및 멱등성 처리 규칙은
[AI 서버 오류·재시도 처리 명세](AI%20서버%20오류·재시도%20처리%20명세.md)를 따릅니다.

### 2026-09-11 수정 ① — 오버레이 이미지를 보내지 않습니다

**AI가 오버레이를 그려 보내면 프론트가 그 이미지밖에 못 씁니다.** 색·두께·선택 강조·부품별 토글을 조절할 수 없고, 원본을 보고 싶어도 이미 그려진 이미지만 있습니다. **좌표를 JSON으로 주고 프론트가 원본 위에 직접 그립니다.**

|  | 전 | 후 |
| --- | --- | --- |
| Content-Type | `multipart/form-data` | **`application/json`** |
| 오버레이 이미지 | `part "overlay"` × N | **없음** |
| `imageResults[].overlayPart` | 있음 | **없음** |
| 좌표 | 안 보냄 | **`imageResults[].detections[].geometry`** |
| 분석 중 S3 쓰기 | 오버레이 업로드 | **없음** |
| 실패 시 보상 삭제 | 필요 | **불필요** |

`analysis_image_result.s3_key_overlay` 컬럼은 **쓰지 않고 NULL로 둡니다.**

**프론트가 그릴 때 필요한 것**

- 좌표는 **원본 이미지 픽셀, 좌상단 원점**입니다 (`coordinateSystem: "PIXEL_XY_TOP_LEFT"`)
- 화면에 `resized`나 `thumbnail`을 띄운다면 **비율로 환산해야 합니다.** 그래서 `imageResults[]`에 원본 `width`·`height`를 함께 보냅니다
- `bbox`는 XYWH 객체, 폴리곤은 `{x, y}` 점 배열입니다. 폴리곤이 여러 개일 수 있습니다
- 견적 항목을 누르면 해당 영역을 강조하는 동작은 **`items[].detectionIds` ↔ `detections[].detectionId`*로 잇습니다

### 2026-09-11 수정 ② — DDL과 대조해 정정

| 항목 | 내용 |
| --- | --- |
| `confidence` **추가** | `damaged_part.confidence`가 `NUMERIC(5,4) NOT NULL`인데 `items[]`에 없어 **저장 자체가 불가능**했습니다. 부품 단위 대표값 하나를 AI가 정해 보냅니다 |
| `detectionIds` **추가** | 초안에는 항목이 어느 사진·어느 영역에서 나왔는지가 없었습니다. 같은 부품이 여러 사진에 찍히면 AI가 병합하므로 AI만 아는 정보입니다 |
| `pipelineVersionId` **추가** | AI가 분석에 쓴 버전 조합 식별자. "이 견적이 어느 버전으로 나왔나"를 되짚는 열쇠입니다. `modelVersion` 문자열 하나로는 부족합니다 |
| `imageResults[].width`·`height` **추가** | 프론트 좌표 환산에 필요 |

### 2026-09-10 수정 — `referencedCaseIds`가 항목 안으로 이동했습니다

처음 초안은 견적 레벨에 둔 것을 **`items[]` 안으로** 옮겼습니다. 화면의 "이 사례들 보기"가 **항목별 근거 안에** 있어, 앞범퍼 사례와 헤드램프 사례를 구분해야 하기 때문입니다.

- 비용 산정에 실제 쓴 `referencedCaseIds`는 항목당 최대 **30건**까지 보낼 수 있습니다. 화면은 이 중 대표 Top-10만 표시할 수 있습니다.
- `refCaseCount` 는 실제 통계 산정에 쓴 전체 건수입니다. 화면 표시 건수와 다를 수 있습니다.
- 저장은 `estimate_item.ref_condition` JSONB 에 합니다(S15P21A307-285). 상한이 없으면 JSONB 가 커집니다.

**조회 API** — `GET /api/estimates/{estimateId}/similar-cases?estimateItemId={id}` (S15P21A307-236). 백엔드가 사례를 다시 검색하지 않고 **AI 가 준 ID 로 조회만** 합니다. 재검색하면 AI 가 본 사례와 달라져 근거가 어긋나기 때문입니다.

---

## 백엔드가 저장하는 곳

| 응답 | 저장 위치 |
| --- | --- |
| `totals` · `confidenceGrade` | `estimate` (`EstimateVersioningService.append()`) |
| `items[]` 비용·공임 | `estimate_item` |
| `items[]` 근거 (`referencedCaseIds` · `costDistribution` · `fallbackStage` · `refCaseCount`) | `estimate_item.ref_condition` JSONB |
| `items[]` 부품·손상·수리방식·`confidence` | `damaged_part` |
| `imageResults[]` 제외 여부 | `analysis_image_result` |
| **`imageResults[].detections[]`** | **`analysis_image_result.detections` JSONB (신규)** |

### `detections`를 담을 컬럼이 필요합니다

좌표는 **(이미지 × 검출)** 단위인데 그 단위의 테이블이 없습니다. `damaged_part`는 `UNIQUE (job_id, part_code)`로 부품당 1행이고 `estimate_item.damaged_part_id`가 NOT NULL FK로 그 단위를 참조하므로 바꿀 수 없습니다.

```sql
ALTER TABLE analysis_image_result
    ADD COLUMN detections JSONB;
```

`analysis_image_result`는 이미 `UNIQUE (job_id, image_id)`라 **"어느 이미지의 좌표인지"가 JSONB 안이 아니라 행으로 해결됩니다.**

**받은 `detections[]`를 키 이름·구조를 바꾸지 말고 그대로 넣습니다.** 프론트가 견적을 다시 열 때 이 값으로 다시 그리므로, 필드를 덜어내면 그리기가 깨집니다.

---

## 값 규칙

| 필드 | 허용 값 |
| --- | --- |
| `damageType` | `Scratched` · `Separated` · `Crushed` · `Breakage` |
| `repairMethod` | `coating` · `sheet_metal` · `exchange` · `repair` |
| `fallbackStage` | `MODEL` · `PRICE_TIER` · `ALL` |
| `confidenceGrade` | `HIGH` · `MEDIUM` · `LOW` · `null` |
| `nonEstimableReason` | `PART_NOT_RESOLVED` · `NO_DAMAGE_DETECTED` · `INSUFFICIENT_CASES` · `null` |
| `exclusionReason` | `NOT_VEHICLE` · `RATIO_BELOW_THRESHOLD` |
| `partCode` | 표준 부품 코드 — 사진에서 나오는 것은 **32종** (`part_code` 마스터는 견적 전용 24종을 포함해 56종) |
| `pairStatus` | `PAIRED` · `UNPAIRED` · `AMBIGUOUS` — 부품·손상 geometry 매칭 상태 |
| `searchability` | `STRICT` · `VECTOR_ONLY` · `EXCLUDED` — 검색 사용 범위. `VECTOR_ONLY`면 `imageResults`의 `partCode`는 null |
| `confidence` | 0~1 |
| 좌표 | 원본 이미지 픽셀 · 좌상단 원점 · bbox는 XYWH |
| 금액 | 원 단위 정수 · **부가세 미포함** |

`pairStatus`와 `searchability`를 함께 보내는 이유는 부품 매칭 결과를 백엔드가
구분해야 하기 때문입니다. `VECTOR_ONLY`는 유사 사례 검색·화면 표시에는 사용하지만
확정 부품이 아니므로 견적 `items[]`에는 포함하지 않습니다.

`estimable: false`면 `totals`·`items`는 비우고 `nonEstimableReason`을 보냅니다. **이때도 `imageResults[].detections[]`는 보냅니다** — 검출은 됐는데 사례가 부족해 산정을 못 한 경우, 화면에 손상 부위는 보여줄 수 있어야 합니다.

`unresolvedParts[]`는 여러 부품 중 비용 산정에 실패한 부품만 담습니다. 하나 이상의 `items[]`가
있으면 부분 견적으로 `estimable: true`를 유지하고, `totals`는 산정 가능한 항목만 합산합니다.
`unresolvedParts[]`가 비어 있지 않으면 프론트는 총액이 부분 금액임을 함께 안내해야 합니다.

### 미검출 결과 구분

| part 검출 | damage 검출 | 실제 상황 | 이미지 결과 및 callback 처리 |
| --- | --- | --- | --- |
| 0개 | 0개 | 차량이 안 찍혔거나 너무 멂 | `excluded: true` · `exclusionReason: NOT_VEHICLE`; 모든 이미지가 이 상태면 백엔드는 `ALL_IMAGES_EXCLUDED`로 재업로드 안내 |
| 1개 이상 | 0개 | 차량은 맞지만 손상이 없음 | `excluded: false` · `detections: []`; 분석 작업은 정상 완료하고 `nonEstimableReason: NO_DAMAGE_DETECTED` |
| 1개 이상 | 1개 이상 | 손상 검출 | `imageResults[].detections[]`를 유지하고 검색·견적을 진행 |

위 분기는 HTTP 오류가 아니다. `NOT_VEHICLE`은 이미지 단위 제외 사유이고,
`NO_DAMAGE_DETECTED`는 차량 유효성 확인 후 손상이 없다는 작업 결과 사유다.

**`fallbackStage`는 차종 → 수리비대 순입니다.** 기준 건수에 못 미치면 차종을 먼저 풀고, 그래도 부족하면 같은 수리비대까지 풉니다. 현재 DEV corpus는 모델 매핑 전이므로 `PRICE_TIER`부터 사용하며, `MODEL`은 모델 매핑 적재 후에만 사용합니다.

> 2026-09-17 변경. 두 번째 단계가 `CAR_CLASS`(차급)에서 `PRICE_TIER`(수리비대)로 바뀌었습니다. 차급은 배기량·크기 기준이라 수리비를 설명하지 못합니다 — 견적서 코퍼스 실측에서 소·중·대의 수리비 지수 중앙값이 1.01 / 0.99 / 1.05로 6% 안에 있었고 범위가 완전히 겹쳤습니다. `PRICE_TIER`는 그 실측 지수의 사분위(`P1`~`P4`)이며, AI 서버가 `vehicle_model.price_tier`에서 직접 읽습니다. **요청 본문은 바뀌지 않습니다** — 백엔드는 지금처럼 `carClass`만 보내면 됩니다. 근거: `Docs/Erd/A307_VEHICLE_AXIS.md`

**`costDistribution`의 의미** — 사례 견적서 행 중 실제 정산에 들어간 금액만 집계합니다. 참고가 행은 제외되므로 화면에 "실제 청구 기준"이라고 쓸 수 있습니다.

**`detections` 개수는 사진에 보이는 손상 개수와 다를 수 있습니다.** 계약을 벗어난 검출은 AI가 걸러내고 보냅니다. 적재 실패가 아닙니다.

---

## 중복 방지 — 가장 중요

세부 규칙은 [AI 서버 오류·재시도 처리 명세](AI%20서버%20오류·재시도%20처리%20명세.md)를
따릅니다. callback 재시도 시 `X-Request-Id`와 본문의 `requestId`는 같은 값을
사용하며, 백엔드는 이미 처리한 요청을 다시 저장하지 않습니다.

## 실패 경로

```
AI 응답 없음       → analysis_job = FAILED, retry_count +1 (최대 3)
같은 requestId 재수신 → 무시하고 200
저장 실패             → 재시도. S3 보상 삭제는 불필요 (분석이 S3에 쓰지 않음)
```

## 범위에서 뺀 것

- **사고 위치 · 사용자 설명** — 샘플 리포트엔 있으나 접수 화면·스키마에 없어 제외
- **부가세** — 제외. 금액은 모두 부가세 미포함
- **손상 심각도 3단계** — 제외. 원천 데이터의 level 이 한 값에 몰려 파생 규칙이 없음(S15P21A307-197)
- **사용자 재분석** — 없음
- **오버레이 이미지** — 제외. 프론트가 좌표로 직접 그림
- **진행 상황 중간 통보** — 없음. AI는 완료 시 한 번만 콜백합니다. `analysis_job`은 `PROCESSING` 하나로만 표시하고, `analysis_stage` 4단계(PREPROCESS·DETECT·MATCH·ESTIMATE)는 채우지 않습니다

---

## 정해야 할 것

| # | 내용 | 소유 |
| --- | --- | --- |
| 1 | **presigned URL 유효시간** — 분석 소요 시간보다 길어야 함. 다른 곳은 10분. -159 성능 측정이 근거 | 백엔드 |
| 2 | **프론트가 어느 이미지 위에 그리나** — `original`이면 환산 불필요, `resized`면 비율 계산 필요. 원본 크기는 응답에 있음 | 프론트 · 백엔드 |
| 3 | **`s3_key_overlay` 컬럼 처리** — NULL로 두고 방치할지 제거할지 | 백엔드 |
| 4 | **`reasonCode` 어휘** — 수리 방식 후보가 둘일 때 무엇으로 고르는지. 아래 참조 | AI |
| 5 | **`confidenceGrade` 산정 기준** — `HIGH`/`MEDIUM`/`LOW`를 무엇으로 가르는지 | AI |

### 4번 — `repairMethod`가 지금 확정되지 않는 이유

손상 유형에서 나오는 작업은 **후보**입니다. 후보가 둘인 손상이 3종이고, 교환과 판금·수리를 가르는 축이 심각도인데 **그 파생 규칙을 2026-09-09에 롤백했습니다**(-197).

| `damageType` | 후보 | 상태 |
| --- | --- | --- |
| `Scratched` | `coating` | **확정 가능** |
| `Separated` | `repair` · `exchange` | 규칙 필요 (-196) |
| `Crushed` | `sheet_metal` · `exchange` | 규칙 필요 (-196) |
| `Breakage` | `repair` · `exchange` | 규칙 필요 (-196) |

`damaged_part.repair_method`가 `NOT NULL`이라 **규칙이 서기 전에는 후보가 둘인 손상을 저장할 수 없습니다.** 규칙 내용과 무관한 스키마 결정이므로 먼저 처리할 수 있습니다 — nullable로 바꾸거나 `UNDETERMINED`를 허용합니다.

`repairMethodReason.candidates`는 규칙이 선 뒤 재판정할 때 근거가 되므로, 확정값을 보내더라도 후보를 함께 남겨 주세요.

---

## DDL 에 자리가 없는 것

| 없는 것 | 필요한 이유 |
| --- | --- |
| `analysis_image_result.detections` JSONB | 프론트가 그릴 좌표를 보관. 없으면 견적을 다시 열 때 다시 그릴 수 없음 |
| `estimate_item.paint_material_cost` | 샘플 리포트가 **"공임 315,000원 + 도장 재료비 185,000원"**으로 나눠 보여줌. `repair_case_item`에는 `paint_material_cost`가 이미 있음 |
| `analysis_job` 버전 컬럼 | `pipelineVersionId`를 저장할 `analysis_job.pipeline_version_id` 컬럼이 없어 migration이 필요함 |

별도 티켓으로 잡아야 합니다.

---

## 백엔드가 이미 만들어 둔 것

| 계약 필드 | 받는 곳 | 지라 상태 |
| --- | --- | --- |
| `totals` · `confidenceGrade` | `EstimateVersioningService.append()` | **완료** (-299) |
| 견적 조회 | `GET /api/estimates/{id}` | **완료** (-300) |
| `items[]` 근거 필드 | `estimate_item.ref_condition` | 진행 중 (-285) |
| `unresolvedParts[]` | `estimate.unresolved_parts` → 견적 조회 `unresolvedParts[]` · 리포트 PDF | 진행 중 (-534) |
| 근거 조회 | `GET /api/estimates/{id}/basis` | 진행 중 (-286) |
| `referencedCaseIds` → 사례 조회 | — | 해야 할 일 (-236) |
| **수신 API** | — | 해야 할 일 (**-157**) |
| 분석 요청 API | — | 해야 할 일 (-156). |
- **158(진행 단계 조회·SSE)은 범위가 줄어듭니다** — AI가 중간 통보를 하지 않으므로 단계별 표시가 불가능합니다. 이슈 내용을 맞춰야 합니다.
