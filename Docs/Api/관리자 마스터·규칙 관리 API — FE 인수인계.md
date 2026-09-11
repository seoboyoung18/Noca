# 관리자 마스터·규칙 관리 API — FE 인수인계

> **독자** — 관리자 화면을 만드는 프론트엔드 담당자. **백엔드 코드를 열지 않고** 이 문서만으로 붙일 수 있게 썼습니다.
> **작성 기준** — 2026-09-10 작성 (`develop` `25dccdd` 위의 로컬 작업본, 부품명 매핑 계약 변경 · `changeReason` · 상태 변경 멱등성) · **2026-09-11 갱신** — `origin/develop` **`0ffb7c0`** 에 머지 완료(`5278d21`).
> **이 문서의 JSON 은 실제 DTO 에서 옮긴 것입니다.** 추측한 필드가 없습니다.
>
> ⚠️ **코드는 머지됐지만 운영 DB 마이그레이션은 적용 전입니다**(10장). `Docs/Erd/migrations/2026-09-10-admin-master-and-rules.sql` 을 **코드 배포보다 먼저** 적용해야 합니다 — `ddl-auto=validate` 라 순서가 바뀌면 서버가 기동하지 않습니다.
> ⚠️ **이 문서에 빠진 엔드포인트가 둘 있습니다** — `GET /api/admin/rules/overview` · `GET /api/admin/rules/history`. 계약은 `김재원 담당 백엔드 API — FE 인수인계.md` **§9-3** 에 있습니다.

---

## 0. 5분 요약

| 영역 | 엔드포인트 | 관리자가 할 수 있는 일 |
|---|---|---|
| 차량 모델 | `/api/admin/vehicle-models` | 등록 · 수정 · 비활성화 · 재활성화 |
| 부품 코드 | `/api/admin/part-codes` | 등록 · 수정 · 비활성화 · 재활성화 |
| 부품명 매핑 | `/api/admin/part-name-mappings` | 등록 · 대상 변경 · **삭제** |
| 수리 방식·손상 유형 코드 | `/api/admin/repair-codes` | **표시명·순서·활성 상태만** |
| 심각도 → 수리 방식 규칙 | `/api/admin/repair-method-rules` | 등록 · 수정 · 비활성화 · 재활성화 |
| 이상 탐지 임계값 | `/api/admin/estimate-validation-rules` | 조회 · **새 버전 생성** |
| 변경 이력 | `/api/admin/audit-logs` | **조회만** |

### 먼저 알아야 할 일곱 가지

1. **모두 `ROLE_ADMIN` 전용입니다.** 비로그인 401, 일반 사용자 403.
2. **관리자 ID 를 보내지 마세요.** 어떤 요청 DTO 에도 actor 필드가 없고, 있어도 무시됩니다. 이력의 행위자는 세션에서 옵니다.
3. **식별 코드는 바꿀 수 없습니다.** `partCode`·`rawName`·`repairCode` 는 만들 때 정해지고 그 뒤로 불변입니다.
4. **마스터 코드는 지울 수 없습니다.** 비활성화만 됩니다. 예외는 부품명 매핑(별칭)뿐입니다.
5. **수정에는 `version` 이 필요합니다.** 조회 응답의 값을 그대로 실어 보내세요. 안 맞으면 409입니다.
6. **⚠️ 부품명 매핑은 CSV 명세와 계약이 다릅니다.** `rawName` 이 경로 변수가 아니라 **쿼리 파라미터**이고, 등록·수정·삭제에 **`changeReason` 이 필수**입니다. 실제 데이터에서 경로 변수가 동작하지 않기 때문입니다 — 이유와 근거 수치는 4-2·4-3 에 있습니다.
7. **같은 상태를 다시 요청하면 아무 일도 일어나지 않습니다.** 이미 비활성인 것을 또 끄면 200 이지만 `version` 도 오르지 않고 이력도 생기지 않습니다 (1-6).

---

## 1. 공통 규약

### 1-1. 응답 봉투

성공은 `{ "data": ... }`, 실패는 `{ "error": { "code", "message" } }` 입니다. 기존 API 와 같습니다.

⚠️ **401 만 이 봉투가 아닙니다.** 본문 없이 상태 코드만 옵니다 — `status === 401` 로만 판정하세요.

### 1-2. 목록 봉투 — 관리자 API 는 하나로 통일했습니다

기존 API 는 배열 키가 `accidents`·`content`·`vehicles` 로 제각각이고 `hasNext` 유무도 갈립니다.
**관리자 API 는 전부 아래 한 가지 모양**이라 어댑터를 한 번만 만들면 됩니다.

```jsonc
{
  "data": {
    "content": [ /* ... */ ],
    "page": 0,
    "size": 20,
    "totalElements": 137,
    "totalPages": 7,
    "hasNext": true
  }
}
```

**예외 하나** — `GET /api/admin/repair-codes` 는 행이 8개로 고정이라 페이지네이션하지 않고 배열을 바로 줍니다.

### 1-3. 목록 공통 파라미터

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `page` | `0` | 0부터. 음수는 0으로 보정 |
| `size` | `20` | **최대 200.** 넘기면 200으로 깎입니다 |
| `sort` | 목록마다 다름 | `필드명,asc` 또는 `필드명,desc`. **허용 목록 밖이면 조용히 기본 정렬로 되돌아갑니다** |
| `active` | (없음) | `true`/`false`. **생략하면 비활성 항목도 나옵니다** — 관리자 목록의 기본값입니다 |

정렬 허용 필드는 각 절에 적어 두었습니다.

### 1-4. 오류 코드

**`error.code` 로 분기하세요.** 한글 메시지를 문자열 비교하면 문구가 바뀔 때 조용히 깨집니다.

