# 사고 조회 API — FE 인수인계

> **대상 백로그** `[BE] 사고 이력 목록 조회 API 구현`(S15P21A307-225) · 상위 스토리 `[BE] 회원 사고 이력 조회 기능 구현`(224)
> **연관 FE 백로그** `[FE] 마이페이지 사고 이력 목록 UI 및 상세 진입 연동 구현`(S15P21A307-230)
> **서버 상태** 이 브랜치(`feature/S15P21A307-225-accident-query`)에서 구현. `develop` 머지 후 사용 가능
> **작성 기준** `origin/develop` `41b87e1` 기준 · 2026-09-08 · H2 테스트 218개 통과
> **근거** API 명세서 CSV 27·37행 · `A307_ddl_final.sql` · 와이어프레임 `마이페이지_견적이력.png` · 코드 직접 확인

---

## ⚠️ 시작하기 전에 — 두 가지를 먼저 알아야 한다

### 1. 지금은 로그인해도 **500**이 난다 (이 API의 결함이 아니다)

`develop`에 소셜 로그인이 들어와 있지만 **회원 ID를 꺼내는 지점이 아직 스텁**이다.

```java
// common/security/CurrentMemberProvider.java — develop 현재 상태
public Long currentMemberId() {
    throw new UnsupportedOperationException("인증 연동 전 — 세션 로그인 구현 후 교체 (S15P21A307-auth)");
}
```

`AccidentController`의 모든 핸들러가 첫 줄에서 이 메서드를 부르므로, **정상 로그인 세션으로 호출해도** 이렇게 온다.

```json
HTTP 500
{ "error": { "code": "INTERNAL_ERROR", "message": "서버 내부 오류가 발생했습니다." } }
```

`estimate-validations`·`vehicles`·`accidents` 컨트롤러가 모두 같은 상태다. **이 클래스 본문만 교체되면 API 계약을 하나도 바꾸지 않고 그대로 동작한다.** 아래 응답 예시는 그 교체 이후를 전제로 한다.

### 2. ★ 이 API만으로는 `마이페이지_견적이력` 화면을 완성할 수 없다

와이어프레임이 요구하는 것과 이 API가 주는 것이 다르다. **화면을 설계하기 전에 반드시 읽을 것.**

| 와이어프레임 항목 | 이 API가 주는가 | 설명 |
|---|:---:|---|
| **사진**(썸네일) | ❌ | 이미지가 응답에 없다. `variant='BLURRED'` 생성 기능이 아직 없어 지금 내보내면 번호판·얼굴이 그대로 나간다 → 4-1 |
| **차량** (`현대 아반떼AD`) | ✅ | `manufacturer` + `modelName` 을 이어 붙이면 된다 |
| **분석일** (`2026.08.24`) | ⚠️ **다른 값** | 응답의 `createdAt` 은 **사고 접수 시각**이다. AI 분석일이 아니다 → 4-2 |
| **예상 수리비** (`48만 ~ 61만원`) | ❌ | 견적 요약이 응답에 없다 → 4-1 |
| **총 N건** | ⚠️ | 별도 필드가 없다. 페이지네이션이 없으므로 **배열 길이**를 그대로 쓰면 된다 |
| **페이지네이션** (1, 2) | ❌ | 쿼리 파라미터가 없다. 전체가 한 번에 온다 → 3-4 |
| 행 클릭 → 상세 이동 | ✅ | `accidentId` 로 `GET /api/accidents/{accidentId}` 를 부르면 된다 |

→ **지금 만들 수 있는 것은 "차량 · 접수일 · 상세 진입"까지다.** 썸네일·예상 수리비·분석일 열은 이미지·견적·분석 기능이 붙은 뒤에 채워진다. 열 자체를 미리 만들어 두고 비워 둘지, 나중에 추가할지는 FE 판단이다.

---

## 0. 먼저 알아야 할 것 세 가지

### 0-1. 목록 항목과 상세 응답이 **완전히 같은 모양**이다

