# 견적서 검증 API — FE 인수인계

> **대상 백로그** `[BE] 검증 요약·등급 기능 구현`(S15P21A307-314) · `[BE] 3단계 등급 판정 설정값 외부화`(315) · `[BE] 총액 차이·확인 필요 항목 수 산출 및 검증 결과 저장·조회 API 구현`(316)
> **서버 상태** **`develop` 에 있다.** `d84c6d5 Feat: 견적 검증 이상 항목 탐지 및 결과 저장 구현 (S15P21A307-310)` 에 함께 머지됨
> **작성 기준** 2026-09-08 작성 (`feature/S15P21A307-314-validation-summary-grade`, develop `2da805b` 머지본) · **2026-09-11 갱신** — `origin/develop` `0ffb7c0` 기준. 아래 ⚠️ 절의 3대 차단 요인이 전부 해소됐다
> **근거** API 명세서 17·19·21·23·25·28행 · `A307_ddl_final.sql` · 와이어프레임 `정비소 견적서 검증 결과.png` · 코드 직접 확인
>
> 정비소 확인 질문(`GET .../questions`)의 상세는 **별도 문서 작업(317·318)** 이 다룬다. 이 문서는 엔드포인트 목록에만 올린다.

---

## ⚠️ 시작하기 전에 — 막혀 있던 셋이 **전부 풀렸다** (2026-09-11)

초판(2026-09-08)에는 세 가지가 막혀 있다고 적혀 있었다. **지금은 셋 다 코드가 들어왔고, 남은 것은 배포 환경변수뿐이다.**

| # | 막혔던 것 | 지금 |
|---|---|:---|
| **1** | `CurrentMemberProvider` 가 스텁이라 **로그인해도 모든 검증 API 가 500** | ✅ **해소.** `S15P21A307-422` 가 교체해 세션의 `UserPrincipal` 에서 `memberId` 를 꺼낸다 |
| 2 | 파일 저장소 어댑터가 없어 업로드·PDF·삭제가 **503** | ✅ **어댑터 있음.** 단 **환경변수를 넣어야 켜진다** — 아래 |
| 3 | OCR·LLM·PDF 워커가 없어 `QUEUED` 에서 멈춤 | ✅ **워커 있음.** 단 **기본값이 꺼짐** — 아래 |

**API 계약은 하나도 바뀌지 않았다.** 이 문서의 응답 예시가 그대로 맞다.

### 아직 503 이거나 `QUEUED` 에서 멈춘다면 — 버그가 아니라 설정이다

| 기능 | 켜는 값 | 기본값 |
|---|---|---|
| 견적서 파일 업로드·삭제 | `DOCUMENT_STORAGE_PROVIDER=s3` · `STORAGE_SERVICE_BUCKET` | 둘 다 빈 값 → **503** |
| 견적서 판독(OCR) | `ESTIMATE_OCR_PROVIDER` · `ESTIMATE_WORKER_ENABLED=true` · `GMS_KEY` | 꺼짐 → `QUEUED` 유지 |
| 검증 결과 PDF | `VALIDATION_PDF_ENABLED=true` + 위 저장소 설정 | `false` → `GET /pdf` 가 **409** |

> ⚠️ `GMS_KEY` **는 개인정보 승인 전까지 채우면 안 된다.** 이 경로는 견적서 **원본 이미지·PDF 자체**를 외부 LLM 으로 보낸다.

**항목 직접 입력(JSON) 경로는 위 설정 없이도 끝까지 동작한다.** 파일 업로드만 저장소가 필요하다.

로그인하지 않은 요청은 **401**(본문 없음)이다. 500 이 아니다.

**그래도 화면은 지금 만들 수 있다.** 응답 JSON·필드 출처·판정 규칙·오류 코드가 전부 코드에서 확정돼 있다. 아래는 추측이 아니라 DTO·서비스·DDL 에서 읽은 것이다. **실호출 검증만 1번 이후로 미루면 된다.**

---

## 0. 먼저 알아야 할 것 네 가지

### 0-1. 직접 입력은 **폴링이 필요 없다** — 202 인데 이미 `COMPLETED` 다

```
POST /api/estimate-validations   (Content-Type: application/json)
→ HTTP 202 Accepted
  { "data": { "validationId": 1, "status": "COMPLETED", "inputType": "MANUAL", "statusUrl": "/api/estimate-validations/1" } }
```

**202 는 "접수했다"는 뜻이지 "아직 처리 중"이라는 뜻이 아니다.** 직접 입력은 같은 트랜잭션 안에서 판정까지 끝내고 `COMPLETED` 로 저장한 뒤 응답한다. 엔티티가 잠깐 `PROCESSING` 을 거치지만 커밋 시점엔 이미 `COMPLETED` 라 **FE 가 `PROCESSING` 을 볼 일이 없다.**

→ **직접 입력이면 202 를 받은 즉시 `GET /{id}/result` 를 호출해도 된다.** 응답의 `status` 로 분기할 것. 폴링 루프를 돌리면 첫 시도에서 끝나므로 낭비다.

### 0-2. 파일 입력은 `QUEUED` 에서 **멈춘다** (지금은 503 이라 거기까지 가지도 못한다)

파일 경로는 저장소에 넣고 `QUEUED` 로 접수만 한다. **그 큐를 소비하는 워커가 없다.** 저장소 어댑터가 붙어도 OCR 워커가 없으면 `QUEUED` 그대로다.

→ **폴링에 무한 대기를 두지 말 것.** 타임아웃과 "처리가 지연되고 있습니다" 안내가 필요하다.

### 0-3. `FAILED` 는 **지금 절대 나오지 않는다**

`EstimateValidation.fail(...)` 을 호출하는 프로덕션 코드가 **한 곳도 없다.** DDL·enum·`failureReason` 필드가 전부 `FAILED` 를 전제하는데 현재 코드가 그 상태를 만들지 않는다.

→ `FAILED` 분기는 **만들어 두되 테스트할 방법이 없다**는 것을 알고 있을 것. `failureReason` 도 항상 `null` 이다.

### 0-4. 총액 차이는 **저장된 값이 아니라 조회 시점 계산값**이다

`estimate_validation` 테이블에 `total_diff` 류 컬럼이 **없다.** `claimed_total`·`review_item_count`·`total_item_count` 만 저장하고, AI 견적과의 차이는 `GET /{id}/result` 를 부를 때마다 계산해서 응답에만 담는다.

**누락이 아니라 의도된 설계다.** AI 예상 견적이 재산정되면 차이도 따라 바뀌어야 하는데, 저장하면 낡은 값이 남는다.

→ **FE 가 차이값을 캐시해 두고 재사용하지 말 것.** 결과를 다시 조회하면 값이 달라질 수 있다.

---

## 1. API 8종

| # | 메서드 | 경로 | 인증 | 성공 | 명세서 | 지금 되는가 |
|---|---|---|:---:|---|:---:|---|
| 1 | POST | `/api/estimate-validations` (JSON) | 필요 | **202** | 17행 | ⚠️ 500 (★) |
| 2 | POST | `/api/estimate-validations` (multipart) | 필요 | 202 | 17행 | ⚠️ 500 → 503 |
| 3 | GET | `/api/estimate-validations/{validationId}` | 필요 | 200 | 19행 | ⚠️ 500 (★) |
| 4 | GET | `/api/estimate-validations/{validationId}/result` | 필요 | 200 | 25행 | ⚠️ 500 (★) |
| 5 | GET | `/api/estimate-validations/{validationId}/questions` | 필요 | 200 | (행 추가함) | ⚠️ 500 (★) |
| 6 | GET | `/api/estimate-validations/me` | 필요 | 200 | 28행 | ⚠️ 500 (★) |
| 7 | GET | `/api/estimate-validations/{validationId}/pdf` | 필요 | **302** | 23행 | ⚠️ 500 → 409 |
| 8 | DELETE | `/api/estimate-validations/{validationId}` | 필요 | **204 · 본문 없음** | 21행 | ⚠️ 500 → 503 |

★ = `CurrentMemberProvider` 스텁 때문. 교체되면 표의 "성공" 열대로 동작한다.

**연관 API 2종** — 컨트롤러는 다르지만 같은 화면 흐름이다.

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| PUT | `/api/accidents/{accidentId}/actual-cost` | 200 | 실제 수리비·완료일·정비소명 기록 |
| GET | `/api/accidents/{accidentId}/cost-comparison` | 200 | AI 견적 · 정비소 견적들 · 실제 수리비 비교 |

> **`PUT .../actual-cost` 는 `/api/estimate-validations` 아래가 아니라 `/api/accidents` 아래다.** 실제 수리비는 사고 건에 붙는 값이지 개별 검증 건에 붙는 값이 아니다. `AccidentController` 소속이다.

`/api/estimate-validations/**` 는 `PUBLIC_PATHS` 에 없다. 전부 `ROLE_USER` 또는 `ROLE_ADMIN` 이 필요하다.

---

### 1-1. POST `/api/estimate-validations` (JSON) — 직접 입력

`Content-Type: application/json` 이면 직접 입력, `multipart/form-data` 면 파일 입력이다. **같은 경로를 Content-Type 으로 가른다.**

**요청**