| `error.code` | HTTP | 언제 | 화면이 할 일 |
|---|:---:|---|---|
| `ADMIN_TARGET_NOT_FOUND` | 404 | 대상이 없음 | "없는 항목입니다" |
| `DUPLICATE_VEHICLE_MODEL` | 409 | 제조사+차량명 중복 | 입력을 고치게 안내 |
| `DUPLICATE_PART_CODE` | 409 | 부품 코드 중복 | 위와 같음 |
| `DUPLICATE_PART_NAME_MAPPING` | 409 | 원문 부품명 중복 | 위와 같음 |
| `VERSION_CONFLICT` | 409 | **다른 관리자가 먼저 바꿈** | **다시 조회한 뒤 재시도**하도록 안내 |
| `CODE_IN_USE` | 409 | 다른 데이터가 참조 중 | 삭제 불가 안내 |
| `REFERENCED_BY_ACTIVE_RULE` | 409 | 활성 규칙이 이 코드를 씀 | "규칙을 먼저 바꿔 주세요" |
| `NORMALIZED_NAME_CONFLICT` | 409 | 정규화하면 다른 부품 별칭과 겹침 | 메시지에 충돌 상대가 들어 있습니다 |
| `OVERLAPPING_RULE_RANGE` | 409 | 심각도 구간이 겹침 | 메시지에 겹치는 규칙 ID |
| `LAST_ACTIVE_RULE` | 409 | 마지막 활성 규칙을 끄려 함 | "대체 규칙을 먼저 등록해 주세요" |
| `INACTIVE_CODE` | 400 | 비활성 코드를 대상으로 씀 | "먼저 활성화해 주세요" |
| `UNKNOWN_CODE` | 400 | canonical 목록에 없는 코드 | (FE 버그입니다) |
| `INVALID_SEVERITY_RANGE` | 400 | 하한 ≥ 상한 | 입력 검증 |
| `INVALID_RULE_VALUE` | 400 | 임계값 교차 관계 위반 | 입력 검증 |
| `INVALID_REQUEST` | 400 | Bean Validation 실패, **필수 쿼리 파라미터 누락**, 공백 `changeReason` | `message` 를 그대로 노출 가능 |
| `CONFLICT` | 409 | 위 어느 것으로도 분류되지 않은 DB 제약 위반(경쟁 조건) | **다시 조회한 뒤 재시도**하도록 안내 |
| `FORBIDDEN` | 403 | 관리자가 아님 | 접근 차단 |

⚠️ **`error.code` 를 좁은 유니온 타입으로 잡지 마세요.** 위 값들은 공통 `ErrorCode` 열거형 밖의 이름이고 늘어날 수 있습니다. `string` 으로 받고 모르는 코드는 `message` 를 보여주세요.

### 1-5. 동시 수정 — `version`

**조회 → 수정 사이에 다른 관리자가 먼저 저장하면 나중 저장이 앞의 변경을 조용히 덮습니다.** 그걸 막는 장치입니다.

```
1. GET  → 응답에 version: 3
2. PATCH → 본문에 version: 3 을 실어 보냄
3. 그 사이 남이 바꿨으면 → 409 VERSION_CONFLICT (현재 version 이 메시지에 있음)
4. 다시 GET 해서 최신 값을 보여주고 사용자가 재시도
```

- **수정과 상태 변경 둘 다** `version` 이 필요합니다.
- 성공하면 응답의 `version` 이 올라갑니다. 그 값을 다음 요청에 쓰세요.
- **부품명 매핑에는 `version` 이 없습니다** — 바꿀 수 있는 필드가 대상 코드 하나뿐이라 잃을 것이 없습니다.
- 이상 탐지 규칙은 `version` 대신 `baseVersion` 을 씁니다(7장).

### 1-6. 상태 변경은 멱등입니다

이미 비활성인 것을 또 `{"isActive": false}` 로 요청하면:

- **200** 이고 현재 상태가 그대로 돌아옵니다 (409 가 아닙니다)
- **`version` 이 오르지 않습니다** — 다른 관리자의 화면이 괜히 버전 충돌을 만나지 않습니다
- **이력이 생기지 않습니다** — `DEACTIVATE` 가 두 번 남으면 두 번째는 거짓이 되고, 언제 실제로 꺼졌는지 찾을 수 없게 됩니다

또 참조 검사도 건너뜁니다. 이미 꺼져 있는 부품 코드를 다시 끄려 할 때 `REFERENCED_BY_ACTIVE_RULE` 로 막히지 않습니다 — 상태가 바뀌지 않으니 막을 이유가 없습니다.

차량 모델·부품 코드·수리 코드·수리 방식 규칙 네 곳 모두 같은 규칙입니다. 버튼 연타나 재시도에 안전합니다.

---

## 2. 차량 모델

```http
GET   /api/admin/vehicle-models?keyword=&manufacturer=&vehicleType=&carClass=&active=&page=&size=&sort=
GET   /api/admin/vehicle-models/{modelId}
POST  /api/admin/vehicle-models
PATCH /api/admin/vehicle-models/{modelId}
PATCH /api/admin/vehicle-models/{modelId}/status
```

정렬 허용: `manufacturer` · `modelName` · `vehicleType` · `carClass` · `modelId` (기본 `manufacturer, modelName` 오름차순)
`keyword` 는 제조사·차량명 부분 일치(대소문자 무시), `manufacturer` 는 완전 일치입니다.
`vehicleType`(`SEDAN`·`SUV`·`VAN`·`TRUCK`)과 `carClass` 로 거를 수 있고 둘은 AND 입니다. **`carClass` 는 응답 JSON 과 같은 코드값**(`CityCar`·`Compact`·`Mid-size`·`Full-size`)으로 보내세요 — 목록에서 받은 값을 그대로 필터에 되돌려 보내면 됩니다. 모르는 값은 400 `INVALID_REQUEST` 입니다. *(2026-09-11 추가, prompt53)*

**응답**

```jsonc
{
  "data": {
    "modelId": 12,
    "manufacturer": "기아",
    "modelName": "K5",
    "vehicleType": "SEDAN",
    "carClass": "Mid-size",
    "active": true,
    "version": 0
  }
}
```

**등록** — `POST`, 201

```json
{ "manufacturer": "기아", "modelName": "K5", "vehicleType": "SEDAN", "carClass": "Mid-size" }
```