`POST /api/accidents` 응답과도 같다. **FE 가 타입 하나를 세 곳에서 재사용할 수 있다.**

```text
POST /api/accidents            → data            (객체)
GET  /api/accidents/{id}       → data            (객체 — 위와 같은 모양)
GET  /api/accidents/me         → data.accidents[] (배열 원소가 위와 같은 모양)
```

목록에만 있는 축약 필드도, 상세에만 있는 확장 필드도 **없다.**

### 0-2. 차량 정보는 **접수 당시 스냅샷**이다 — 현재 차량 값이 아니다

`manufacturer`·`modelName`·`vehicleType`·`carClass`·`modelYear`·`modelId` 는 사고를 접수한 그 시점의 값이 사고 행에 복사되어 있다.

- 차량 연식을 수정해도 **기존 사고의 `modelYear` 는 바뀌지 않는다**
- 차량을 삭제해도, 모델 마스터의 표시값이 바뀌어도 **사고 응답은 그대로다**
- 예외는 `vehicleId` 하나뿐이다 — 이것만 현재 연관 차량에서 온다. `POST /api/accidents` 응답과 같은 동작이다

`POST /api/accidents` 의 스냅샷 계약(prompt21)과 동일하다.

### 0-3. **폐차·매각한 차량의 사고도 그대로 보인다**

차량 삭제는 소프트 삭제(`vehicle.deleted_at`)다. 그래도 **상세·목록 모두에서 사고가 사라지지 않는다.**

- 명세서 CSV 37행 비고: *"폐차 차량의 사고도 이력에 나와야 하므로 deleted_at 필터 없음"*
- 즉 `GET /api/vehicles/me` 에는 없는 차량의 사고가 `GET /api/accidents/me` 에는 있을 수 있다
- → **목록의 차량명을 `/api/vehicles/me` 와 대조해 검증하지 말 것.** 없는 것이 정상이다

---

## 1. API 2종

| # | 메서드 | 경로 | 인증 | 성공 | 명세서 |
|---|---|---|:---:|---|:---:|
| 1 | GET | `/api/accidents/{accidentId}` | 필요 | 200 | CSV 27행 |
| 2 | GET | `/api/accidents/me` | 필요 | 200 | CSV 37행 |

`PUBLIC_PATHS` 에 `/api/accidents/**` 가 없다. 둘 다 `ROLE_USER` 또는 `ROLE_ADMIN` 이 필요하다.

