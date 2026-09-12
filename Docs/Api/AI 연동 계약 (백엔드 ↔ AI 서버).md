임시로 이렇게 받을 것 같아서 정리했습니다. 고칠 사항 있으시면 편하게 수정하시고 알려주세요!!

> **목적** — 백엔드가 AI 서버에 무엇을 보내고 무엇을 받을지 정합니다. 리포트 샘플(`bareunmobilereportsample`)에서 필요한 값을 역산해 만들었습니다.
> 

> ⚠ **초안입니다.** AI 팀 확인 후 확정합니다.
> 

| 항목 | 내용 |
| --- | --- |
| 작성일 | 2026-09-10 |
| 관련 이슈 | S15P21A307-236 · -256 · -257 · -258 · -336 |
| 역할 분담 | AI: 부위 검출 · 유사 사례 검색 · 견적 산정 · 오버레이 생성 / 백엔드: 수신 · 저장 · 조회 · 리포트 |

---

## 전체 흐름

```
사용자                    백엔드                         AI 서버            S3
  │ ① 사진 업로드          │                              │                 │
  ├────────────────────>│ presigned URL 발급           │                 │
  ├─ ② 브라우저가 직접 PUT ────────────────────────────────────────>│ staging
  ├─ ③ 완료 통보 ──────────>│ 검증 후 service 복사 ────────────────────>│ service
  │                        │ ④ 분석 요청 ─────────────>│ 이미지 받기 ──>│
  │                        │   analysis_job=PROCESSING     │ 부위 검출      │
  │ ⑤ 진행 상황 폴링 <─────>│                              │ 사례 검색      │
  │                        │                              │ 견적 산정      │
  │                        │ ⑥ 결과 수신 (multipart) <──┤ 오버레이 생성   │
  │                        │   requestId 중복 검사         │                 │
  │                        │   오버레이 업로드 ──────────────────────────>│
  │                        │   저장 · analysis_job=COMPLETED               │
  │ ⑦ 견적 화면 <──────────>│                              │                 │
  │ ⑧ 리포트 생성 ─────────>│ LLM 요약 + 체크리스트 → PDF ──────────────>│
  │ ⑨ PDF 다운로드 <───────>│ presigned GET 302             │                 │
```

**AI 연동은 ④와 ⑥ 둘뿐입니다.**

## ① 백엔드 → AI (분석 요청)

```
POST {AI_SERVER_URL}/analysis
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

**`vehicle` 을 보내는 이유** — 유사 사례 검색 조건입니다. `modelId` → `carClass` → 전체 순으로 넓히는 3단 폴백의 입력입니다.

**이미지는 presigned URL** — 버킷이 비공개라 AI 서버가 직접 읽을 수 없습니다. 분석에는 원본(`original`)을 줍니다.

## ② AI → 백엔드 (결과)

```
POST /internal/analysis-jobs/{jobId}/result
Content-Type: multipart/form-data
Header: X-Internal-Token, X-Request-Id