**수정** — `PATCH`, 200. `modelId` 는 요청에 없습니다.

```json
{ "manufacturer": "기아", "modelName": "K5", "vehicleType": "SEDAN",
  "carClass": "Mid-size", "version": 0 }
```

**상태 변경** — `PATCH .../status`, 200

```json
{ "active": false, "version": 1 }
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| `manufacturer` | string | ✅ | trim 후 빈 값 금지, 50자 이하 |
| `modelName` | string | ✅ | trim 후 빈 값 금지, 100자 이하 |
| `vehicleType` | enum | ✅ | `SEDAN`·`SUV`·`VAN`·`TRUCK` |
| `carClass` | enum | ✅ | **`CityCar`·`Compact`·`Mid-size`·`Full-size`** — enum 이름이 아니라 코드값입니다(하이픈 주의) |
| `version` | number | 수정·상태변경 ✅ | 조회한 값 |

**규칙**

- `(제조사, 차량명)` 중복은 409 — **비활성 모델과도 겹칠 수 없습니다.**
- 새 모델은 **활성으로 만들어집니다.** 등록 요청에 `active` 가 없습니다.
- **삭제 API 가 없습니다.** 이미 등록된 사용자 차량과 사고 스냅샷이 이 모델을 가리킵니다.
- **비활성화하면** 공개 `GET /api/vehicle-models` 와 신규 차량 등록 후보에서 빠집니다. **이미 등록된 차량과 사고 이력은 그대로 조회됩니다.**
- **관리자 목록에는 비활성 모델도 나옵니다** — 다시 켜야 하니까요. `active=true` 로 걸러 볼 수 있습니다.

---

## 3. 부품 코드

```http
GET   /api/admin/part-codes?keyword=&layoutZone=&scope=&active=&page=&size=&sort=
GET   /api/admin/part-codes/{partCode}
POST  /api/admin/part-codes
PATCH /api/admin/part-codes/{partCode}
PATCH /api/admin/part-codes/{partCode}/status
```

정렬 허용: `partCode` · `nameKo` · `layoutZone` · `displayOrder` (기본 `displayOrder, partCode` 오름차순)

**응답**

```jsonc
{
  "data": {
    "partCode": "FRONT_BUMPER",
    "nameKo": "앞 범퍼",
    "layoutZone": "FRONT",
    "displayOrder": 1,
    "codeScope": "AI_LABEL",
    "active": true,
    "mappingCount": 412,
    "version": 0
  }
}
```

| 필드 | 설명 |
|---|---|
| `codeScope` | `AI_LABEL` = 파손 검출 모델이 출력하는 핵심 라벨(시드 32종) / `EXTENDED` = 견적서 매핑용 확장 코드(시드 24종) |
| `mappingCount` | 이 부품을 가리키는 한글 별칭 수. **비활성화·정리 영향 범위를 화면이 먼저 보여줄 수 있습니다** |

### ★ AI 핵심 32종과 확장 코드 구분

예전에는 두 집합을 가르는 단서가 `displayOrder`(1~32 vs 101~) 뿐이었습니다. **표시 순서일 뿐이라 관리자가 순서를 바꾸면 구분이 무너집니다.** 그래서 `codeScope` 라는 명시적 속성을 추가했습니다.

- 목록에서 `scope=AI_LABEL` 또는 `scope=EXTENDED` 로 거릅니다.
- **등록 시 생략하면 `EXTENDED`** 입니다 — AI 라벨은 모델을 다시 학습해야 늘어나므로 관리자가 추가하는 코드는 사실상 전부 확장 코드입니다.
- 화면에서 두 집합을 구분해 보여주고, AI 라벨 추가는 막거나 경고를 띄우시는 편이 좋습니다.

**등록** — `POST`, 201

```json
{ "partCode": "SIDE_STEP_L", "nameKo": "사이드스텝(좌)", "layoutZone": "SIDE_L",
  "displayOrder": 109, "codeScope": "EXTENDED" }
```

**수정** — `PATCH`, 200. **`partCode` 는 요청에 없습니다.**

```json
{ "nameKo": "사이드스텝(좌)", "layoutZone": "SIDE_L", "displayOrder": 109,
  "codeScope": "EXTENDED", "version": 0 }
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| `partCode` | string | 등록 ✅ | **대문자로 시작, 대문자·숫자·밑줄만**, 50자 이하. `^[A-Z][A-Z0-9_]*$` |
| `nameKo` | string | ✅ | trim 후 빈 값 금지, 50자 이하 |
| `layoutZone` | string | ✅ | `FRONT`·`REAR`·`SIDE_L`·`SIDE_R`·`TOP`·`UNDER` |
| `displayOrder` | number | ✅ | 0 ~ 32767. **중복을 허용합니다** — 같은 순서면 부품 코드 오름차순으로 안정 정렬됩니다 |
| `codeScope` | enum | 수정 ✅ / 등록 ⬜ | `AI_LABEL`·`EXTENDED` |

**규칙**

- **`partCode` 는 등록 후 불변입니다.** 과거 분석·견적·통계·별칭이 이 문자열을 그대로 들고 있습니다. 바꿔야 하면 → 새 코드 등록 → 별칭 이전 → 기존 코드 비활성화.
- **삭제 API 가 없습니다.**
- **비활성화하면** 런타임 부품명 사전과 신규 매핑 대상에서 빠집니다. **매핑 행 자체는 남고, 과거 분석·견적·검증 결과의 부품명도 그대로입니다.**
- **활성 수리 방식 규칙이 이 부품을 지목하고 있으면 비활성화가 409** (`REFERENCED_BY_ACTIVE_RULE`) 입니다. 규칙을 먼저 바꿔야 합니다.

---

## 4. 부품명 매핑 (한글 원문 ↔ 부품 코드)

```http
GET    /api/admin/part-name-mappings?keyword=&partCode=&partActive=&scope=&page=&size=&sort=
POST   /api/admin/part-name-mappings
PATCH  /api/admin/part-name-mappings?rawName=...
DELETE /api/admin/part-name-mappings?rawName=...&changeReason=...
```