**같은 컨트롤러의 기존 API 2종** — 이번 작업에서 바꾸지 않았다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/api/accidents` | 사고 접수 (등록 차량 선택 / 즉시 입력) |
| PUT | `/api/accidents/{accidentId}/actual-cost` | 실제 수리비 기록 |

---

### 1-1. GET `/api/accidents/{accidentId}` — 사고 상세 조회

**요청** — 파라미터 없음.

**200**

```json
{
  "data": {
    "accidentId": 2,
    "vehicleId": 7,
    "vehicleInputType": "REGISTERED",
    "modelId": 14,
    "manufacturer": "현대",
    "modelName": "아반떼",
    "vehicleType": "SEDAN",
    "carClass": "Mid-size",
    "modelYear": 2020,
    "createdAt": "2026-09-08T04:12:00Z"
  }
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `accidentId` | number | 사고 PK |
| `vehicleId` | number | **현재 연관 차량의 id.** 이 필드만 스냅샷이 아니다 |
| `vehicleInputType` | string | `REGISTERED`(등록 차량 선택) · `DIRECT`(즉시 입력) |
| `modelId` | number | 접수 당시 모델 id (스냅샷) |
| `manufacturer` | string | 접수 당시 제조사 (스냅샷) |
| `modelName` | string | 접수 당시 차량명 (스냅샷) |
| `vehicleType` | string | `SEDAN` · `SUV` · `VAN` · `TRUCK` (스냅샷) |
| `carClass` | string | `CityCar` · `Compact` · `Mid-size` · `Full-size` (스냅샷) |
| `modelYear` | number | 접수 당시 연식 (스냅샷) |
| `createdAt` | string(ISO instant) | **사고 접수 시각.** 분석일이 아니다 |

> `vehicleType`·`carClass` 의 한글 라벨은 `차량 API — FE 인수인계.md` 3-2·3-3 상수표를 그대로 쓰면 된다. **`Mid-size` 의 하이픈에 주의.**

**에러**

| 상황 | 상태 | `error.code` | `error.message` |
|---|:---:|---|---|
| 없는 사고 · **남의 사고** · 다른 회원 차량의 사고 | 404 | `NOT_FOUND` | `사고를 찾을 수 없습니다.` |
| `{accidentId}` 가 숫자가 아님 | 400 | `INVALID_REQUEST` | — |
| 미인증 | 401 | — | **봉투가 다르다 → 2-3** |
| 가입 미완료 세션 | 403 | `SIGNUP_REQUIRED` | `약관 동의 후 가입을 완료해 주세요.` → 2-4 |

> ★ **남의 사고에 403 을 쓰지 않는다.** 403 은 "그 사고가 존재한다"는 사실을 알려주기 때문이다. 없는 사고와 남의 사고를 **구분 없이 전부 404** 로 낸다.
> → **404 를 "권한 없음"으로 나눠 표시하지 말 것.** 구분할 방법이 없고, 구분하지 않는 것이 의도다.

---

### 1-2. GET `/api/accidents/me` — 사고 이력 목록

**요청** — **파라미터가 하나도 없다.** 페이지네이션·정렬·필터 없음.

**200**

```json
{
  "data": {
    "accidents": [
      {
        "accidentId": 2,
        "vehicleId": 7,
        "vehicleInputType": "REGISTERED",
        "modelId": 14,
        "manufacturer": "현대",
        "modelName": "아반떼",
        "vehicleType": "SEDAN",
        "carClass": "Mid-size",
        "modelYear": 2020,
        "createdAt": "2026-09-08T04:12:00Z"
      },
      {
        "accidentId": 1,
        "vehicleId": 8,
        "vehicleInputType": "DIRECT",
        "modelId": 20,
        "manufacturer": "기아",
        "modelName": "K5",
        "vehicleType": "SEDAN",
        "carClass": "Mid-size",
        "modelYear": 2019,
        "createdAt": "2026-09-07T09:30:00Z"
      }
    ]
  }
}
```

**200 (사고가 0건일 때)** — 404 가 아니다.

```json
{ "data": { "accidents": [] } }
```

- **배열이 바로 오지 않는다.** `data.accidents` 로 한 번 더 들어간다 — 차량 목록(`data.vehicles`)과 같은 관례다
- **정렬은 `createdAt` 내림차순**이고, 같은 시각이면 **`accidentId` 내림차순**이다. 최근 접수가 위. **정렬 UI 를 만들지 말 것**
- 2차 정렬 키가 있어 **같은 시각에 접수된 두 건의 순서가 매번 같다.** 목록을 다시 불러도 흔들리지 않는다
- **폐차·매각한 차량의 사고도 포함된다** → 0-3
- 사고가 0건이면 **빈 배열**이다. 빈 상태 화면을 404 처리에 넣지 말 것

**에러** — 401 · 403 뿐이다. 파라미터가 없어 400 이 날 일이 없다.

---

## 2. 공통 응답 봉투와 에러

### 2-1. 성공 봉투 — 항상 `data` 로 감싼다

| API | 꺼내는 경로 |
|---|---|
| 사고 상세 | `res.data` (객체) |
| 사고 이력 목록 | `res.data.accidents` (배열) |

### 2-2. 에러 봉투 — 400 · 404 · 500

```json
{ "error": { "code": "NOT_FOUND", "message": "사고를 찾을 수 없습니다." } }
```

| 상태 | `error.code` | 언제 |
|:---:|---|---|
| 400 | `INVALID_REQUEST` | 경로 변수가 숫자가 아님 |
| 404 | `NOT_FOUND` | 없는 사고 · **남의 사고** |
| **500** | `INTERNAL_ERROR` | **지금은 `CurrentMemberProvider` 스텁 때문에 전부 여기로 온다** → ⚠️ 시작하기 전에 |

**이 두 API 에서 403 `FORBIDDEN` 과 409 는 나오지 않는다.** 403 은 아래 가입 대기 경우에만 나온다.

### 2-3. ★ 401 만 봉투가 다르다 — 본문을 파싱하지 말 것

시큐리티 필터가 `response.sendError(SC_UNAUTHORIZED)` 로 끝내 공통 예외 처리기를 거치지 않는다.

```json
{"timestamp":"...","status":401,"error":"Unauthorized","path":"/api/accidents/me"}
```

⚠️ **`error` 가 객체가 아니라 문자열이다.** `err.error.code` 가 `undefined` 다.

→ **`status === 401` 로만 분기할 것.** 이 동작은 의도적으로 유지되고 있어 곧 바뀔 예정이 아니다.

### 2-4. 403 — 가입 대기와 권한 부족을 나눠야 한다

403 은 401 과 달리 공통 봉투를 쓴다.

| 상황 | `error.code` | FE 가 할 일 |
|---|---|---|
| 소셜 인증만 끝나고 **가입 미완료** (`ROLE_SIGNUP_PENDING`) | **`SIGNUP_REQUIRED`** | **약관·가입 화면으로 보낸다** |
| 권한 부족 (관리자 전용 API 등) | `FORBIDDEN` | 권한 없음 안내 |

> ★ 소셜 로그인만 하고 약관에 동의하지 않은 세션은 "인증됨"이지만 `ROLE_USER` 가 아니라서 이 API 에서 403 이 난다. **`error.code` 로 분기해 가입 화면으로 보내야 한다.** 전 API 공통 동작이다.

```js
if (res.status === 401) { goToLogin(); return; }        // 본문 파싱 금지
const body = await res.json();
if (res.status === 403 && body.error.code === 'SIGNUP_REQUIRED') { goToSignup(); return; }
if (!res.ok) showError(body.error.message);             // 400 · 404 · 500
```

### 2-5. 인증 방식과 CORS

- **서버 세션 방식**이다. 세션 ID 는 HttpOnly 쿠키로 오간다
- → **모든 요청에 `credentials: 'include'`** (axios 는 `withCredentials: true`)
- CORS 는 **`http://localhost:5173` 하나만** 허용한다 → **FE 개발 서버를 5173 포트로 띄울 것**

---

## 3. 화면 설계에 영향을 주는 동작 8가지

| # | 동작 | 화면에 미치는 영향 |
|---|---|---|
| 1 | **지금은 로그인해도 전부 500** (`CurrentMemberProvider` 스텁) | 화면은 만들되 실호출 확인을 미룬다 → ⚠️ 시작하기 전에 |
| 2 | **썸네일·분석일·예상 수리비가 없다** | 와이어프레임의 세 열을 이 API 로 채울 수 없다 → ⚠️ 2번 · 4장 |
| 3 | **차량 정보가 접수 당시 스냅샷** | 차량을 수정해도 이력의 차종·연식이 바뀌지 않는다 → 0-2 |
| 4 | **폐차한 차량의 사고도 목록에 남는다** | `/api/vehicles/me` 와 대조해 거르지 말 것 → 0-3 |
| 5 | **남의 사고·없는 사고 모두 404** (403 없음) | "권한 없음"으로 나눠 표시하지 말 것 → 1-1 |
| 6 | **사고 0건은 빈 배열** (404 아님) | 빈 상태 화면을 404 처리에 넣지 말 것 → 1-2 |
| 7 | **`createdAt` 내림차순 + `accidentId` 내림차순 고정** | 정렬 UI 를 만들지 말 것. 순서가 매번 같다 → 1-2 |
| 8 | **페이지네이션이 없다** | 무한 스크롤·페이지 버튼을 만들지 말 것 → 3-4 |

### 3-4. 페이지네이션이 없다 — 지금은 전체가 한 번에 온다

명세서에 페이지 파라미터가 없어 **발명하지 않았다.** 회원 한 명의 사고 건수는 현재 규모에서 문제가 되지 않는다.

- 와이어프레임에는 페이지 버튼(1, 2)이 있다. **서버가 아직 지원하지 않는다**
- 지금 필요하면 **FE 가 받은 배열을 잘라서 페이지를 그리면 된다**(클라이언트 페이징). 요청은 한 번뿐이라 오히려 빠르다
- 서버 페이지네이션이 필요해지면 별도 이슈다 — 그때 `page`·`size` 파라미터가 추가되고 응답에 `totalElements` 가 붙는다. **그전까지 파라미터를 붙여 보내지 말 것.** 무시된다

---

## 4. 이번 범위가 아닌 것 — 왜 없는지

### 4-1. 이미지 · 예상 수리비

| 항목 | 왜 없는가 |
|---|---|
| **썸네일·이미지 목록** | 화면에 내보낼 수 있는 것은 번호판·얼굴을 가린 `variant='BLURRED'` 뿐인데(CSV 27행 비고) **그 생성 기능이 아직 없다**(`S15P21A307-226~228`·`386`). 지금 원본이나 `RESIZED` 를 내보내면 개인정보가 그대로 나간다. 이미지 목록은 **`GET /api/accidents/{accidentId}/images`** 가 따로 있으므로 상세 응답에 끼워 넣지 않았다 |
| **예상 수리비** | `estimate` 엔티티가 저장소에 없다. 견적 도메인의 담당·범위가 아직 확정되지 않았다 |
| **AI 분석 상태** | `analysis_job` 엔티티가 없다. AI 분석은 2차 범위다 |

**`null` 고정 필드를 미리 만들어 두지 않았다.** 있는 척하는 필드보다 없는 편이 낫다 — FE 가 "값이 안 온다"고 오해하지 않는다.

### 4-2. `createdAt` 은 분석일이 아니다

와이어프레임의 **분석일**(`2026.08.24`)은 AI 분석이 끝난 날짜다. 이 API 의 `createdAt` 은 **사고를 접수한 시각**이다. 접수 직후 분석하면 비슷하겠지만 **같은 값이 아니다.**

→ 지금 `createdAt` 을 "분석일" 열에 넣으면 나중에 진짜 분석일이 생겼을 때 값이 달라진다. **열 이름을 "접수일" 로 두거나, 분석 기능이 붙을 때까지 열을 비워 두는 편이 안전하다.** 기획 확인이 필요하다.

---

## 5. 미확정 — 기획·재원님 확인 대기

| # | 항목 | 지금 문서의 처리 | 정해지면 바뀌는 것 |
|---|---|---|---|
| 1 | **와이어프레임의 썸네일·분석일·예상 수리비 열을 지금 어떻게 할 것인가** | 채울 수 없음을 명시했다(⚠️ 2번) | 열을 나중에 추가할지, 미리 만들고 비워 둘지 |
| 2 | **"분석일" 열에 `createdAt`(접수일)을 쓸 것인가** | 다른 값이라고 적었다(4-2) | 열 이름 또는 표시할 값 |
| 3 | **서버 페이지네이션이 필요한가** | 없다고 적고 클라이언트 페이징을 권했다(3-4) | 파라미터 추가 — **서버 수정이 필요하다** |

> **FE 가 임의로 정하지 말 것.**

---

## 문서 갱신 이력

| 날짜 | 내용 |
|---|---|
| 2026-09-08 | 최초 작성. `origin/develop` `41b87e1` + 이 브랜치 구현 기준 |

**갱신이 필요한 시점** — ① `CurrentMemberProvider` 교체 시(⚠️ 1번 · 2-2 의 500 행 삭제 · 실호출 검증 반영) ② 이미지 `BLURRED` 생성 완료 시(4-1) ③ `estimate`·`analysis_job` 도입 시(4-1·4-2) ④ 서버 페이지네이션 추가 시(3-4) ⑤ 5장 미확정 항목이 확정될 때