part "result"    application/json
part "overlay"   image/jpeg × N
```

```json
{
  "requestId": "a1b2c3d4e5f6",
  "jobId": 12,
  "modelVersion": "yolo-v8-20260901",
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
      "repairMethod": "coating",
      "standardHq": 1.8,
      "partCost": null,
      "laborCost": 250000,
      "paintMaterialCost": 85500,
      "itemTotal": 335500,

      "refCaseCount": 18,
      "referencedCaseIds": [121381, 121414],
      "costDistribution": { "p25": 300000, "median": 335500, "p75": 380000 },
      "fallbackStage": "MODEL",
      "repairMethodReason": {
        "candidates": ["coating", "exchange"],
        "reasonCode": "..."
      }
    }
  ],

  "imageResults": [
    { "imageId": 501, "overlayPart": "overlay_501", "excluded": false, "exclusionReason": null }
  ]
}
```

### ⚠ 2026-09-10 수정 — `referencedCaseIds` 가 항목 안으로 이동했습니다

처음 초안은 견적 레벨에 둔 것을 **`items[]` 안으로** 옮겼습니다. 화면의 "이 사례들 보기"가 **항목별 근거 안에** 있어, 앞범퍼 사례와 헤드램프 사례를 구분해야 하기 때문입니다.

- **항목당 최대 10건**만 보내주세요. 화면에 보여줄 대표 사례입니다.
- `refCaseCount` 는 그대로 둡니다 — **통계 산정에 쓴 전체 건수**라 둘은 다릅니다(예: 18건으로 산정했고 그중 10건을 보여줌).
- 저장은 `estimate_item.ref_condition` JSONB 에 합니다(S15P21A307-285). 상한이 없으면 JSONB 가 커집니다.

**조회 API** — `GET /api/estimates/{estimateId}/similar-cases?estimateItemId={id}` (S15P21A307-236). 백엔드가 사례를 다시 검색하지 않고 **AI 가 준 ID 로 조회만** 합니다. 재검색하면 AI 가 본 사례와 달라져 근거가 어긋나기 때문입니다.

**오버레이를 multipart 로 받는 이유** — AI 서버에 S3 쓰기 권한을 주지 않고, key 규칙을 백엔드가 통제하기 위해서입니다. 이전 프로젝트(방방봐)에서 검증된 방식입니다.

백엔드가 저장하는 위치: `accidents/{accidentId}/images/{imageId}/overlay.jpg`

## 값 규칙

| 필드 | 허용 값 |
| --- | --- |
| `damageType` | `Scratched` · `Separated` · `Crushed` · `Breakage` |
| `repairMethod` | `coating` · `sheet_metal` · `exchange` · `repair` |
| `fallbackStage` | `MODEL` · `CAR_CLASS` · `ALL` |
| `confidenceGrade` | `HIGH` · `MEDIUM` · `LOW` |
| `exclusionReason` | `NOT_VEHICLE` · `RATIO_BELOW_THRESHOLD` |
| `partCode` | 표준 부품 코드 (`part_code` 마스터) |
| 금액 | 원 단위 정수 · **부가세 미포함** |

`estimable: false` 면 `totals` · `items` 는 비우고 `nonEstimableReason` 만 보냅니다.

## 중복 방지 — 가장 중요

**재시도할 때 `X-Request-Id` 를 같은 값으로 보내야 합니다.**

다른 값이면 백엔드가 새 요청으로 보고 견적을 하나 더 만듭니다. 사용자는 분석을 한 번 했는데 화면에 견적 버전이 두 개가 됩니다. 같은 `requestId` 가 다시 오면 백엔드는 무시하고 200 을 돌려줍니다.

## 실패 경로

```
AI 응답 없음       → analysis_job = FAILED, retry_count +1 (최대 3)
같은 requestId 재수신 → 무시하고 200
저장 실패          → S3 오버레이 보상 삭제 (고아 파일 방지)
```

## 범위에서 뺀 것

- **사고 위치 · 사용자 설명** — 샘플 리포트엔 있으나 접수 화면·스키마에 없어 제외
- **부가세** — 제외. 금액은 모두 부가세 미포함
- **손상 심각도 3단계** — 제외. 원천 데이터의 level 이 한 값에 몰려 파생 규칙이 없음(S15P21A307-197)
- **사용자 재분석** — 없음. 같은 이미지로 다시 분석하는 경우도 없어 오버레이 key 에 jobId 불필요

## 정해야 할 것

| # | 내용 |
| --- | --- |
| 1 | **presigned URL 유효시간** — 분석 소요 시간보다 길어야 함. 다른 곳은 10분 |
| 2 | **진행 상황을 AI 가 중간 통보하나** — `analysis_stage` 4단계(PREPROCESS·DETECT·MATCH·ESTIMATE)를 채우려면 필요. 안 하면 PROCESSING 하나로만 표시 |
| 3 | **`reasonCode` 어휘** — 수리 방식 후보가 둘일 때 무엇으로 고르는지 |

## DDL 에 자리가 없는 것

`paintMaterialCost`(도장 재료비) 를 담을 컴럼이 `estimate_item` 에 없습니다. 샘플 리포트가 **"공임 315,000원 + 도장 재료비 185,000원"** 으로 나눠 보여주고 있어 필요합니다. `repair_case_item` 에는 `paint_material_cost` 가 이미 있습니다.

별도 티켓으로 잡아야 합니다.

## 백엔드가 이미 만들어 둔 것 (추가로 작업할 것 같습니다.)

이 계약을 받는 구조는 절반 이상 준비돼 있습니다.

| 계약 필드 | 받는 곳 | 상태 |
| --- | --- | --- |
| `totals` · `confidenceGrade` | `EstimateVersioningService.append()` | 구현됨 (S15P21A307-299) |
| `items[]` 근거 필드 | `estimate_item.ref_condition` | 구현됨 (S15P21A307-285) |
| 근거 조회 | `GET /api/estimates/{id}/basis` | 구현됨 (S15P21A307-286) |
| 견적 조회 | `GET /api/estimates/{id}` | 구현됨 (S15P21A307-300) |
| `referencedCaseIds` → 사례 조회 | — | **미구현** (S15P21A307-236) |
| 수신 API | — | **미구현** |