정렬 허용: `rawName` (기본 오름차순)

> ### ⚠️ CSV 명세와 두 가지가 다릅니다 — 읽고 시작하세요
>
> | | CSV 명세 | 실제 구현 | 왜 |
> |---|---|---|---|
> | 대상 지정 | `PATCH .../{rawName}` | `PATCH ...?rawName=` | 원문에 `/` 가 **346건** 있어 경로로 표현이 불가능합니다 (4-2) |
> | 요청 필드 | `partCode` 만 | `partCode` + **`changeReason` 필수** | 이 표는 두 칸뿐이라 왜 바꿨는지 기록할 곳이 없었습니다 (4-3) |

> **시드만 15,308 건입니다.** 페이지네이션 없이 부르지 마세요. `size` 상한 200 이 걸려 있습니다.

> **이 API 가 다루는 것은 “확정 매핑” 뿐입니다.** 표준화 파이프라인의 7가지 판정 상태 중 DB 에 적재되는 것은 `MAPPED`·`MAPPED_EXTENDED` 두 종류(15,308건)입니다. 나머지 5종(`MAPPED_SIDE_UNKNOWN`, `MAPPED_SPLIT`, `REVIEW_CONFLICT`, `OUT_OF_SCOPE_PART`, `NOT_A_PART`)은 워크북에만 있고 **DB 에 행이 없습니다.** 그래서 목록 응답에 `status` 나 `mappingStatus` 같은 필드가 없습니다 — 없는 값을 지어내지 않았습니다. 미확정 후보 검수 화면이 필요하면 후보 테이블부터 설계해야 하는 별개 작업입니다.

### 4-1. 목록·응답

```jsonc
{
  "data": {
    "rawName": "앞 범퍼(좌) 교환",
    "normalizedName": "앞범퍼좌",
    "partCode": "FRONT_BUMPER",
    "partNameKo": "앞 범퍼",
    "partCodeScope": "AI_LABEL",
    "partActive": true,
    "inDictionary": true
  }
}
```

| 필드 | 설명 |
|---|---|
| `normalizedName` | **런타임 사전의 실제 키**입니다. 중복 검사에 쓰는 비교 키와는 다른 값입니다 (4-4) |
| `partCodeScope` | 대상 부품이 **AI 핵심 32종(`AI_LABEL`)인지 견적 확장 코드(`EXTENDED`)인지.** 목록 파라미터 `scope=AI_LABEL` 로 32종 대상 매핑만 거를 수 있습니다. 다른 값은 400. *(2026-09-11 추가, prompt53)* |
| `partActive` | 대상 부품의 활성 상태 |
| `inDictionary` | **지금 실제로 사전에 올라가는가.** 부품이 비활성이거나 사전 키가 다른 부품과 겹치면 `false` |

#### ★ `inDictionary` 를 반드시 화면에 표시하세요

런타임 사전은 키가 겹치면서 가리키는 부품이 다르면 **두 항목을 모두 버립니다.** 그걸 모르면 "분명히 등록했는데 매핑이 안 된다" 는 상태가 됩니다. `inDictionary: false` 인 행은 경고 표시를 해 주세요.

현재 시드에도 그런 행이 **7건** 있습니다(`도어밸트(눈썹)몰딩(뒤,좌/우) 재사용50%` 계열 2개 키). 버그가 아니라 원문이 실제로 애매해서 생긴 것이고, 화면에서 보이게 만드는 것이 이 필드의 목적입니다.

### 4-2. ★ `rawName` 은 쿼리 파라미터입니다 — 경로에 넣지 마세요

시드 15,308건의 `raw_name` 을 전수 조사한 결과입니다.

| 문자 | 건수 | 경로 변수로 | 쿼리 파라미터로 |
|---|---|---|---|
| `/` | 346 | **불가.** `%2F` 로 인코딩해도 Spring Security `StrictHttpFirewall` 기본값이 거부합니다 | 인코딩하면 OK |
| `%` | 360 | 조건부 (`%25` 필수, 한 번 빠뜨리면 400) | 인코딩하면 OK |
| `#` | 7 | 조건부 (인코딩 안 하면 fragment 로 잘려 서버에 도달조차 안 함) | 인코딩하면 OK |
| `&` | 61 | 문제 없음 | **인코딩 필수** — 안 하면 값이 잘립니다 |
| `+` | 38 | 문제 없음 | **인코딩 필수** — 안 하면 공백으로 디코딩돼 **다른 행이 조회됩니다** |
| `?` | 0 | — | — |

`&` 와 `+` 는 계약을 쿼리로 옮기면서 **새로 생긴** 위험입니다. 숨기지 않고 적어 둡니다.

**FE 가 할 일은 하나입니다.** `encodeURIComponent` 는 위 다섯 문자를 모두 이스케이프합니다.

```js
const q = new URLSearchParams({ rawName, changeReason }).toString();
// 또는 직접:  `?rawName=${encodeURIComponent(rawName)}`
await fetch(`/api/admin/part-name-mappings?${q}`, { method: 'DELETE' });
```

⚠️ **문자열을 직접 이어 붙이지 마세요.** 위 다섯 문자가 각각 다른 방식으로 조용히 깨집니다 — 특히 `+` 는 400 도 409 도 아니라 **엉뚱한 행을 지웁니다.**

`rawName` 을 아예 빼면 400 `INVALID_REQUEST` 입니다 (404 가 아닙니다 — "없다" 와 "안 보냈다" 는 고칠 것이 다릅니다).

> **왜 `mappingId` 같은 대리 키를 안 만들었나** — 그게 더 깔끔하지만 `raw_name` 이 PK 인 테이블의 스키마와 15,308건 시드를 함께 바꿔야 합니다. 이번 범위를 넘어서 후속 항목으로 남겼습니다.

### 4-3. ★ `changeReason` 이 필수입니다

**등록** — `POST`, 201

```json
{ "rawName": "앞 범퍼(좌) 교환", "partCode": "FRONT_BUMPER", "changeReason": "정비소 표기 변형 추가" }
```