```json
{
  "accidentId": 1,
  "estimateId": 3,
  "fileType": "MANUAL",
  "claimedTotal": 622000,
  "items": [
    { "lineNo": 1, "rawItemName": "앞 범퍼 교환",     "workType": "교환", "quantity": 1, "partCost": 240000, "laborCost": 30000 },
    { "lineNo": 2, "rawItemName": "프론트 펜더 판금", "workType": "판금", "quantity": 1, "partCost": 0,      "laborCost": 182000 }
  ]
}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| `accidentId` | number | ✅ | 양수. 로그인 회원 소유의 사고 건 |
| `estimateId` | number | ❌ | 양수. **생략 가능** — 없으면 AI 견적과 총액을 비교하지 않는다(2-2 경고) |
| `fileType` | string | ✅ | **반드시 `"MANUAL"`** — 다른 값이면 400 |
| `claimedTotal` | number | ❌ | 양수. 보내면 **항목 소계 합계와 정확히 같아야 한다.** 다르면 400 |
| `items` | array | ✅ | 1개 이상 **200개 이하** |
| `items[].lineNo` | number | ✅ | 1 ~ 32767. **견적서 안에서 중복 금지** |
| `items[].rawItemName` | string | ✅ | 공백 아님, 200자 이하 |
| `items[].workType` | string | ✅ | 아래 표의 값 중 하나 |
| `items[].quantity` | number | ✅ | 1 ~ 32767 |
| `items[].partCost` | number | ✅ | 0 이상 |
| `items[].laborCost` | number | ✅ | 0 이상. **부품비·공임 중 하나는 0보다 커야 한다** |

**`workType` 에 보낼 수 있는 값** — 앞뒤 공백을 없애고 소문자로 바꾼 뒤 비교한다. 표에 없는 값은 400 이다.

| 보낼 값 | 서버 enum | 화면 표기 | 사례 통계와 비교되는가 |
|---|---|---|:---:|
| `교환` · `exchange` | `REPLACEMENT` | 교환 | ✅ |
| `판금` · `sheet_metal` | `SHEET_METAL` | 판금 | ✅ |
| `도장` · `coating` | `PAINTING` | 도장 | ✅ |
| `수리` · `repair` | `REPAIR` | 수리 | ✅ |
| `탈착` | `DETACHMENT` | 탈착 | ❌ **표준 수리방식이 없어 비교 불가** |
| `오버홀` | `OVERHAUL` | 오버홀 | ❌ **같음** |

> ⚠️ **`탈착`·`오버홀` 은 항상 `INSUFFICIENT_REFERENCE` 플래그가 붙는다.** 대응하는 표준 수리방식(`StandardRepairMethod`)이 없어 `repair_cost_stat` 을 찾을 수 없기 때문이다. **비교 자료 부족이지 이상 항목이 아니다** — 화면 문구를 그렇게 구분할 것.
> ⚠️ **한글 값을 권한다.** 영문 별칭은 `교환`·`판금`·`도장`·`수리` 4개에만 있고 `탈착`·`오버홀` 에는 없다.

**202 Accepted**

```json
{
  "data": {
    "validationId": 1,
    "status": "COMPLETED",
    "inputType": "MANUAL",
    "statusUrl": "/api/estimate-validations/1"
  }
}
```

`statusUrl` 은 서버가 만들어 주는 편의 필드다. FE 가 경로를 조립하지 않아도 된다.

**에러**

| 상황 | 상태 | `error.code` | `error.message` |
|---|:---:|---|---|
| `fileType` 이 `MANUAL` 이 아님 | 400 | `INVALID_REQUEST` | `fileType은 MANUAL이어야 합니다.` |
| `claimedTotal` ≠ 소계 합계 | 400 | `INVALID_REQUEST` | `claimedTotal이 항목 소계 합계와 일치하지 않습니다.` |
| `lineNo` 중복 | 400 | `INVALID_REQUEST` | `lineNo는 견적서 안에서 중복될 수 없습니다.` |
| 부품비·공임이 둘 다 0 | 400 | `INVALID_REQUEST` | `각 항목의 부품비와 공임 중 하나는 0보다 커야 합니다.` |
| 모르는 `workType` | 400 | `INVALID_REQUEST` | `지원하지 않는 작업유형입니다: {값}` |
| 금액이 허용 범위를 넘음 | 400 | `INVALID_REQUEST` | `견적 항목 값이 허용 범위를 벗어났습니다.` / `견적 합계가 허용 범위를 벗어났습니다.` |
| 없는 사고 · **남의 사고** | 404 | `NOT_FOUND` | `사고 건을 찾을 수 없습니다.` |
| 없는 `estimateId` · **다른 사고의 견적** | 404 | `NOT_FOUND` | `AI 예상 견적을 찾을 수 없습니다.` |
| 미인증 | 401 | — | **봉투가 다르다 → 4-3** |
| 가입 미완료 세션 | 403 | `SIGNUP_REQUIRED` | `약관 동의 후 가입을 완료해 주세요.` → 4-4 |

> ⚠️ **`error.message` 를 파싱하지 말 것.** Bean Validation 오류가 여러 개면 `, ` 로 이어 붙고 순서도 보장되지 않는다. **분기는 `error.code`, 표시는 `error.message` 를 그대로.**

---

### 1-2. POST `/api/estimate-validations` (multipart) — 파일 입력

```
Content-Type: multipart/form-data