**수정** — `PATCH ...?rawName=...`, 200. 대상 코드만 바꿉니다.

```json
{ "partCode": "REAR_BUMPER", "changeReason": "앞/뒤 오분류 정정" }
```

**삭제** — `DELETE ...?rawName=...&changeReason=...`, 204 (본문 없음)

| 규칙 | 값 |
|---|---|
| 필수 | 예. 세 경로(등록·수정·삭제) 모두 |
| 길이 | 1–500자. 앞뒤 공백은 서버가 제거합니다 |
| 공백만 | 400 `INVALID_REQUEST` — `"   "` 는 사유가 아닙니다 |

**왜 필수인가** — 매핑을 바꾸면 **앞으로 처리하는** 분석의 부품 귀속이 달라지지만 **이미 저장된 `repair_case_item.part_code` 는 그대로**입니다. 나중에 과거 데이터와 현재 매핑이 다른 이유를 알 수 있는 곳은 이력뿐입니다. `(raw_name, part_code)` 두 칸짜리 행에는 그 정보를 담을 데가 없습니다.

사유는 `/api/admin/audit-logs` 응답의 `changeReason` 으로 되돌려 받습니다 (8장).

> **차량 모델·부품 코드 등 다른 대상은 `changeReason` 을 받지 않습니다.** 필수로 만드는 결정은 대상마다 따로 내려야 하고, 지금 근거가 확인된 것은 매핑뿐입니다. 이력 컬럼은 nullable 이라 `null` 이면 "받지 않았다", 빈 문자열이면 "빈 값을 받았다" 로 구분됩니다.

### 4-4. ★ 중복 3종 — 셋 다 409 지만 `error.code` 로 갈립니다

중복 판정은 표준화 파이프라인의 `compact()` 와 **같은 비교 키**를 씁니다. `normalizedName`(런타임 사전 키)과는 **다른 규칙**입니다.

| 비교 키 | 무엇을 지우나 | 어디에 쓰나 |
|---|---|---|
| **비교 키** (`compact()`) | NFKC · 소문자 · `공백 _ - － · . , /` 제거 | 중복 판정 · 시드 생성 · 파이프라인 |
| **사전 키** (`normalizedName`) | NFKC · 소문자 · 괄호 · 공백 · 작업 접미사(`교환` 등) 제거 · `왼쪽→좌` | 런타임 조회 |

시드 15,308건에 둘을 돌리면 **79.4%(12,162건)가 다른 결과**를 냅니다. 버그가 아니라 역할이 다릅니다.

| 상황 | 결과 |
|---|---|
| **정확히 같은** `rawName` | 409 `DUPLICATE_PART_NAME_MAPPING` |
| 비교 키가 같고 **같은 부품** | 409 `DUPLICATE_PART_NAME_MAPPING` — 넣어도 매핑 결과가 안 바뀝니다 |
| 비교 키가 같고 **다른 부품** | 409 `NORMALIZED_NAME_CONFLICT` — 메시지에 충돌 상대의 원문과 코드가 있습니다 |
| 사전 키만 겹침 (`… 교환` 등) | **등록됩니다.** 대신 `inDictionary: false` 가 됩니다 |
| 대상 부품이 없음 | 404 `ADMIN_TARGET_NOT_FOUND` |
| 대상 부품이 **비활성** | 400 `INACTIVE_CODE` |
| 없는 `rawName` 수정·삭제 | 404 `ADMIN_TARGET_NOT_FOUND` |
| 같은 원문 **동시 등록** | 한 건만 201, 나머지 409 `DUPLICATE_PART_NAME_MAPPING` |

**수정은 등록보다 관대합니다.** `rawName` 은 그대로이고 대상만 바뀌므로 기준이 "모호성을 **넓히는가**" 입니다. 시드에는 비교 키가 겹치는 원문이 1,950개 키(5,614행)에 이미 들어 있어서, 등록 규칙을 그대로 쓰면 그 행들을 **영구히 고칠 수 없게** 됩니다.

| 같은 키 묶음의 부품 | 변경 후 | 판정 |
|---|---|---|
| `{A}` | `{B}` | 200 — 혼자인 행의 대상 변경 |
| `{A, B}` | `{B}` | 200 — 갈라져 있던 것을 합침 |
| `{A}` | `{A, B}` | 409 — 없던 모호성을 만듦 |
| `{A, C}` | `{B, C}` | 200 — 이미 모호했고 더 나빠지지 않음 |

### 4-5. 그 밖의 규칙

- **`rawName` 은 바꿀 수 없습니다.** 원문을 바꾸는 것은 다른 별칭을 만드는 일이므로 **삭제 후 재등록**입니다.
- **매핑은 삭제할 수 있습니다.** 마스터 코드가 아니라 별칭이라 지워도 과거 데이터의 의미가 바뀌지 않습니다.
- **변경은 다음 검증부터 즉시 반영됩니다.** 사전이 매번 DB 를 읽으므로 캐시 무효화가 없습니다.
- **과거 데이터는 자동으로 바뀌지 않습니다.** 이미 적재된 `repair_case_item.part_code`, 과거 분석·견적·비용 통계는 그대로입니다. 소급 반영은 별도 재적재 작업이 필요합니다.
- **부품 코드를 비활성화해도 매핑 행은 보존됩니다.** 사전과 신규 등록 대상에서만 빠집니다 — 목록에서 `partActive: false` 로 보입니다.
- 매핑 행에는 `version` 이 없습니다. 바꿀 수 있는 필드가 `partCode` 하나뿐이라 마지막 값이 곧 관리자의 의도이고, 무엇을 잃었는지는 이력의 before/after 에 남습니다.

⚠️ **시드 재적재 주의** — `A307_part_name_mapping_seed.sql` 을 다시 돌리면 운영에서 지운 별칭이 되살아납니다. 시드는 신규 설치 전용입니다.

---

## 5. 수리 방식·손상 유형 코드 (표시층)

```http
GET   /api/admin/repair-codes?codeType=
PATCH /api/admin/repair-codes/{codeType}/{code}
PATCH /api/admin/repair-codes/{codeType}/{code}/status
```