metadata  (application/json)  { "accidentId": 1, "estimateId": 3 }
file      (binary)            견적서 이미지 또는 PDF
```

`metadata` 파트는 `accidentId`(필수·양수)와 `estimateId`(선택·양수) **두 개뿐**이다. `fileType` 은 보내지 않는다 — 서버가 파일 시그니처로 판별한다.

**파일 제약** — 아래를 **함께** 검사한다. 하나라도 어긋나면 400 이다.

| 검사 | 규칙 |
|---|---|
| 크기 | **10MB 이하**, 0바이트 금지 |
| 파일명 | 1~255자, `/` `\` 및 제어문자 금지, 확장자 필수 |
| 확장자 | `jpg` · `jpeg` · `png` · `pdf` (소문자 변환 후 비교) |
| Content-Type | 확장자와 **일치해야** 한다 — `image/jpeg` · `image/png` · `application/pdf` |
| 매직 시그니처 | 실제 바이트가 확장자와 **일치해야** 한다 |

| 상황 | 상태 | `error.code` | `error.message` |
|---|:---:|---|---|
| 파일 없음·0바이트 | 400 | `INVALID_REQUEST` | `견적서 파일이 필요합니다.` |
| 10MB 초과 | 400 | `INVALID_REQUEST` | `견적서 파일은 10MB 이하여야 합니다.` |
| 파일명이 부적절 | 400 | `INVALID_REQUEST` | `원본 파일명이 올바르지 않습니다.` |
| 확장자 없음 | 400 | `INVALID_REQUEST` | `파일 확장자가 필요합니다.` |
| 지원하지 않는 확장자 | 400 | `INVALID_REQUEST` | `JPG, PNG, PDF 파일만 등록할 수 있습니다.` |
| Content-Type 불일치 | 400 | `INVALID_REQUEST` | `파일 Content-Type과 확장자가 일치하지 않습니다.` |
| 시그니처 불일치 | 400 | `INVALID_REQUEST` | `파일 시그니처와 확장자가 일치하지 않습니다.` / `지원하는 파일 시그니처가 아닙니다.` |
| **저장소 미구성** | **503** | `SERVICE_UNAVAILABLE` | `문서 저장소 공급자가 구성되지 않았습니다. 직접 입력을 이용해 주세요.` |

> ⚠️ **HEIC 은 지원하지 않는다.** 확장자 목록에 없으므로 400 `JPG, PNG, PDF 파일만 등록할 수 있습니다.` 가 난다.
> **503 의 `message` 가 곧 사용자 안내다** — "직접 입력을 이용해 주세요"를 그대로 노출하면 된다.

성공 시 응답은 1-1 과 같은 모양이되 `status` 가 **`QUEUED`**, `inputType` 이 `IMAGE` 또는 `PDF` 다.

---

### 1-3. GET `/api/estimate-validations/{validationId}` — 상태 조회 (폴링용)

**200**

```json
{
  "data": {
    "validationId": 1,
    "inputType": "MANUAL",
    "status": "COMPLETED",
    "failureReason": null,
    "createdAt": "2026-09-08T02:11:30Z",
    "completedAt": "2026-09-08T02:11:30Z"
  }
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `inputType` | string | `MANUAL` · `IMAGE` · `PDF` |
| `status` | string | `QUEUED` · `PROCESSING` · `COMPLETED` · `FAILED` |
| `failureReason` | string \| null | **현재 항상 `null`** → 0-3 |
| `completedAt` | string \| null | `COMPLETED`·`FAILED` 에서만 채워진다 (`ck_ev_done` 제약) |

**가벼운 API 다.** 항목·질문을 읽지 않는 프로젝션 조회라 폴링에 써도 된다.

**에러** — 없는 검증·**남의 검증** 모두 404 `NOT_FOUND` `견적서 검증을 찾을 수 없습니다.` (403 이 아니다 → 4-2.)

---

### 1-4. GET `/api/estimate-validations/{validationId}/result` — ★ 결과 조회 (화면의 본체)

**`COMPLETED` 가 아니면 409 다.** 200 에 빈 결과가 오지 않는다.

**200**

```json
{
  "data": {
    "validationId": 1,
    "accidentId": 1,
    "estimateId": 3,
    "status": "COMPLETED",
    "grade": "CAUTION",
    "gradeDisplayName": "주의",
    "summary": "주의: 총 4개 항목 중 2개 항목의 확인을 권장합니다. 정비소 견적은 AI 중앙값보다 62,000원 차이납니다.",
    "claimedTotal": 622000,
    "aiTotalMin": 480000,
    "aiTotalMedian": 560000,
    "aiTotalMax": 610000,
    "differenceFromMedian": 62000,
    "differenceFromRangeMax": 12000,
    "reviewItemCount": 2,
    "totalItemCount": 4,
    "items": [ /* 1-4-2 */ ],
    "questions": [ /* 별도 문서 */ ],
    "actualRepairCost": null,
    "actualRepairCompletedDate": null,
    "repairShopName": null,
    "shopEstimateDifferenceFromActual": null,
    "aiMedianDifferenceFromActual": null,
    "actualWithinAiRange": null,
    "legalNotice": "이 결과는 AI와 사례 통계를 이용한 참고용 추정치이며 실제 수리비와 다를 수 있고 특정 사업자를 평가하지 않습니다.",
    "createdAt": "2026-09-08T02:11:30Z",
    "completedAt": "2026-09-08T02:11:30Z"
  }
}
```

#### 1-4-1. 필드 전체와 출처

**출처가 중요하다.** "저장" 은 DB 컬럼을 그대로 읽은 값, "계산" 은 조회할 때마다 다시 구하는 값, "상수" 는 코드에 박힌 값이다.

| 필드 | 타입 | 출처 | 비고 |
|---|---|---|---|
| `validationId` | number | 저장 `estimate_validation.validation_id` | |
| `accidentId` | number | 저장 (연관 `accident`) | |
| `estimateId` | number \| null | 저장 `estimate_id` | **null 가능** — AI 견적을 연결하지 않은 검증 |
| `status` | string | 저장 `status` | 여기서는 항상 `COMPLETED` |
| `grade` | string | 저장 `llm_grade` | `APPROPRIATE`·`CAUTION`·`NEEDS_REVIEW` |
| `gradeDisplayName` | string | **enum 상수** | `적정 범위`·`주의`·`확인 필요` |
| `summary` | string | 저장 `llm_summary` | 판정 시점에 만들어 저장한 문장 → 7-2 |
| `claimedTotal` | number \| null | 저장 `claimed_total` | 직접 입력은 **항목 소계 합계로 서버가 계산해 저장**한다 |
| `aiTotalMin` | number \| null | **조회 시 estimate 조인** | `estimateId` 가 null 이면 null |
| `aiTotalMedian` | number \| null | 같음 | |
| `aiTotalMax` | number \| null | 같음 | |
| `differenceFromMedian` | number \| null | **계산** `claimedTotal − aiTotalMedian` | 어느 한쪽이 null 이면 null |
| `differenceFromRangeMax` | number \| null | **계산** `claimedTotal − aiTotalMax` | 같음 |
| `reviewItemCount` | number | 저장 `review_item_count` | 확인 권장 항목 수 |
| `totalItemCount` | number | 저장 `total_item_count` | 전체 항목 수 |
| `items` | array | 저장 `estimate_validation_item` | `line_no` 오름차순 |
| `questions` | array | 저장 `estimate_validation_question` | `display_order` 오름차순. 상세는 별도 문서 |
| `actualRepairCost` | number \| null | 저장 `accident.actual_repair_cost` | **`PUT /api/accidents/{id}/actual-cost` 전에는 null** |
| `actualRepairCompletedDate` | string(date) \| null | 저장 `accident` | 같음 |
| `repairShopName` | string \| null | 저장 `accident` | 같음 |
| `shopEstimateDifferenceFromActual` | number \| null | **계산** `claimedTotal − actualRepairCost` | 같음 |
| `aiMedianDifferenceFromActual` | number \| null | **계산** `aiTotalMedian − actualRepairCost` | 같음 |
| `actualWithinAiRange` | boolean \| null | **계산** `aiTotalMin ≤ actual ≤ aiTotalMax` | 같음 |
| `legalNotice` | string | **상수** | → 1-4-4 |
| `createdAt` / `completedAt` | string(ISO instant) | 저장 | |

**실제 수리비를 입력하기 전에는 `null` 인 필드 6개** — `actualRepairCost` · `actualRepairCompletedDate` · `repairShopName` · `shopEstimateDifferenceFromActual` · `aiMedianDifferenceFromActual` · `actualWithinAiRange`. 이 블록은 통째로 숨기거나 "실제 수리비를 입력하면 비교해 드립니다" 안내로 대체하는 편이 좋다.

> **`reviewItemCount`·`totalItemCount` 는 저장값인데 총액 차이는 계산값이다 — 이 비대칭은 의도적이다.**
> 항목 수는 판정 시점의 사실이라 나중에 바뀌면 안 되고, 총액 차이는 AI 견적이 재산정되면 함께 바뀌어야 한다. 그래서 한쪽만 컬럼으로 굳혔다.

#### 1-4-2. `items[]` — 항목별 비교표

```json
{
  "lineNo": 2,
  "rawItemName": "프론트 펜더 판금",
  "normalizedName": "프론트 펜더",
  "partCode": "FRT_FENDER_LH",
  "workType": "SHEET_METAL",
  "quantity": 1,
  "partCost": 0,
  "laborCost": 182000,
  "subtotal": 182000,
  "referenceMin": 90000,
  "referenceMedian": 110000,
  "referenceP75": 125000,
  "referenceMax": 130000,
  "referenceCaseCount": 37,
  "flag": "OVER_P75",
  "reason": "소계 182,000원이 유사 사례 37건의 75백분위 125,000원을 초과합니다.",
  "displayDecision": "확인 권장"
}
```

| 필드 | 설명 |
|---|---|
| `rawItemName` | 사용자가 입력한 원문 그대로 |
| `normalizedName` | 표준 부품명. **매핑 실패 시 null** |
| `partCode` | 표준 부품 코드. **매핑 실패 시 null** |
| `workType` | 서버 enum 값(`REPLACEMENT`·`SHEET_METAL`·…) — 한글이 아니다. 표시는 FE 가 3-1 표로 변환 |
| `subtotal` | `(partCost + laborCost) × quantity` 를 서버가 계산해 저장 |
| `referenceMin` ~ `referenceMax` | 유사 사례 통계 스냅샷. **비교 자료가 없으면 5개 전부 null** |
| `referenceCaseCount` | 통계의 사례 건수. 화면에 "37건 기준" 으로 쓸 수 있다 |
| `flag` | 확인 권장 사유. **`null` 이면 정상 항목** |
| `reason` | 사람이 읽는 사유 문장. **`flag` 가 null 이면 null** |
| `displayDecision` | **`flag == null ? "범위 내" : "확인 권장"`** — 서버가 만들어 준다 |

> ★ **`displayDecision` 을 그대로 화면에 쓰면 된다.** 와이어프레임의 "범위 내"·"확인 권장" 배지가 바로 이 값이다. **FE 가 한글을 하드코딩하지 않아도 된다.**

**`flag` 5종과 화면 문구 방향** — `reason` 이 이미 사람이 읽는 문장이므로 **`reason` 을 그대로 노출하는 것을 권한다.** 아래는 아이콘·색·그룹핑을 나눌 때 쓸 분류다.

| `flag` | 뜻 | 성격 | 화면 문구 방향 |
|---|---|---|---|
| `OVER_P75` | 소계가 유사 사례 75백분위를 **초과** | **금액 이슈** | "유사 사례보다 높습니다 — 산정 근거를 확인해 보세요" |
| `NOT_IN_ANALYSIS` | AI 분석에서 확인되지 않은 부품의 **교환** | **범위 이슈** | "AI 분석에서 확인되지 않은 부품입니다" |
| `DUPLICATE_LABOR` | 같은 부품·표준작업의 공임이 앞선 행에도 있음 | **중복 이슈** | "공임이 여러 번 산정되어 있습니다" |
| `UNMAPPED_ITEM` | 표준 부품 코드로 매핑하지 못함 | **판독 한계** | "항목명을 해석하지 못해 비교하지 못했습니다" |
| `INSUFFICIENT_REFERENCE` | 차급·부품·손상·수리방식에 맞는 비교 자료 부족 | **판독 한계** | "비교할 사례가 부족합니다" |

> ★ **`UNMAPPED_ITEM`·`INSUFFICIENT_REFERENCE` 는 "이상"이 아니라 "비교 못 함"이다.** `OVER_P75` 와 같은 톤으로 표시하면 정상 견적을 문제처럼 보이게 만든다. **색·아이콘을 분리할 것.**
> ⚠️ 다만 서버는 이 둘도 `reviewItemCount` 에 **포함**해서 센다(2-2). "확인 권장 2건" 안에 판독 한계가 섞여 있을 수 있다.

> ★ **한 항목에 플래그가 여러 개 붙어도 응답에는 하나만 온다.** 저장 시 `questionOrder` 가 가장 낮은 것 하나만 남는다 — `OVER_P75`(10) → `NOT_IN_ANALYSIS`(20) → `DUPLICATE_LABOR`(30) → `UNMAPPED_ITEM`(40) → `INSUFFICIENT_REFERENCE`(50).

#### 1-4-3. ★ `differenceFromMedian` 과 `differenceFromRangeMax` — 무엇을 기본으로 보여줄 것인가

두 값의 정의는 명확하다.

| 필드 | 계산식 | 뜻 |
|---|---|---|
| `differenceFromMedian` | `claimedTotal − aiTotalMedian` | AI 예상 **중앙값** 대비 차이 |
| `differenceFromRangeMax` | `claimedTotal − aiTotalMax` | AI 예상 **범위 상한** 대비 차이 |

**둘 다 음수일 수 있다.** 견적이 AI 예상보다 낮으면 음수다. `+`/`−` 부호는 FE 가 붙여야 한다.

### **결론: 헤드라인 숫자는 `differenceFromMedian` 을 쓴다.** (근거 강도 — 강함)

두 가지 근거가 서로 맞물린다.

1. **서버가 만든 `summary` 문장이 중앙값 기준이다** — `"정비소 견적은 AI 중앙값보다 62,000원 차이납니다."` 헤드라인에 `differenceFromRangeMax` 를 쓰면 바로 아래 요약문과 숫자가 어긋나 보인다.
2. **등급 판정의 `totalDifferenceRatio` 도 중앙값 기준이다** — `|claimedTotal − aiTotalMedian| ÷ aiTotalMedian`. 헤드라인 숫자와 "주의" 배지가 같은 기준에서 나와야 사용자가 납득한다.

**와이어프레임도 숫자로는 중앙값을 가리킨다.** `정비소 견적서 검증 결과.png` 의 카드는 `+62,000원` 이고 부제가 `견적서 622,000원 · AI 예상 480,000 ~ 610,000원` 이다. `622,000 − 610,000 = 12,000` 이므로 **범위 상한 기준으로는 나올 수 없는 숫자**이고, 중앙값이 `560,000` 일 때의 `differenceFromMedian` 과만 맞는다.

> ⚠️ **다만 그 카드의 라벨은 "AI 예상 범위와의 차이" 다.** 라벨은 범위를, 숫자는 중앙값을 가리켜 서로 어긋난다.
> **이 문서는 숫자 쪽을 정본으로 본다** — 라벨은 문안이고 숫자는 계약이며, 위 두 근거가 모두 중앙값을 가리키기 때문이다.
> → **라벨을 `"AI 예상 중앙값과의 차이"` 로 바꾸는 것을 권한다.** 부제에 범위(`480,000 ~ 610,000`)가 이미 있으므로 정보 손실이 없다. 문안 확정은 기획 판단이다 → 6장.

`differenceFromRangeMax` 는 **"범위를 벗어났는가"를 판정하는 보조 값**으로 쓰는 것이 맞다 — 0 이하면 AI 예상 범위 안, 양수면 상한을 넘었다는 뜻이다. 배지나 색 결정에 쓰면 된다.

#### 1-4-4. `legalNotice` — 서버가 주는 문구를 그대로 쓸 것

```
이 결과는 AI와 사례 통계를 이용한 참고용 추정치이며 실제 수리비와 다를 수 있고 특정 사업자를 평가하지 않습니다.
```

`EstimateValidationService.LEGAL_NOTICE` 상수다. **화면과 PDF 에 같은 문구가 나가야 해서 응답에 담는다.** 명세서 23행이 검증 PDF 에 *"특정 사업자를 평가하지 않습니다"* 고지를 필수로 요구한다.

> ⚠️ **와이어프레임 상단 배너 문구는 이것과 다르다** — `"이 견적은 AI 추정치로 실제 수리비와 다를 수 있으며 법적 효력이 없습니다"`.
> **서버가 주는 `legalNotice` 를 쓸 것.** 문구를 FE 에 하드코딩하면 서버가 고쳐도 화면이 따라가지 않고, 특히 *"특정 사업자를 평가하지 않습니다"* 가 빠지면 명세서 요구를 못 지킨다. 배너 문안 변경은 기획 판단이다 → 6장.

**에러**

| 상황 | 상태 | `error.code` | `error.message` |
|---|:---:|---|---|
| 없는 검증 · **남의 검증** | 404 | `NOT_FOUND` | `견적서 검증을 찾을 수 없습니다.` |
| **아직 `COMPLETED` 가 아님** | **409** | `CONFLICT` | `완료된 검증 결과가 없습니다.` |
| 미인증 | 401 | — | 4-3 |

> ★ **409 를 에러 토스트로 띄우지 말 것.** 파일 업로드 경로에서는 "아직 처리 중"이라는 뜻이므로 **정상 흐름**이다. 로딩 화면을 유지하고 폴링을 계속하면 된다.

---

### 1-5. GET `/api/estimate-validations/{validationId}/questions` — ★ 정비소 확인 질문

**확인 권장 항목에서 자동 생성된, 정비소에 그대로 물어볼 수 있는 문장 목록**이다. 사용자가 복사해 정비소에 보내는 것이 목적이라 문구를 서버가 확정해 준다.

**★ `data` 가 바로 배열이다.** 다른 목록 API 와 달리 한 번 더 감싸지 않는다.

**200**

```json
{
  "data": [
    {
      "questionId": 1,
      "validationItemId": 12,
      "lineNo": 2,
      "sourceFlag": "OVER_P75",
      "text": "프론트 펜더 판금 비용의 산정 근거와 작업 범위, 적용 공임 단가를 알려주실 수 있나요?",
      "displayOrder": 1
    },
    {
      "questionId": 2,
      "validationItemId": 15,
      "lineNo": 4,
      "sourceFlag": "DUPLICATE_LABOR",
      "text": "건조료 도장 공임이 여러 번 산정된 기준과 각각의 작업 범위를 확인해 주실 수 있나요?",
      "displayOrder": 2
    }
  ]
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `questionId` | number | 질문 PK |
| `validationItemId` | number \| null | 이 질문을 만든 **항목**의 id. `items[]` 의 행과 연결하는 키 |
| `lineNo` | number \| null | 그 항목의 견적서 줄 번호. 화면에서 "2번 항목에 대한 질문" 으로 묶을 때 쓴다 |
| `sourceFlag` | string \| null 아님 | 질문을 만든 사유. `OVER_P75`·`NOT_IN_ANALYSIS`·`DUPLICATE_LABOR`·`UNMAPPED_ITEM`·`INSUFFICIENT_REFERENCE` |
| `text` | string | **그대로 화면에 쓰고 그대로 복사시키면 되는 완성 문장.** 최대 500자 |
| `displayOrder` | number | 표시 순서. **1부터 연속** |

> ⚠️ **`validationItemId`·`lineNo` 는 `null` 이 될 수 있는 타입이다.** 다만 **현재 흐름에서는 실제로 null 이 되지 않는다** — 질문은 확인 권장 항목에서만 만들어지고 그 항목은 반드시 함께 저장되기 때문이다(1-5-2). **null 체크는 넣되 그 분기를 화면 설계의 전제로 삼지 말 것.**

#### 1-5-1. 언제 만들어지는가 — **검증 처리 시점**이다, 조회 시점이 아니다

질문은 **검증을 처리할 때 한 번 생성되어 저장**된다. `GET .../questions` 는 저장된 것을 읽기만 한다.

- **몇 번을 불러도 같은 문장, 같은 순서**가 온다. 캐시해도 안전하다
- 조회할 때 LLM 을 부르거나 다시 만들지 않는다 (명세서 26행 *"조회 시 재생성하지 않음"*)
- 생성 규칙이 **결정론적**이다 — 같은 견적서를 다시 검증하면 같은 질문이 나온다

#### 1-5-2. 어떤 항목이 질문이 되는가

**`items[]` 중 `flag` 가 붙은 항목(= `displayDecision` 이 `"확인 권장"` 인 항목)만** 질문이 된다.

- `flag` 가 `null` 인 정상 항목은 질문을 만들지 않는다
- 확인 권장 항목이 하나도 없으면 **빈 배열** `{ "data": [] }` 이다. 404 가 아니다
- **한 항목에 여러 사유가 겹치면 질문이 여러 개 나온다.** `items[]` 는 대표 `flag` 하나만 내려주지만(1-4-2) 질문은 **사유마다 하나씩** 만들어진다
  → **질문 수가 "확인 권장 N건" 보다 많을 수 있다.** 화면에서 "N건의 질문" 을 `reviewItemCount` 로 쓰지 말고 **배열 길이**를 쓸 것

#### 1-5-3. 사유별 질문 문장 — 5종 전문

`{부품}` 과 `{작업}` 만 바뀌고 나머지는 고정 문구다. **FE 가 문장을 만들 필요가 없다.**

| `sourceFlag` | 질문 문장 |
|---|---|
| `OVER_P75` | `{부품} {작업} 비용의 산정 근거와 작업 범위, 적용 공임 단가를 알려주실 수 있나요?` |
| `NOT_IN_ANALYSIS` | `{부품} 교환이 필요한 손상 근거와 가능한 다른 수리 방식을 알려주실 수 있나요?` |
| `DUPLICATE_LABOR` | `{부품} {작업} 공임이 여러 번 산정된 기준과 각각의 작업 범위를 확인해 주실 수 있나요?` |
| `UNMAPPED_ITEM` | `{부품} 항목이 정확히 어떤 부품과 작업을 의미하는지 알려주실 수 있나요?` |
| `INSUFFICIENT_REFERENCE` | `{부품} {작업} 작업 범위와 비용 산정 근거를 알려주실 수 있나요?` |

- **`{작업}`** 은 작업유형의 한글 표기다 — 교환 · 탈착 · 판금 · 도장 · 오버홀 · 수리 (3-1 표와 같은 값). `NOT_IN_ANALYSIS`·`UNMAPPED_ITEM` 템플릿에는 들어가지 않는다
- **`{부품}`** 은 이 순서로 정해진다 — **표준 부품명** → 없으면 **사용자가 입력한 원문** → 그것도 비면 **`"해당 항목"`**
- 5종 전부 템플릿이 있다. **사유가 있는데 질문이 안 나오는 경우는 없다**

#### 1-5-4. 순서·중복·길이

| 항목 | 동작 |
|---|---|
| **정렬** | `lineNo` 오름차순 → 같은 줄이면 사유 우선순위(`OVER_P75` → `NOT_IN_ANALYSIS` → `DUPLICATE_LABOR` → `UNMAPPED_ITEM` → `INSUFFICIENT_REFERENCE`) |
| **`displayOrder`** | 정렬된 결과에 **1부터 연속**으로 부여. 빈 번호가 없다 |
| **중복 제거** | **문장이 완전히 같으면 하나만 남는다.** 서로 다른 줄이 같은 문장을 만들어도 1개 |
| **부품명 길이** | 60자에서 자른다 |
| **질문 길이** | 500자 초과 시 **499자 + `?`** — 물음표 없이 끊기지 않는다. DB 컬럼도 `VARCHAR(500)` |

> **`displayOrder` 오름차순으로 이미 정렬되어 온다. FE 가 다시 정렬하지 말 것.**
> 중복 제거 때문에 **질문 수가 (확인 권장 항목 × 사유) 보다 적을 수 있다.** 정상이다.

#### 1-5-5. ★ `/result` 에도 같은 질문이 들어 있다 — 어느 쪽을 쓸 것인가

`GET .../result` 응답의 `questions[]` 와 이 API 의 응답은 **같은 데이터, 같은 순서**다. 같은 저장소를 같은 정렬로 읽는다.

**권장: 결과 화면은 `/result` 한 번만 호출한다.**

와이어프레임 `정비소 견적서 검증 결과.png` 에서 **정비소 확인 질문이 검증 결과와 같은 화면 아래쪽에 있다**(Q1·Q2·Q3 + 각 행의 "복사" + "질문 전체 복사"). 한 화면에 함께 그리는 구조이므로 요청을 두 번 보낼 이유가 없고, 두 번 부르면 화면 일부만 늦게 채워진다.

**`/questions` 를 따로 쓰는 경우는 이 둘이다.**

| 상황 | 이유 |
|---|---|
| **질문만 다시 불러올 때** — 복사 후 목록 갱신, 부분 리프레시 | 결과 전체(항목·통계 포함)를 다시 받지 않아도 된다. 응답이 훨씬 가볍다 |
| **검증이 아직 `COMPLETED` 가 아닐 때** | `/result` 는 **409** 를 내지만 `/questions` 는 **빈 배열 `[]`** 을 준다. 상태 검사를 하지 않기 때문이다 |

> ★ **두 번째 차이가 중요하다.** 처리 중에 `/questions` 를 부르면 에러가 아니라 빈 배열이 온다. **"질문이 없다" 와 "아직 안 끝났다" 를 이 API 만으로는 구별할 수 없다.** 상태는 `GET /{id}` 로 판단할 것.

#### 1-5-6. 문구 정책 — 서버가 코드로 강제한다

**모든 질문이 의문형 요청체다.** *"…알려주실 수 있나요?"* · *"…확인해 주실 수 있나요?"* 로 끝난다. 단정적으로 잘못을 지적하는 문장이 하나도 없다.

이것이 주석이 아니라 **실행 코드**로 들어가 있다. 부품명이 OCR 로 읽은 원문이라 무엇이 섞여 들어올지 모르기 때문에, 질문을 만들기 전에 부품명에서 아래를 제거한다.

| 제거 대상 | 이유 |
|---|---|
| `<script>…</script>` 블록 · 모든 `<태그>` | 질문 문장이 화면에 그대로 렌더링되므로 |
| **`사기` · `허위` · `불필요(한 수리\|확정)`** | **"확인 권장" 정책.** 원문에 들어 있어도 질문에 실리지 않는다 |
| 허용 문자 밖의 모든 문자 | 허용: 숫자 · 영문 · 한글 · 공백 · `(` `)` `/` `_` `-` |
| 연속 공백 | 하나로 합친다 |

→ **FE 가 이 문장을 세게 바꾸지 말 것.** 정비소를 평가하는 서비스가 아니라 소비자가 물어볼 말을 만들어 주는 서비스다. 사용자가 직접 편집하는 UI 를 둘 수는 있으나 **기본 문구는 서버 값 그대로**가 맞다.

> 표준 부품명(마스터 56종)은 위 필터에 **전혀 영향받지 않는다.** 원문 fallback 에 `·` `,` `.` `#` 같은 문자가 섞이면 공백으로 바뀌지만 뜻은 유지된다(예: `앞 범퍼·그릴` → `앞 범퍼 그릴`).

#### 1-5-7. 복사 기능은 FE 몫이다

**서버는 완성된 텍스트만 준다.** 클립보드 복사·"질문 전체 복사"·복사 완료 토스트는 전부 FE 구현이다(`[FE] 정비소 확인 질문 생성 화면 구성` Task 333).

- 전체 복사는 `text` 를 줄바꿈으로 이어 붙이면 된다. 서버가 합쳐 주는 API 는 없다
- 번호를 붙이려면 `displayOrder` 를 쓰면 된다

**에러**

| 상황 | 상태 | `error.code` | `error.message` |
|---|:---:|---|---|
| 없는 검증 · **남의 검증** | 404 | `NOT_FOUND` | `견적서 검증을 찾을 수 없습니다.` |
| 미인증 | 401 | — | 봉투가 다르다 → 4-3 |
| 가입 미완료 세션 | 403 | `SIGNUP_REQUIRED` | `약관 동의 후 가입을 완료해 주세요.` → 4-4 |

**`COMPLETED` 가 아니어도 409 가 아니다** — 빈 배열이 온다(1-5-5). 소유권 검사는 조회 쿼리 조건에 있어 남의 검증은 404 로 통일된다.

### 1-6. GET `/api/estimate-validations/me` — 검증 이력 목록

**요청** — `?page=0&size=20` (기본값 `page=0` · `size=20`)

| 파라미터 | 기본 | 제약 |
|---|---|---|
| `page` | 0 | **0 이상.** 음수면 400 |
| `size` | 20 | **1 이상.** 0 이하면 400. **100 을 넘겨도 100 으로 잘린다**(에러 아님) |

**200**

```json
{
  "data": {
    "content": [
      {
        "validationId": 1,
        "accidentId": 1,
        "manufacturer": "현대",
        "modelName": "아반떼",
        "modelYear": 2020,
        "inputType": "MANUAL",
        "status": "COMPLETED",
        "grade": "CAUTION",
        "claimedTotal": 622000,
        "reviewItemCount": 2,
        "totalItemCount": 4,
        "createdAt": "2026-09-08T02:11:30Z",
        "completedAt": "2026-09-08T02:11:30Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

- **`created_at DESC`** — 최근 검증이 위. 정렬 UI 를 만들지 말 것
- 차량 정보는 **사고 접수 시점 스냅샷**이다(`accident.snapshot_*`). 이후 차량을 수정·삭제해도 이력의 차종은 바뀌지 않는다
- `grade` 는 **`COMPLETED` 가 아니면 null** 이다. 목록에 배지를 그릴 때 null 처리가 필요하다
- **`gradeDisplayName` 이 없다.** 목록에서는 FE 가 3-2 표로 변환해야 한다 (결과 조회에는 있다)
- 결과가 없으면 `content: []` — 404 가 아니다

**에러** — 400 `INVALID_REQUEST` `page는 0 이상, size는 1 이상이어야 합니다.` · 401

---

### 1-7. GET `/api/estimate-validations/{validationId}/pdf` — PDF 다운로드

**302 Found + `Location` 헤더**에 presigned URL 이 담긴다. 본문은 없다.

- URL 유효시간은 **`app.estimate-validation.presigned-url-minutes` (현재 10분)** → 5장
- 만료되면 **이 API 를 다시 호출**하면 된다. 서버가 매번 새 URL 을 만든다
- 그래서 **URL 을 저장하거나 공유하지 말 것.** 링크를 눌러야 할 때마다 다시 발급받는다

> ⚠️ **`fetch` 로 부르지 말 것.** 브라우저가 302 를 자동으로 따라가 스토리지로 요청을 보내는데, 그 요청에 `credentials: 'include'` 와 CORS 프리플라이트가 얽혀 실패한다. **`window.location` 이동이나 `<a>` 링크로 처리할 것.**

**에러**

| 상황 | 상태 | `error.code` | `error.message` |
|---|:---:|---|---|
| 없는 검증 · 남의 검증 | 404 | `NOT_FOUND` | `견적서 검증을 찾을 수 없습니다.` |
| **PDF 가 아직 없음** | 409 | `CONFLICT` | `완료된 검증 PDF가 없습니다.` |
| **저장소 미구성** | 503 | `SERVICE_UNAVAILABLE` | `문서 저장소 공급자가 구성되지 않았습니다. 직접 입력을 이용해 주세요.` |

> **지금은 PDF 생성기가 없어 `estimate_validation_report` 가 `QUEUED` 에 머문다 → 항상 409 다.** 직접 입력으로 만든 검증도 마찬가지다. **PDF 버튼은 비활성으로 두거나 숨길 것.**

---

### 1-8. DELETE `/api/estimate-validations/{validationId}` — 검증 삭제

**204 No Content · 본문 없음.** `res.json()` 을 호출하면 파싱 에러가 난다.

- **물리 삭제다.** 개인정보 문서라 행을 지운다(명세서 21행). 소프트 삭제가 아니므로 **되돌릴 수 없다** → 확인 모달 필요
- 원본 파일·PDF 의 스토리지 오브젝트도 함께 지운다
- 항목·질문·리포트는 FK `ON DELETE CASCADE` 로 함께 사라진다

> ⚠️ **지울 오브젝트 키가 하나라도 있으면 저장소를 호출하므로 지금은 503 이다.** 직접 입력으로 만든 검증은 `s3_key_file` 이 null 이고 리포트 PDF 도 없어 **저장소를 부르지 않으므로 204 로 성공한다.**

**에러** — 404 `NOT_FOUND` · 503 `SERVICE_UNAVAILABLE` · 401

---

## 2. 등급 3단계와 판정 규칙

### 2-1. 등급 값과 표시 문구

| `grade` | `gradeDisplayName` | 뜻 |
|---|---|---|
| `APPROPRIATE` | **적정 범위** | 확인을 권장할 항목이 없고 총액도 기준 안 |
| `CAUTION` | **주의** | 확인 권장 항목이 있거나 총액 차이가 1차 기준 이상 |
| `NEEDS_REVIEW` | **확인 필요** | 확인 권장 항목이 많거나 개별·총액 차이가 크다 |

★ **`gradeDisplayName` 을 그대로 쓰면 된다.** enum 에 붙은 상수라 서버·PDF·화면이 같은 문구를 쓴다. DDL 도 `ck_ev_grade` 로 세 값만 허용한다.

```sql
CONSTRAINT ck_ev_grade CHECK (llm_grade IS NULL
                        OR llm_grade IN ('APPROPRIATE','CAUTION','NEEDS_REVIEW'))
```

> **목록 API(`/me`)에는 `gradeDisplayName` 이 없다** → 3-2 상수표로 변환.

### 2-2. 판정 규칙 — 결정론적이고 순서가 있다

**`NEEDS_REVIEW` 를 먼저 본다. 세 조건 중 하나만 걸려도 `NEEDS_REVIEW` 다.**

| 순서 | 등급 | 조건 (하나라도 참이면 이 등급) |
|:---:|---|---|
| 1 | `NEEDS_REVIEW` | `reviewItemCount >= needs-review-item-count` (현재 **3**)<br>**또는** `개별 항목 최대 초과배수 >= severe-over-p75-multiplier` (현재 **1.5**)<br>**또는** `총액 차이 비율 >= needs-review-total-difference-ratio` (현재 **0.20**) |
| 2 | `CAUTION` | `reviewItemCount > 0`<br>**또는** `총액 차이 비율 >= caution-total-difference-ratio` (현재 **0.10**) |
| 3 | `APPROPRIATE` | 위 어디에도 걸리지 않음 |

**세 입력값이 어디서 나오는가** (`EstimateValidationService.decideGrade`)

| 입력 | 계산 |
|---|---|
| `reviewItemCount` | `flag` 가 하나라도 붙은 항목의 수 |
| `highestReferenceRatio` | 항목별 `subtotal ÷ referenceP75` 중 **최댓값**. 비교 자료가 없는 항목은 제외하고, 하나도 없으면 0 |
| `totalDifferenceRatio` | `\|claimedTotal − aiTotalMedian\| ÷ aiTotalMedian`. **절대값이다** |

**정책 문장으로 옮기면 이렇다.**

- **확인 권장 항목이 하나라도 있으면 "적정 범위"가 될 수 없다.** `CAUTION` 의 하한이 `reviewItemCount > 0` 이다
- **확인 권장 항목이 3개 이상이면 총액이 정확해도 "확인 필요"다.** 개수 자체가 신호다
- **항목 하나가 유사 사례 75백분위의 1.5배를 넘으면 그 하나만으로 "확인 필요"다.** 총액이 맞아도 개별 항목이 심하면 걸러낸다
- **OR 인 이유** — 세 가지가 서로 다른 종류의 이상을 잡는다. 개수(넓게 퍼진 이상) · 개별 배수(한 항목의 심한 이상) · 총액 비율(전체 규모의 이상). AND 로 묶으면 각각을 놓친다

**경계값** — `GradeDeciderTest` 로 고정되어 있다.

| 조건 | 연산자 | 경계 |
|---|:---:|---|
| 항목 수 (확인 필요) | **`>=`** | 2건 → `CAUTION`, **3건 → `NEEDS_REVIEW`** |
| 개별 초과배수 | **`>=`** | 1.4999 → `CAUTION`, **1.50 → `NEEDS_REVIEW`** |
| 총액 차이 비율 (주의) | **`>=`** | 0.0999 → `APPROPRIATE`, **0.10 → `CAUTION`** |
| 총액 차이 비율 (확인 필요) | **`>=`** | 0.1999 → `CAUTION`, **0.20 → `NEEDS_REVIEW`** |
| 항목 수 (주의 하한) | **`>`** | **0건 → 걸리지 않음**, 1건 → `CAUTION` |

> ⚠️ **`estimateId` 를 보내지 않으면 총액 차이 비율이 0 으로 고정된다.** AI 견적이 없으면 비교 대상이 없기 때문이다. `aiTotalMedian` 이 null 이거나 0 이어도 같다. 이때 등급은 **확인 권장 항목 수와 개별 초과배수만으로** 결정된다.
> → **FE 가 "AI 예상 견적을 먼저 만드세요" 안내를 하는 편이 좋다.** 안 그러면 총액 이상을 잡지 못한다.

**`reviewItemCount` 가 세는 것** — `flag` 가 붙은 항목 전부다. **`UNMAPPED_ITEM`·`INSUFFICIENT_REFERENCE`(판독 한계)도 포함된다.** 비교 자료가 없는 견적은 항목 수가 많을수록 등급이 올라갈 수 있다.

### 2-3. 판정 문구 정책 — **"확인 권장" 만 쓴다**

**단정적 표현을 쓰지 않는다.** *"부당청구"* · *"과다청구"* · *"바가지"* 같은 말은 화면·PDF 어디에도 넣지 않는다.

- 서버가 주는 `displayDecision` 은 `범위 내` · **`확인 권장`** 두 값뿐이다
- `summary` 도 *"…개 항목의 확인을 권장합니다"* 로 끝난다
- `legalNotice` 가 *"특정 사업자를 평가하지 않습니다"* 를 명시한다
- 명세서 25행도 *"'확인 권장'만 사용"* 을 못박고 있다

→ **FE 가 문구를 세게 바꾸지 말 것.** 정비소를 평가하는 서비스가 아니라 소비자가 물어볼 질문을 만들어 주는 서비스다.

---

## 3. 상수표 — 그대로 복사해 쓸 것

### 3-1. `workType` 코드 → 한글

`items[].workType` 은 서버 enum 값이다. 사용자에게 그대로 보여줄 수 없다.

```js
export const WORK_TYPE_LABEL = {
  REPLACEMENT:  '교환',
  DETACHMENT:   '탈착',
  SHEET_METAL:  '판금',
  PAINTING:     '도장',
  OVERHAUL:     '오버홀',
  REPAIR:       '수리',
};
```

> ⚠️ **요청에 보내는 값과 응답으로 받는 값이 다르다.** 보낼 때는 한글(`"교환"`), 받을 때는 enum(`"REPLACEMENT"`) 이다. 같은 상수를 양방향으로 쓰지 말 것.

### 3-2. `grade` 코드 → 한글 (목록 API 용)

결과 조회에는 `gradeDisplayName` 이 있지만 **목록 API 에는 없다.**

```js
export const GRADE_LABEL = {
  APPROPRIATE:  '적정 범위',
  CAUTION:      '주의',
  NEEDS_REVIEW: '확인 필요',
};
```

### 3-3. `flag` 코드 → 분류

```js
export const FLAG_KIND = {
  OVER_P75:               'amount',    // 금액 이슈
  NOT_IN_ANALYSIS:        'scope',     // 범위 이슈
  DUPLICATE_LABOR:        'duplicate', // 중복 이슈
  UNMAPPED_ITEM:          'unknown',   // 판독 한계 — "이상"이 아니다
  INSUFFICIENT_REFERENCE: 'unknown',   // 판독 한계 — "이상"이 아니다
};
```

**문구는 서버의 `reason` 을 그대로 쓰고, 이 분류는 색·아이콘에만 쓸 것.**

---

## 4. 공통 응답 봉투와 에러

### 4-1. 성공 봉투 — 항상 `data` 로 감싼다

```json
{ "data": { ... } }
```

| API | 꺼내는 경로 |
|---|---|
| 접수(202) · 상태 · 결과 | `res.data` (객체) |
| **확인 질문** | **`res.data`** (★ 배열이 바로 온다) |
| 이력 목록 | `res.data.content` (+ `page`·`size`·`totalElements`·`totalPages`) |
| PDF | 본문 없음 (302 · `Location` 헤더) |
| 삭제 | 본문 없음 (204) |

> ★ **확인 질문만 `data` 가 배열이다.** 차량 API 처럼 `{ "vehicles": [...] }` 로 한 번 더 감싸지 않는다.

### 4-2. 에러 봉투 — 400 · 404 · 409 · 500 · 503

```json
{ "error": { "code": "CONFLICT", "message": "완료된 검증 결과가 없습니다." } }
```

**이 도메인에서 실제로 나오는 코드**

| 상태 | `error.code` | 언제 |
|:---:|---|---|
| 400 | `INVALID_REQUEST` | 요청 검증 실패 · 파일 검증 실패 · 페이징 파라미터 |
| 404 | `NOT_FOUND` | 없는 리소스 · **남의 리소스** |
| 409 | `CONFLICT` | 아직 완료되지 않은 결과·PDF 를 요청 |
| **500** | `INTERNAL_ERROR` | **지금은 `CurrentMemberProvider` 스텁 때문에 전부 여기로 온다** → ⚠️ 시작하기 전에 |
| 503 | `SERVICE_UNAVAILABLE` | 저장소 공급자 미구성 |

> ★ **리소스 소유권 위반은 403 이 아니라 404 다.** 남의 검증·남의 사고·남의 견적은 **전부 404** 다. Repository 쿼리 자체가 `findByValidationIdAndMemberId` 처럼 `memberId` 를 조건에 넣어 조회하므로, 서비스 입장에서는 "없는 것"과 "남의 것"이 구별되지 않는다. 리소스 존재 여부를 노출하지 않기 위한 의도적 선택이다.
> → **404 를 "권한 없음"으로 나눠 표시하지 말 것.** 구분할 방법이 없다.

### 4-3. ★ 401 만 봉투가 다르다 — 본문을 파싱하지 말 것

**401 은 공통 예외 처리기를 거치지 않는다.** 시큐리티 필터가 `response.sendError(SC_UNAUTHORIZED)` 로 끝내기 때문에 서블릿 컨테이너 기본 오류가 나간다.

```json
{"timestamp":"...","status":401,"error":"Unauthorized","path":"/api/estimate-validations/1"}
```

⚠️ **`error` 가 객체가 아니라 문자열이다.** `err.error.code` 가 `undefined` 이고 `err.error.message` 도 `undefined` 다.

`SecurityConfig` 주석에 *"401 은 기존 동작(본문 없는 sendError)을 유지한다"* 고 명시되어 있다. 기존 테스트 계약을 깨지 않으려는 의도적 선택이며 **곧 바뀔 예정이 아니다.**

→ **401 은 본문을 보지 말고 `status === 401` 로만 분기할 것.** (로그인 화면으로 보내면 된다.)

### 4-4. ★ 403 은 두 가지다 — 가입 대기와 권한 부족을 나눠야 한다

**403 은 401 과 달리 공통 봉투를 쓴다.** `RestAccessDeniedHandler` 가 직접 같은 모양으로 써 준다.

| 상황 | `error.code` | `error.message` | FE 가 할 일 |
|---|---|---|---|
| **소셜 인증만 끝나고 가입 미완료** (`ROLE_SIGNUP_PENDING`) | **`SIGNUP_REQUIRED`** | `약관 동의 후 가입을 완료해 주세요.` | **약관·가입 화면으로 보낸다** |
| 권한 부족 (관리자 전용 API 등) | `FORBIDDEN` | `접근 권한이 없습니다.` | 권한 없음 안내 |

> ★ **`SIGNUP_REQUIRED` 를 놓치면 신규 사용자가 막힌다.** 소셜 로그인만 하고 약관에 동의하지 않은 세션은 "인증됨"이지만 `ROLE_USER` 가 아니라서 모든 검증 API 에서 403 이 난다. `error.code` 로 분기해 가입 화면으로 보내야 한다.
> 이 403 은 `/api/estimate-validations/**` 자체의 결함이 아니라 **전 API 공통 동작**이다.

```js
if (res.status === 401) { goToLogin(); return; }        // 본문 파싱 금지
if (res.status === 204 || res.status === 302) return;   // 본문 없음
const body = await res.json();
if (res.status === 403 && body.error.code === 'SIGNUP_REQUIRED') { goToSignup(); return; }
if (!res.ok) showError(body.error.message);             // 400 · 404 · 409 · 500 · 503
```

### 4-5. 인증 방식과 CORS

- **서버 세션 방식**이다. JWT 가 아니다. 세션 ID 는 HttpOnly 쿠키로 오간다
- → **모든 요청에 `credentials: 'include'` (axios 는 `withCredentials: true`) 가 필요하다**
- CORS 는 **`http://localhost:5173` 하나만** 허용한다. `allowCredentials: true`, 허용 메서드는 `GET`·`POST`·`PUT`·`PATCH`·`DELETE`·`OPTIONS`
- → **FE 개발 서버를 반드시 5173 포트로 띄울 것.** 포트가 다르면 CORS 에서 막힌다

---

## 5. 판정 임계값과 조정 방법 (315)

**코드를 고치지 않고 조정할 수 있다.** `EstimateValidationProperties` 가 `app.estimate-validation.*` 를 바인딩한다.

`backend/src/main/resources/application.properties` **89~94행**:

```properties
# -- Estimate validation deterministic thresholds --
# These are conservative product assumptions until production calibration data is approved.
app.estimate-validation.reference-percentile=75
app.estimate-validation.severe-over-p75-multiplier=1.5
app.estimate-validation.caution-total-difference-ratio=0.10
app.estimate-validation.needs-review-total-difference-ratio=0.20
app.estimate-validation.needs-review-item-count=3
app.estimate-validation.presigned-url-minutes=10
```

| 프로퍼티 | 제약 | 현재 | 의미 | 값을 **올리면** |
|---|---|---:|---|---|
| `reference-percentile` | `@Min(1) @Max(100)` | 75 | 사례 통계에서 비교 기준으로 쓸 분위 | **아무 변화 없다** ⚠️ 아래 |
| `severe-over-p75-multiplier` | `@DecimalMin("1.0", exclusive)` | 1.5 | 기준 분위의 몇 배를 넘으면 즉시 확인 필요인가 | 판정이 **완화**된다 |
| `caution-total-difference-ratio` | `@DecimalMin("0.0")` | 0.10 | 총액 차이 비율이 이 값 이상이면 주의 | 판정이 **완화**된다 |
| `needs-review-total-difference-ratio` | `@DecimalMin("0.0")` | 0.20 | 총액 차이 비율이 이 값 이상이면 확인 필요 | 판정이 **완화**된다 |
| `needs-review-item-count` | `@Min(1)` | 3 | 확인 권장 항목이 몇 개 이상이면 확인 필요인가 | 판정이 **완화**된다 |
| `presigned-url-minutes` | `@Min(1) @Max(1440)` | 10 | 검증 PDF 다운로드 URL 유효시간(분) | 링크가 오래 산다 |

> ⚠️ **`reference-percentile` 은 현재 아무 데도 쓰이지 않는다.**
> 바인딩·검증은 되지만 **이 값을 읽는 프로덕션 코드가 한 줄도 없다.** 비교 기준은 `repair_cost_stat.cost_p75` 로 하드코딩되어 있어 이 값을 90 으로 바꿔도 판정은 그대로 75백분위 기준이다. `GradePolicy` 인터페이스에도 이 값은 없다. **바꿔도 소용없다는 것을 알고 있을 것** — 후속 이슈로 보고되어 있다.

### 5-1. 교차 제약 — 두 값을 거꾸로 넣으면 **기동이 실패한다**

```java
@AssertTrue(message = "needs-review-total-difference-ratio must be at least caution-total-difference-ratio")
public boolean isDifferenceRatioOrderValid()
```

`needs-review-total-difference-ratio >= caution-total-difference-ratio` 가 아니면 스프링 컨텍스트가 뜨지 않는다. 실수를 런타임까지 끌고 가지 않으려는 장치다.

### 5-2. 기본값이 없다 — 값을 빼면 기동이 실패한다

`@DefaultValue` 를 **일부러 두지 않았다.** 근거 없는 기본값으로 사용자에게 "확인 권장"을 띄우지 않겠다는 정책이다. 프로퍼티 하나라도 빠지면 기동 단계에서 멈춘다.

### 5-3. ★ 현재 값은 전부 **잠정치**다

요구사항에 *"예: 60%"* 형태로 적힌 값이라 **실제 데이터를 보고 조정해야 한다.** 프로퍼티 주석도 *"conservative product assumptions until production calibration data is approved"* 라고 적어 두었다.

→ **FE 가 "1.5배 초과 시 확인 필요" 같은 수치를 화면에 하드코딩하지 말 것.** 값이 바뀌면 화면이 거짓말을 한다. 수치를 보여줘야 한다면 서버의 `reason` 문장(사례 건수·실제 금액이 들어 있다)을 쓰면 된다.

### 5-4. 조정 절차

1. `application.properties` 의 해당 줄을 수정
2. 애플리케이션 **재기동** (핫 리로드 없음)
3. 배포 환경에서는 환경변수·외부 설정으로 덮을 수 있다 — 예: `APP_ESTIMATEVALIDATION_NEEDSREVIEWITEMCOUNT=4`
4. 기동에 실패하면 5-1·5-2 를 먼저 볼 것

**테스트 환경에도 같은 값이 있다** (`backend/src/test/resources/application.properties` 46~51행). main 과 어긋나면 `EstimateValidationPropertiesTest` 가 잡는다.

**설계 의도** — `EstimateValidationProperties` 는 `GradePolicy` **인터페이스를 구현**한다. 판정 도메인(`GradeDecider`)이 스프링 설정 타입을 모르게 하려는 분리다. 그래서 `GradeDeciderTest` 는 스프링 없이 익명 `GradePolicy` 로 경계값을 검증한다.

---

## 6. 미확정 — 기획·재원님 확인 대기

**임시값을 쓰지 말고 확정된 뒤에 반영한다.** 아래 셋 다 **API 계약은 바뀌지 않는다** — 화면 문구·표시 기준만 바뀐다.

| # | 미확정 항목 | 이 문서의 처리 | 정해지면 바뀌는 것 |
|---|---|---|---|
| 1 | **헤드라인 차이값 카드의 라벨** — 와이어프레임 라벨("AI 예상 범위와의 차이")과 숫자(`+62,000`, 중앙값 기준)가 어긋난다 | 숫자를 정본으로 보고 **`differenceFromMedian`** 을 권했다. 라벨을 "AI 예상 중앙값과의 차이"로 바꾸자고 제안 (1-4-3) | 카드 라벨 문구 |
| 2 | **상단 고지 배너 문안** — 와이어프레임과 서버 `legalNotice` 가 다르다 | 서버 값을 쓰라고 적었다 (1-4-4) | 서버 상수를 바꿀지 배너를 서버 값으로 맞출지 |
| 3 | **판독 한계(`UNMAPPED_ITEM`·`INSUFFICIENT_REFERENCE`)를 "확인 권장 N건" 에 넣을 것인가** | 서버가 이미 포함해서 센다. 그대로 표시하되 색을 나누라고 적었다 (1-4-2 · 2-2) | 카운트 정의 — 바꾸려면 **서버 수정**이 필요하다 |

> **FE 가 임의로 정하지 말 것.** 정하지 않은 것은 정하지 않은 채로 되돌아와야 한다.

---

## 7. 구현되지 않은 것 — 지금 기대하면 안 되는 범위

### 7-1. 포트만 있고 어댑터가 없다 (실측: 구현체 0개)

| 포트 | 없으면 무엇이 안 되는가 | 지금 응답 |
|---|---|---|
| `DocumentStoragePort` | 파일 업로드 · PDF 다운로드 · **삭제** | **503** `SERVICE_UNAVAILABLE` |
| `EstimateOcrPort` | 업로드한 견적서에서 항목을 읽어내기 | 워커가 없어 `QUEUED` 유지 |
| `SummaryGenerationPort` | LLM 요약문 생성 | 직접 입력은 **서버가 만든 결정론적 문장**을 쓴다 |
| `PdfGenerationPort` | 검증 결과 PDF | 리포트가 `QUEUED` 유지 → PDF 조회 **409** |

**가짜 성공을 만들지 않는다.** 하드코딩 URL 이나 더미 결과를 반환하지 않고 503·409 로 정직하게 실패한다.

### 7-2. `summary` 는 LLM 이 만든 문장이 아니다

컬럼 이름이 `llm_summary` 이고 필드 이름이 `summary` 지만, **현재는 서버가 템플릿으로 만든 문장**이다.

```
{등급}: 총 {N}개 항목 중 {M}개 항목의 확인을 권장합니다. {비교문}
```

`{비교문}` 은 `estimateId` 가 있으면 *"정비소 견적은 AI 중앙값보다 62,000원 차이납니다."*, 없으면 *"AI 예상 견적 중앙값과는 비교하지 않았습니다."* 다.

→ **지금은 문장 길이·형태가 예측 가능해 레이아웃이 안정적이지만, LLM 어댑터가 붙으면 길이가 달라진다.** 카드 높이를 고정하지 말 것.

`llm_model` 컬럼도 항상 null 이다. 명세서 25행의 *"매번 LLM 재호출 금지"* 는 지켜진다 — 저장된 `llm_grade`·`llm_summary` 를 렌더링만 한다.

### 7-3. 명세서와 현재 구현의 차이

| 명세서 | 실제 | FE 가 할 일 |
|---|---|---|
| *"OCR→LLM→PDF 진행 상태"*(19행) | 단계별 상태가 **없다.** `status` 4값뿐이고 `analysis_stage` 같은 단계 테이블이 이 도메인엔 없다 | **4단계 체크리스트를 만들지 말 것.** 단일 스피너 |
| *"매번 LLM 재호출 금지"*(25행) | ✅ 지켜진다 | — |
| 검증 PDF 고지 문구(23행) | 상수는 있으나 **PDF 생성기가 없다** | PDF 버튼 비활성 |

---

## 8. 화면 설계에 영향을 주는 동작 14가지

| # | 동작 | 화면에 미치는 영향 |
|---|---|---|
| 1 | **지금은 로그인해도 전부 500** (`CurrentMemberProvider` 스텁) | 실호출 검증을 미룬다. 요청 탓을 하지 말 것 → ⚠️ 시작하기 전에 |
| 2 | **직접 입력은 202 인데 이미 `COMPLETED`** | 폴링 없이 바로 결과 조회 → 0-1 |
| 3 | **파일 입력은 `QUEUED` 에서 멈춘다** (워커 없음) | 폴링에 타임아웃 필수 → 0-2 |
| 4 | **`FAILED` 가 절대 나오지 않는다** | 분기는 만들되 테스트 불가 → 0-3 |
| 5 | **총액 차이는 계산값** | 캐시해 재사용하지 말 것 → 0-4 |
| 6 | **결과 조회는 `COMPLETED` 전에 409** | 에러가 아니라 "처리 중" → 1-4 |
| 7 | **`displayDecision` 을 서버가 준다** | 배지 문구를 하드코딩하지 말 것 → 1-4-2 |
| 8 | **한 항목에 `flag` 는 하나만 온다** | 여러 사유가 겹쳐도 대표 하나만 표시된다 → 1-4-2 |
| 9 | **판독 한계 2종도 "확인 권장" 에 포함된다** | 색·아이콘을 분리 → 1-4-2 · 3-3 |
| 10 | **`estimateId` 없이 검증하면 총액 비교를 하지 않는다** | AI 견적을 먼저 만들도록 유도 → 2-2 |
| 11 | **차이값이 음수일 수 있다** | 부호를 FE 가 붙인다 → 1-4-3 |
| 12 | **남의 리소스는 전부 404**, 가입 대기는 **403 `SIGNUP_REQUIRED`** | 404 를 "권한 없음"으로 쓰지 말고, 403 은 가입 화면으로 → 4-2 · 4-4 |
| 13 | **삭제는 물리 삭제** (되돌릴 수 없음) | 확인 모달 필수 → 1-8 |
| 14 | **PDF 는 302 리다이렉트** | `fetch` 금지, `window.location` 사용 → 1-7 |

---

## 9. Jira 이슈 대응표

| 이슈 | 이 문서의 해당 장 |
|---|---|
| **314** `[BE] 검증 요약·등급 기능 구현` | 2장(등급·판정 규칙) · 1-4(요약·결과 계약) |
| **315** `[BE] 3단계 등급 판정 설정값 외부화` | 5장(프로퍼티 6개·조정 절차·교차 제약) |
| **316** `[BE] 총액 차이·확인 필요 항목 수 산출 및 검증 결과 저장·조회 API` | 1-4(응답 계약·필드 출처) · 0-4(계산 vs 저장) |

세 이슈의 구현 코드는 모두 `d84c6d5 (S15P21A307-310 MR)` 에 함께 머지되었다.

---

## 문서 갱신 이력

| 날짜 | 내용 |
|---|---|
| 2026-09-08 | 최초 작성. `develop` (`2da805b`) 머지본 기준 실측 |
| 2026-09-08 | 정비소 확인 질문 절(1-5) 추가 — S15P21A307-317·318 |

**갱신이 필요한 시점** — ① `CurrentMemberProvider` 교체 시(⚠️ 시작하기 전에 · 4-2 의 500 행 삭제 · 실호출 검증 반영) ② S3 어댑터 완료 시(1-2·1-7·1-8·7-1) ③ OCR·LLM·PDF 어댑터 완료 시(0-2·7-1·7-2) ④ 임계값 캘리브레이션 확정 시(5장 현재 값) ⑤ `reference-percentile` 이 실제로 쓰이게 되면(5장 경고 제거) ⑥ 6장 미확정 항목이 확정될 때