`codeType` 은 `REPAIR_METHOD` 또는 `DAMAGE_TYPE` 입니다. 생략하면 8행 전부 옵니다.

**응답** — 페이지네이션 없이 배열입니다.

```jsonc
{
  "data": [
    { "codeType": "REPAIR_METHOD", "code": "exchange", "displayName": "교환",
      "displayOrder": 1, "active": true, "version": 0 },
    { "codeType": "REPAIR_METHOD", "code": "sheet_metal", "displayName": "판금",
      "displayOrder": 2, "active": true, "version": 0 }
  ]
}
```

### ★ 등록·삭제 API 가 없습니다 — 의도적인 제한입니다

수리 방식 4종(`exchange`·`sheet_metal`·`coating`·`repair`)과 손상 유형 4종(`Scratched`·`Separated`·`Crushed`·`Breakage`)은 **서버 계산 로직과 DB 제약이 같은 문자열을 공유**합니다. 관리자가 행만 추가하면 DB 에는 늘어나는데 계산은 그 값을 모릅니다 — 조용히 죽은 코드가 됩니다.

**관리자가 바꿀 수 있는 것은 `displayName` · `displayOrder` · `active` 뿐입니다.**
화면에 "코드 추가" 버튼을 만들지 마세요. 새 코드가 필요하면 백엔드 작업입니다.

**수정** — `PATCH`, 200

```json
{ "displayName": "교체", "displayOrder": 1, "version": 0 }
```

**상태 변경** — `PATCH .../status`, 200. 본문은 2장과 같습니다.

- **활성 수리 방식 규칙이 쓰고 있는 코드는 끌 수 없습니다** — 409 `REFERENCED_BY_ACTIVE_RULE`.

---

## 6. 심각도 → 수리 방식 규칙

```http
GET   /api/admin/repair-method-rules?active=&page=&size=&sort=
GET   /api/admin/repair-method-rules/{ruleId}
POST  /api/admin/repair-method-rules
PATCH /api/admin/repair-method-rules/{ruleId}
PATCH /api/admin/repair-method-rules/{ruleId}/status
```

정렬 허용: `ruleId` · `damageType` · `priority` · `severityMin` · `createdAt`
(기본 `damageType` 오름차순 → `priority` 내림차순 → `severityMin` 오름차순)

> ⚠️ **아직 이 규칙을 읽는 분석 파이프라인이 없습니다.** 결정 로직과 조회 계약은 구현돼 있고 통합 테스트가 지키지만, 손상 부위를 만드는 파손 검출 코드가 이 저장소에 없어 **운영 호출 경로는 비어 있습니다.** 관리 화면은 지금 만들 수 있고, 실제 견적에 반영되는 시점은 분석 기능이 붙은 뒤입니다.

**응답**

```jsonc
{
  "data": {
    "ruleId": 3,
    "damageType": "Scratched",
    "partCode": null,
    "severityMin": 0.00,
    "severityMax": 30.00,
    "maxInclusive": false,
    "repairMethod": "coating",
    "priority": 0,
    "active": true,
    "version": 0,
    "createdAt": "2026-09-10T05:00:00Z",
    "updatedAt": "2026-09-10T05:00:00Z"
  }
}
```

**등록** — `POST`, 201

```json
{ "damageType": "Scratched", "partCode": null,
  "severityMin": 0.00, "severityMax": 30.00, "maxInclusive": false,
  "repairMethod": "coating", "priority": 0 }
```

**수정** — `PATCH`, 200. **`damageType`·`partCode` 는 요청에 없습니다.**

```json
{ "severityMin": 0.00, "severityMax": 40.00, "maxInclusive": false,
  "repairMethod": "coating", "priority": 0, "version": 0 }
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| `damageType` | string | 등록 ✅ | `repair-codes` 의 `DAMAGE_TYPE` 중 **활성인 것** |
| `partCode` | string \| null | ⬜ | `null` 이면 그 손상 유형 **전체에 적용되는 기본 규칙**, 값이 있으면 그 부품 전용 예외 규칙 |
| `severityMin` / `severityMax` | number | ✅ | 0.00 ~ 9999.99, 소수점 2자리. **하한 < 상한** |
| `maxInclusive` | boolean | ✅ | `false` 면 `[min, max)`, `true` 면 `[min, max]` |
| `repairMethod` | string | ✅ | `repair-codes` 의 `REPAIR_METHOD` 중 **활성인 것** |
| `priority` | number | ✅ | 0 ~ 32767. 함께 걸리면 **큰 쪽이 이깁니다** |

### ★ 심각도 범위를 서버가 정해 주지 않습니다

`severity_score` 의 실제 값 범위(0~1인지 0~100인지)가 **아직 확정되지 않았습니다.** 그 값을 만드는 코드가 없어서 단정할 근거가 없습니다. 서버는 "하한 < 상한" 과 컬럼 한계(0~9999.99)만 강제합니다.

→ **화면에 범위 안내를 하드코딩하지 마세요.** 관리자가 정하는 값입니다.

### ★ 경계 규칙 — 구멍도 겹침도 없게

```
규칙1  [0, 30)   coating       maxInclusive: false
규칙2  [30, 100] sheet_metal   maxInclusive: true   ← 마지막만 닫는다
```

- 기본이 **열린 상한**이라 이웃 구간과 경계값이 겹치지 않습니다.
- **마지막 구간만** `maxInclusive: true` 로 닫아야 최대값이 어느 규칙에도 안 잡히는 구멍이 사라집니다.
- 같은 적용 범위(같은 `damageType` + 같은 `partCode`)에서 **활성 구간이 겹치면 409** `OVERLAPPING_RULE_RANGE` 입니다. 메시지에 겹치는 규칙 ID 와 구간이 들어 있습니다.
- **적용 범위가 다르면 겹쳐도 됩니다.**

**그 밖의 규칙**

- **마지막 활성 규칙은 끌 수 없습니다** — 409 `LAST_ACTIVE_RULE`. 대체 규칙을 먼저 등록하세요.
- 다시 켤 때도 겹침을 검사합니다 — 꺼져 있는 동안 그 구간을 덮는 규칙이 생겼을 수 있습니다.
- **다시 켤 때 손상 유형·수리 방식·부품 코드가 활성인지도 검사합니다** — 400 `INACTIVE_CODE`. 규칙을 꺼 둔 사이 코드가 꺼졌다면 코드를 먼저 켜야 규칙을 켤 수 있습니다. *(2026-09-11 추가, prompt53)*
- **적용 대상은 수정할 수 없습니다.** 바꾸려면 기존 규칙을 끄고 새로 등록하세요.
- 변경은 **다음 결정부터 즉시 반영**되고 **이미 만들어진 결과는 바뀌지 않습니다.**

---

## 7. 견적서 이상 탐지 임계값

```http
GET   /api/admin/estimate-validation-rules/current
GET   /api/admin/estimate-validation-rules/history?page=&size=&sort=
PATCH /api/admin/estimate-validation-rules/current
```

정렬 허용: `ruleVersion` · `createdAt` (기본 `ruleVersion` 내림차순)

**응답**

```jsonc
{
  "data": {
    "ruleVersion": 1,
    "referencePercentile": 75,
    "severeOverP75Multiplier": 1.50,
    "cautionTotalDifferenceRatio": 0.1000,
    "needsReviewTotalDifferenceRatio": 0.2000,
    "needsReviewItemCount": 3,
    "changedBy": null,
    "changeNote": "application.properties 배포 기본값 이관",
    "createdAt": "2026-09-10T00:00:00Z"
  }
}
```

### ★ `PATCH` 지만 새 버전을 만듭니다

기존 값을 고치지 않습니다. **과거 검증이 어떤 기준으로 판정됐는지 되짚을 수 있어야** 하기 때문입니다.

```
1. GET /current  →  ruleVersion: 1
2. PATCH /current  { ..., "baseVersion": 1 }
3. 응답  →  ruleVersion: 2  (즉시 현재 규칙이 됩니다)
4. GET /history  →  버전 2, 1 이 모두 남아 있습니다
```

- **`version` 이 아니라 `baseVersion`** 입니다. 조회한 `ruleVersion` 을 그대로 보내세요.
- 그 사이 다른 관리자가 새 버전을 만들었으면 409 `VERSION_CONFLICT`.
- **변경은 다음 검증부터 즉시 적용**되고 **이미 끝난 검증 결과는 바뀌지 않습니다.**

**수정 요청**

```json
{ "referencePercentile": 75,
  "severeOverP75Multiplier": 1.50,
  "cautionTotalDifferenceRatio": 0.10,
  "needsReviewTotalDifferenceRatio": 0.20,
  "needsReviewItemCount": 3,
  "changeNote": "3분기 기준 조정",
  "baseVersion": 1 }
```

| 필드 | 타입 | 필수 | 범위 | 의미 |
|---|---|:---:|---|---|
| `referencePercentile` | number | ✅ | 1 ~ 100 | ⚠️ **현재 판정에 쓰이지 않습니다**(아래 참조) |
| `severeOverP75Multiplier` | number | ✅ | **1.0 초과** | 항목 금액이 기준 P75 의 몇 배를 넘으면 "확인 필요" |
| `cautionTotalDifferenceRatio` | number | ✅ | 0 이상 | 총액 차이 비율이 이 값 이상이면 "주의" |
| `needsReviewTotalDifferenceRatio` | number | ✅ | **`caution` 이상** | 이 값 이상이면 "확인 필요" |
| `needsReviewItemCount` | number | ✅ | 1 이상 | 확인 권장 항목이 이 개수 이상이면 "확인 필요" |
| `changeNote` | string | ⬜ | 200자 이하 | 변경 사유. 이력에 남습니다 |
| `baseVersion` | number | ✅ | 1 이상 | 조회한 `ruleVersion` |

⚠️ **`referencePercentile` 은 현재 소비되지 않습니다.** 검증 엔진이 기준값 테이블의 P75 컬럼을 직접 읽고 있어 이 값이 쓰이는 지점이 없습니다. 값은 보존되지만 **화면에서 "적용 중"이라고 안내하면 안 됩니다.** 후속 작업으로 남겼습니다.

**등급 판정식** (참고 — FE 가 재계산하면 안 됩니다)

```
확인 필요 : 확인권장항목수 >= needsReviewItemCount
         또는 최고 P75 배수 >= severeOverP75Multiplier
         또는 총액차이비율 >= needsReviewTotalDifferenceRatio
주의     : 확인권장항목수 > 0
         또는 총액차이비율 >= cautionTotalDifferenceRatio
적정 범위 : 나머지
```

**잘못된 값은 저장되지 않고 이력도 남지 않습니다.** 교차 관계(`needsReview >= caution`)가 깨지면 400 `INVALID_RULE_VALUE` 입니다.

---

## 8. 변경 이력

```http
GET /api/admin/audit-logs?from=&to=&actorMemberId=&actionType=&targetType=&targetId=&page=&size=&sort=
```

정렬 허용: `createdAt` · `auditLogId` (기본 `createdAt` 내림차순)

**응답**

```jsonc
{
  "data": {
    "content": [
      {
        "auditLogId": 41,
        "actorMemberId": 7,
        "actionType": "DEACTIVATE",
        "targetType": "PART_CODE",
        "targetId": "SIDE_STEP_L",
        "requestId": null,
        "beforeData": "{\"partCode\":\"SIDE_STEP_L\",\"active\":true,\"version\":0}",
        "afterData":  "{\"partCode\":\"SIDE_STEP_L\",\"active\":false,\"version\":1}",
        "changeReason": null,
        "ipAddress": "127.0.0.1",
        "createdAt": "2026-09-10T05:12:00Z"
      }
    ],
    "page": 0, "size": 20, "totalElements": 41, "totalPages": 3, "hasNext": true
  }
}
```

| 필드 | 값 |
|---|---|
| `actionType` | `CREATE` · `UPDATE` · `DEACTIVATE` · `ACTIVATE` · `DELETE` |
| `targetType` | `VEHICLE_MODEL` · `PART_CODE` · `PART_NAME_MAPPING` · `REPAIR_CODE` · `REPAIR_METHOD_RULE` · `ESTIMATE_VALIDATION_RULE` |
| `targetId` | 대상 식별자. 숫자 PK 도 문자열로 옵니다. `REPAIR_CODE` 는 `"REPAIR_METHOD:exchange"` 형태 |
| `beforeData` / `afterData` | **JSON 문자열입니다** — 객체가 아닙니다. 화면에서 파싱해 diff 로 보여주세요. `CREATE` 면 before 가 `null`, `DELETE` 면 after 가 `null` |
| `actorMemberId` | 탈퇴하면 `null` 이 됩니다. 이력 행 자체는 남습니다 |
| `changeReason` | 관리자가 적은 사유. **`PART_NAME_MAPPING` 대상만 항상 채워집니다** (등록·수정·삭제 필수). 다른 대상은 아직 사유를 받지 않으므로 `null` 입니다 — `null`(안 받음)과 `""`(빈 값을 받음)은 다릅니다 |
| `requestId` | 요청에 `X-Request-Id` 헤더가 있었을 때만. 보통 `null` |
| `ipAddress` | 서버가 본 접속 IP. **리버스 프록시 뒤에서는 프록시 IP 입니다** — `X-Forwarded-For` 는 위조 가능해서 읽지 않습니다 |

**기간 파라미터**

- `from`·`to` 는 `2026-09-10T00:00:00Z` 형식입니다.
- **`from` 은 이상(포함), `to` 는 미만(제외)** 입니다 — 하루치를 볼 때 다음 날 00:00 을 주면 됩니다.

**규칙**

- **조회만 가능합니다.** 수정·삭제 API 가 없습니다.
- **`beforeData`·`afterData` 로 검색할 수 없습니다.** 필터는 기간·행위자·행위·대상뿐입니다.
- **변경이 롤백되면 이력도 함께 롤백됩니다** — 같은 트랜잭션입니다. 실패한 변경은 이력에 남지 않습니다.
- 비밀번호·세션·토큰·API 키·파일 내용은 기록되지 않습니다.

---

## 9. 붙일 때 순서 (권장)

1. **`GET /api/admin/repair-codes` 부터** — 행이 8개라 가장 단순하고, 다른 화면의 코드 드롭다운 데이터가 됩니다.
2. **차량 모델** — 목록·등록·수정·상태 전환의 기본형입니다. `version` 흐름을 여기서 잡으세요.
3. **부품 코드** — 위와 같은 형태에 `codeScope` 필터가 붙습니다.
4. **부품명 매핑** — 페이지네이션과 `encodeURIComponent` 가 필요한 유일한 화면입니다. **계약이 CSV 명세와 다르니 4장을 먼저 읽으세요** — `rawName` 은 쿼리 파라미터이고 `changeReason` 이 필수입니다.
5. **이상 탐지 임계값** — 폼 하나 + 이력 목록.
6. **수리 방식 규칙** — 구간 겹침 UI 가 있어 가장 복잡합니다. 마지막에.
7. **변경 이력** — 위 화면들이 데이터를 만든 뒤에 붙이면 확인이 쉽습니다.

**공통 유틸을 먼저 만드세요**

- `version` 을 들고 다니는 폼 상태 훅
- `error.code` → 화면 문구 매핑 (1-4 표)
- 409 `VERSION_CONFLICT` 를 만나면 자동 재조회 + "다른 관리자가 먼저 변경했습니다" 안내

---

## 10. 아직 안 되는 것 / 제한

| 항목 | 상태 |
|---|---|
| **수리 방식 규칙의 실제 견적 반영** | ⚠️ 규칙 관리와 결정 로직은 완성. **읽는 분석 파이프라인이 없어 운영 경로는 비어 있음** |
| **`referencePercentile` 적용** | ⚠️ 저장은 되지만 판정에 쓰이지 않음. 화면에서 "적용 중"이라고 하지 말 것 |
| **canonical code 추가** | ❌ 불가. 표시명·순서·활성 상태만 |
| **마스터 코드 삭제** | ❌ 불가. 비활성화만 |
| **`partCode`·`rawName`·`repairCode` 변경** | ❌ 불가 |
| **관리자 계정 생성·권한 부여** | ❌ 이번 범위 밖. DB 에서 직접 `member.role = 'ADMIN'` |
| **감사 로그 수정·삭제** | ❌ 없음(의도) |
| **비교 키 중복의 동시성 방어** | ⚠️ **DB 제약이 없습니다.** 정확히 같은 `rawName` 은 PK 가 원자적으로 막지만, 비교 키가 같은 **다른 원문** 두 건이 동시에 들어오면 둘 다 저장될 수 있습니다. 기존 15,308건에 이미 비교 키 중복이 1,950개 키(5,614행) 있어 UNIQUE 제약을 걸 수 없습니다(걸려면 데이터를 지우거나 병합해야 함). 그런 행은 `inDictionary: false` 로 드러납니다 |
| **`mapping_rule_version`** | ❌ 없음. 전역 규칙 버전의 발급·활성화 계약이 정본 문서에 없어 임의 숫자를 만들지 않았습니다. 지금은 감사 이력만 남습니다 |
| **미확정 5종 후보 검수** | ❌ DB 에 후보 행이 없어 불가. 후보 테이블·상태 전이·승인 이력이 필요한 별개 작업 |
| **`changeReason` (매핑 이외 대상)** | ❌ 안 받습니다. 대상마다 따로 결정할 사안이라 근거가 확인된 매핑만 적용 |
| **운영 DB 마이그레이션** | ⚠️ **미적용.** 적용 전에는 서버가 기동하지 않습니다 |

---

*작성 2026-09-10 · 2026-09-11 갱신 — `origin/develop` `0ffb7c0` 머지 완료(`5278d21`, 브랜치 `feature/S15P21A307-362-be-admin-master-and-rules`). 계약 변경 없음*
