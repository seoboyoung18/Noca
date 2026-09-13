# 백엔드 연동 가이드 (프론트엔드용)

이 문서는 `backend/` 코드를 직접 읽고 확인한 내용을 정리한 것입니다. **[확인]** = 코드에서 직접 본 사실, **[추정]** = 근거는 있지만 코드로 100% 확인하지 못한 내용입니다. 코드와 문서가 어긋나는 부분은 코드 쪽 사실을 우선했습니다.

기준 커밋: `develop` 브랜치, 2026-09-13 기준.

---

## 1. 로그인 흐름 (OAuth + 서버 세션)

### 1-1. 전체 그림

이 서비스는 **자체 회원가입/로그인이 없고 카카오·구글 소셜 로그인만** 있습니다. JWT를 발급하지 않고 **서버 세션 + HttpOnly 쿠키**로 로그인 상태를 유지합니다. [확인] (`SecurityConfig.java`)

```
1. 프론트: 로그인 버튼 클릭 시 브라우저를 아래 주소로 이동(fetch 아님, 완전한 페이지 이동)
     GET /oauth2/authorization/kakao   또는   /oauth2/authorization/google

2. 카카오/구글 로그인 화면 → 사용자 동의

3. 카카오/구글이 백엔드 콜백으로 리다이렉트
     GET /login/oauth2/code/{provider}
   (이 경로는 프론트가 직접 호출하지 않음 — 소셜 제공자가 브라우저를 통해 호출)

4. 백엔드가 회원 여부를 판정하고, 세션 쿠키(Set-Cookie: SESSION=...)를 심은 뒤
   브라우저를 프론트 화면으로 리다이렉트
```

### 1-2. 신규 회원 판별 — 어디로 리다이렉트되는가

`OAuth2SuccessHandler` [확인]가 로그인 성공 직후 판정합니다.

| 상태 | 조건 | 세션 권한 | 세션 만료시간 | 리다이렉트 대상 |
|---|---|---|---|---|
| **가입 대기** (신규) | 소셜 계정으로 가입 이력이 없음 | `ROLE_SIGNUP_PENDING` | 10분 | `{FRONTEND_BASE_URL}/signup` |
| **로그인 완료** (기존 회원) | 소셜 계정이 이미 회원과 연결됨 | `ROLE_USER` (또는 `ROLE_ADMIN`) | 30분 | `{FRONTEND_BASE_URL}/` |

- `FRONTEND_BASE_URL`은 서버 환경변수(기본값 `http://localhost:5173`) — 즉 **어느 도메인으로 돌아올지는 백엔드 설정값**입니다.
- 리다이렉트는 **JSON 응답이 아니라 HTTP 302**입니다. 이 콜백 자체를 axios/fetch로 호출하면 안 되고, 브라우저 네비게이션(`window.location.href = ...`)으로 진입시켜야 정상 동작합니다. [확인]
- 로그인 실패 시(사용자가 동의 거부, 탈퇴 회원 재시도 등)는 `{FRONTEND_BASE_URL}/login?error={코드}`로 리다이렉트됩니다. `error` 코드 값: `access_denied`(사용자가 거부, 재시도 가능) / `withdrawn`(탈퇴 회원) / `invalid_response`(소셜 응답 이상) / `server_error`(기타). [확인] (`LoginFailureReason.java`)

### 1-3. `/signup`으로 리다이렉트된 뒤 프론트가 할 일

가입 대기 세션(쿠키는 이미 발급된 상태)에서:

1. `GET /api/auth/signup` — 가입 화면에 필요한 정보 조회 (인증 없이도 호출되지만, 가입 대기 세션이 없으면 401)
   ```json
   { "data": { "provider": "KAKAO", "socialNickname": "홍길동", "requiredTerms": ["SERVICE", "PRIVACY"] } }
   ```
2. 사용자가 닉네임 입력 + 약관 동의 후 `POST /api/auth/signup`
   ```json
   // 요청
   { "nickname": "길동이", "agreedTerms": ["SERVICE", "PRIVACY"] }
   ```
   ```json
   // 응답 201
   { "data": { "memberId": 1, "nickname": "길동이", "email": null, "provider": "KAKAO", "role": "USER" } }
   ```
3. 이 요청이 성공하면 **같은 세션이 그대로 로그인 세션으로 승격**됩니다(다시 소셜 로그인을 태우지 않음). 응답을 받은 시점부터 보호된 API를 호출할 수 있습니다. [확인] (`AuthController.signup`)

이미 가입이 끝난 세션으로 `/api/auth/signup`을 다시 부르면 `409 CONFLICT`(코드 `CONFLICT`, "이미 가입이 완료된 계정입니다.")가 납니다.

### 1-4. 세션 쿠키를 프론트가 다루는 방법

- 쿠키 이름은 **`SESSION`** [확인] (`SecurityConfig.SESSION_COOKIE`), `HttpOnly`이므로 **JS에서 절대 읽거나 만질 수 없습니다.** 존재 여부/값을 신경 쓸 필요 없이 브라우저가 자동으로 매 요청에 실어 보냅니다.
- **모든 API 호출에 `credentials: 'include'`(axios는 `withCredentials: true`)를 반드시 설정해야 합니다.** 서버 CORS 설정이 `allowCredentials(true)`이고 허용 오리진이 명시적 도메인 리스트라, 이 옵션이 없으면 쿠키가 전송되지 않아 전부 401이 납니다. [확인] (`SecurityConfig.corsConfigurationSource`)
- 로그인 여부 확인: `GET /api/auth/me` 또는 `GET /api/members/me` 호출 후 200/401로 판단 (별도의 "세션 유효성 확인" 전용 API는 없음).
- 로그아웃: `POST /api/auth/logout` (바디 없음). 성공 시 `204`, 쿠키도 만료 처리됨. **GET으로 호출하면 안 됨** — 서버가 POST만 받도록 명시적으로 제한함(CSRF성 로그아웃 공격 방지). [확인]
- 세션 만료 시간: 가입 대기 10분, 로그인 완료 30분. **30분 동안 API 호출이 없으면 세션이 끊깁니다** — 자동 로그아웃 안내 UX를 고려할 것.
- 탈퇴: `DELETE /api/members/me` → `204`. 서버가 로그아웃 처리까지 함께 수행하므로 프론트는 후속으로 로그인 페이지로 이동만 시키면 됩니다.

### 1-5. 인증/인가 에러 구분

| 상황 | 상태코드 | 응답 |
|---|---|---|
| 세션 없음/만료 | 401 | 본문 없음 (일반 `sendError`) [확인] |
| 가입 대기 세션으로 보호 API 접근 | 403 | `{ "error": { "code": "SIGNUP_REQUIRED", "message": "약관 동의 후 가입을 완료해 주세요." } }` |
| 권한 부족(예: 일반 회원이 관리자 API 호출) | 403 | `{ "error": { "code": "FORBIDDEN", "message": "접근 권한이 없습니다." } }` |

프론트는 **401**을 받으면 로그인 페이지로, **403 + `SIGNUP_REQUIRED`**를 받으면 가입 페이지로 분기해야 합니다. 401은 표준 에러 포맷이 아니라 빈 본문이라는 점에 주의하세요.

> ⚠️ **[확인 필요]** `Docs/Api/API 명세서 (바른견적 최신).md`에는 "견적서 검증 API들은 `CurrentMemberProvider.currentMemberId()`가 스텁이라 로그인해도 500이 난다"는 경고가 적혀 있습니다. 그런데 실제 코드(`common/security/CurrentMemberProvider.java`)를 읽어보면 스텁이 아니라 세션에서 `memberId`를 정상적으로 꺼내는 구현이 되어 있습니다. **문서가 이후 수정 완료를 반영하지 못한 것으로 보입니다** — 실제로 500이 나는지는 백엔드 팀에 재확인이 필요합니다(6장 질문 목록 참고).

---

## 2. 이미지 업로드 방식 — Presigned URL (S3 직접 업로드)

**바이트가 백엔드 서버를 거치지 않습니다.** 3단계로 진행됩니다. [확인] (`AccidentImageController` 클래스 주석)

```
1. POST /api/accidents/{accidentId}/images/upload-urls
     → 서버가 제약을 검증하고, 파일별 presigned PUT URL 발급 (accident_image 행이 이 시점에 예약 생성됨)
2. PUT {발급받은 presigned url}
     → 브라우저가 S3에 직접 업로드. 백엔드 API 아님
3. POST /api/accidents/{accidentId}/images
     → 완료 통보. 서버가 실제 오브젝트를 재검증하고 리사이즈본·썸네일을 만들어 저장
```

- 사고 이미지 상한: `app.accident-image.max-count-per-accident`(기본 **20장**, 장당 20MB). ⚠️ [확인] API 명세서 문서(`Docs/Api/API 명세서 (바른견적 최신).md`)에는 "사진 상한 10장 확정"이라고 적혀 있어 **코드 기본값(20)과 문서(10)가 서로 다릅니다.** 실제 배포값은 백엔드 팀에 확인 필요.
- 프로필 이미지도 같은 방식(`POST /api/members/me/profile-image/upload-url` → S3 PUT → `PUT /api/members/me/profile-image`)입니다.
- 견적서 검증용 파일 업로드는 예외적으로 **멀티파트 직접 전송**(`POST /api/estimate-validations`, `multipart/form-data`)입니다 — presigned 방식이 아닙니다. 서버가 직접 파일을 받아 저장소에 씁니다.

### 업로드 완료 통보의 부분 실패 처리 (중요)

`POST /api/accidents/{accidentId}/images` 응답은 **항상 200**입니다. 일부 이미지가 실패해도 HTTP 상태코드는 바뀌지 않고, **`results[].status`로만** 성공/실패가 구분됩니다.

```json
{ "data": {
  "requested": 2, "succeeded": 1, "failed": 1,
  "results": [
    { "imageId": 101, "status": "COMPLETED", "qualityStatus": "PASS", "assets": [ /* RESIZED, THUMBNAIL */ ] },
    { "imageId": 102, "status": "FAILED", "failureCode": "SERVER_CONVERSION_UNSUPPORTED", "failureMessage": "..." }
  ]
} }
```

`status` 값: `COMPLETED`(성공) / `ALREADY_COMPLETED`(이미 처리된 이미지를 다시 통보, 에러 아님) / `FAILED`(실패, 같은 imageId로 재업로드 가능).

응답에 담기는 이미지는 **원본이 아니라 `RESIZED`·`THUMBNAIL`만** 노출됩니다(EXIF 개인정보 제거 목적). 화면에 원본을 그대로 보여줄 방법은 없습니다. [확인]

---

## 3. 비동기 API 3종 — 상태값과 폴링 방법

API 명세서는 비동기 API를 3종(AI 분석 / 견적 PDF / 견적서 검증)이라 소개하지만, **코드로 확인한 결과 실제 구현 상태는 서로 다릅니다.**

### 3-1. AI 분석 (`analysis_job.status`) — ❌ **API 자체가 없음 (미구현)**

- `analysis` 패키지 전체를 확인했지만 `@RestController`가 **하나도 없습니다.** "분석 요청" 엔드포인트, "상태 폴링" 엔드포인트, "결과 조회" 엔드포인트 모두 존재하지 않습니다.
- DB 테이블/상태값 정의(`AnalysisJobStatus`: `QUEUED`/`PROCESSING`/`COMPLETED`/`FAILED`)와, AI 서버가 만든 결과 JSON을 DB에 적재하는 내부 서비스(`AnalysisResultIngestService`)는 존재하지만, **이 서비스를 실제로 호출하는 컨트롤러나 워커가 없습니다** — 테스트 코드에서만 호출됩니다.
- 코드 주석에 직접 이렇게 적혀 있습니다: *"작업을 만들고 진행시키는 쪽은 비동기 분석 요청 스토리(S15P21A307-155)이고... 나중에 붙을 분석 요청 스토리가 쓴다."*
- **프론트 대응**: 지금은 이 흐름을 실제 API로 연동할 수 없습니다. API 명세서가 말하는 계약(202 Accepted 후 폴링, `analysis_stage` 4단계 체크리스트)을 기준으로 화면/상태관리만 먼저 만들고, mock으로 `QUEUED → PROCESSING → COMPLETED` 전환을 흉내 내며 개발을 진행하는 것을 권장합니다.

### 3-2. 견적서 검증 (`estimate_validation.status`) — ✅ 구현되어 있음

`ValidationStatus` enum: `QUEUED` / `PROCESSING` / `COMPLETED` / `FAILED` [확인]

```
1. 등록 (JSON 직접 입력 또는 파일 업로드)
   POST /api/estimate-validations                (Content-Type: application/json — 수기 입력)
   POST /api/estimate-validations                (Content-Type: multipart/form-data — 파일 업로드)
   → 202 Accepted
   { "data": { "validationId": 10, "status": "QUEUED", "inputType": "PDF", "statusUrl": "/api/estimate-validations/10" } }

2. 상태 폴링
   GET /api/estimate-validations/{validationId}
   → { "data": { "validationId": 10, "inputType": "PDF", "status": "PROCESSING",
                 "failureReason": null, "createdAt": "...", "completedAt": null } }
   status 가 COMPLETED 또는 FAILED 가 될 때까지 반복 조회

3. 결과 조회 (COMPLETED 후에만 의미 있는 값)
   GET /api/estimate-validations/{validationId}/result
   → grade(APPROPRIATE/CAUTION/NEEDS_REVIEW), summary, items[], questions[] 등

4. PDF 다운로드
   GET /api/estimate-validations/{validationId}/pdf
   → 302 리다이렉트로 presigned GET URL 반환. 아직 생성 전이면 409 CONFLICT
```

**등록 요청 body 예시 (JSON 수기 입력)**:
```json
{
  "accidentId": 2, "estimateId": null, "fileType": "MANUAL", "claimedTotal": 825969,
  "items": [
    { "lineNo": 1, "rawItemName": "전면 범퍼 교체", "workType": "REPLACE", "quantity": 1, "partCost": 200000, "laborCost": 122500 }
  ]
}
```
**등록 요청 (파일 업로드, multipart)**: `file` 파트(이미지/PDF) + `metadata` 파트(JSON: `{"accidentId": 2, "estimateId": null}`).

**폴링 주기 관련**: 명세서 상 "폴링이 전체 트래픽의 62%"라는 언급이 있고 서버는 Redis 캐시로 대응한다고 되어 있습니다 — 프론트는 **너무 짧은 간격으로 폴링하지 않아야 합니다.** 명세서에 구체적 권장 간격 수치는 없으므로 3~5초 정도로 시작하고 백엔드 팀과 협의하는 것을 권장합니다(6장 질문 참고).

이 PDF 생성 자체도 내부적으로 비동기 워커(`ValidationPdfWorker`, `estimate_validation_report` 테이블, 상태값 동일하게 `QUEUED/PROCESSING/COMPLETED/FAILED`)가 처리합니다. 별도의 "PDF 상태 조회" API는 없고, **`GET .../pdf`를 다시 호출해 302 vs 409로 판단**하는 구조입니다. [확인]

### 3-3. "견적 PDF" (`estimate_report.status`) — ⚠️ **코드에서 실체를 찾지 못함**

- API 명세서는 "견적 PDF"를 `estimate_report.status`를 폴링하는 별도의 비동기 API로 소개합니다.
- 코드에서 `estimate_report`라는 이름의 **Java 엔티티·리포지토리·컨트롤러를 찾지 못했습니다.** 이 이름은 주석(`EstimateVersioningService`, `EstimateReportResponse`)에서만 언급됩니다.
- 실제 존재하는 것은 `GET /api/estimates/{estimateId}/report`(**동기 GET**, "저장하지 않고 매번 조립"한다고 코드 주석에 명시 — 202/폴링 없음) 하나뿐입니다. 이 API는 견적·근거·검증 결과를 한 화면 분량으로 모아 JSON으로 돌려줄 뿐, **PDF 파일 자체를 만들어주는 API는 아닙니다.**
- **결론**: 명세서가 말하는 "견적 PDF 비동기 생성/폴링"은 코드베이스에서 확인되지 않았습니다. 3-2의 "견적서 검증 PDF"(`estimate_validation_report`)와 혼동되었을 가능성이 있어 보이나 확실치 않습니다 — **반드시 백엔드 팀에 확인이 필요합니다** (6장 질문 참고).

---

## 4. 도메인별 API 목록

공개(인증 불필요) API를 제외한 모든 API는 세션 쿠키가 필요합니다. 요청/응답은 모두 `{ "data": ... }` 로 감싸져 옵니다(5장 참고).

### 인증 (`/api/auth`)
| Method | URL | 설명 |
|---|---|---|
| GET | `/api/auth/signup` | 가입 화면용 정보 (가입 대기 세션 필요) |
| POST | `/api/auth/signup` | 회원가입 (가입 대기 세션 → 로그인 세션 승격) |
| GET | `/api/auth/me` | 내 정보 (탈퇴 여부까지 재확인) |
| POST | `/api/auth/logout` | 로그아웃 (204) |

### 마이페이지 (`/api/members`)
| Method | URL | 설명 |
|---|---|---|
| GET | `/api/members/me` | 프로필 조회. `vehicleCount`/`accidentCount` 포함 |
| PATCH | `/api/members/me` | 닉네임 변경 |
| DELETE | `/api/members/me` | 회원 탈퇴 (소프트 삭제, 204) |
| POST | `/api/members/me/profile-image/upload-url` | 프로필 이미지 업로드 URL 발급 |
| PUT | `/api/members/me/profile-image` | 프로필 이미지 업로드 완료 통보 |
| DELETE | `/api/members/me/profile-image` | 프로필 이미지 삭제 |

응답 예 (`GET /api/members/me`):
```json
{ "data": { "memberId": 1, "nickname": "길동이", "email": null, "provider": "KAKAO",
            "profileImageUrl": "https://...presigned...", "vehicleCount": 2, "accidentCount": 3,
            "createdAt": "2026-01-01T00:00:00Z" } }
```

### 차량 (`/api/vehicles`, `/api/vehicle-models`)
| Method | URL | 설명 |
|---|---|---|
| POST | `/api/vehicles` | 차량 등록 (`modelId` + `modelYear`만 받음) |
| GET | `/api/vehicles/me` | 내 차량 목록 |
| PATCH | `/api/vehicles/{vehicleId}` | 차량 수정 |
| DELETE | `/api/vehicles/{vehicleId}` | 소프트 삭제(폐차·매각) |
| GET | `/api/vehicle-models` | 차종 마스터 검색(제조사·모델명 자동완성용으로 추정 [추정]) |

응답 예 (`VehicleResponse`, 등록/수정/목록 공통):
```json
{ "vehicleId": 7, "modelId": 14, "manufacturer": "현대", "modelName": "아반떼",
  "vehicleType": "SEDAN", "carClass": "Mid-size", "modelYear": 2020 }
```

### 사고 (`/api/accidents`)
| Method | URL | 설명 |
|---|---|---|
| POST | `/api/accidents` | 사고 접수 |
| GET | `/api/accidents/me` | 내 사고 이력 (페이지네이션: `page`, `size`) |
| GET | `/api/accidents/{accidentId}` | 사고 상세 |
| PUT | `/api/accidents/{accidentId}/actual-cost` | 실제 수리비 기록(정비 완료 후) |
| POST | `/api/accidents/{accidentId}/images/upload-urls` | 이미지 업로드 URL 발급 |
| POST | `/api/accidents/{accidentId}/images` | 이미지 업로드 완료 통보 |
| GET | `/api/accidents/{accidentId}/images` | 이미지 목록/상태 조회 (명세서엔 없는 추가 API) |
| DELETE | `/api/accidents/{accidentId}/images/{imageId}` | 이미지 삭제 |
| GET | `/api/accidents/{accidentId}/cost-comparison` | 실제 수리비 vs AI 예상액 비교 |

없는 사고/남의 사고/폐차 차량의 사고 모두 **403이 아니라 404**로 응답합니다(사고 존재 여부를 노출하지 않기 위함). [확인]

### 견적 (`/api/estimates`, `/api/accidents/{id}/estimates`)
| Method | URL | 설명 |
|---|---|---|
| GET | `/api/estimates/{estimateId}` | 견적 상세 (**생성 API 없음** — 분석 완료 시 자동 산정 예정이나 2장에서 설명했듯 현재 미연결) |
| GET | `/api/estimates/{estimateId}/basis` | 항목별 산정 근거 |
| GET | `/api/estimates/{estimateId}/report` | 리포트 조립본(견적+근거+검증 통합, 동기) |
| GET | `/api/accidents/{accidentId}/estimates?latest=true` | 사고별 견적 이력(재산정 시 버전 누적) |
| GET | `/api/estimates/{estimateId}/similar-cases?estimateItemId=` | 항목이 참고한 유사 사례 |
| GET | `/api/repair-cases/{caseId}` | 유사 사례 상세 |

### 견적서 검증 (`/api/estimate-validations`) — 3장 참고

### 가이드 (`/api/guides`, 인증 불필요)
| Method | URL | 설명 |
|---|---|---|
| GET | `/api/guides/shooting` | 촬영 가이드(각도별) |
| GET | `/api/guides/checklist` | 사고 현장 체크리스트 |

### 위치/정비소 (`/api/locations`, `/api/repair-shops`) — 인증 필요
| Method | URL | 설명 |
|---|---|---|
| GET | `/api/locations/geocode?query=` | 주소 → 좌표 |
| GET | `/api/locations/reverse-geocode?latitude=&longitude=` | 좌표 → 주소 (결과 0건 가능, 404 아님) |
| GET | `/api/locations/places?query=` | 키워드 장소 검색 |
| GET | `/api/locations/places/category?categoryGroupCode=` | 카테고리별 주변 장소 |
| GET | `/api/locations/category-groups` | 카테고리 코드 18종 목록 |
| GET | `/api/repair-shops?latitude=&longitude=` | 가까운 정비소 검색(거리순 고정) |

> ⚠️ 좌표 순서 주의: 응답은 `latitude`/`longitude` 이름을 쓰지만, 카카오 지도 JS SDK에 넘길 때는 `new kakao.maps.LatLng(latitude, longitude)` 순서를 지켜야 합니다. [확인]

### 관리자 (`/api/admin/**`) — `ROLE_ADMIN` 전용, 일반 사용자 화면에서는 사용 안 함
`vehicle-models`, `part-codes`, `part-name-mappings`, `repair-codes`, `repair-method-rules`, `estimate-validation-rules`, `rules/overview`, `rules/history`, `audit-logs` 등 CRUD API가 존재합니다. [확인] 프론트 어드민 화면을 별도로 만든다면 이 목록을 기준으로 상세 스펙을 다시 확인하세요(본 문서에서는 상세 DTO를 다루지 않았습니다).

---

## 5. 공통 에러 응답 형식

**[확인]** (`ApiResponse`, `ErrorResponse`, `GlobalExceptionHandler`)

- 성공: 항상 `{ "data": { ... } }`
- 실패: 항상 `{ "error": { "code": "STRING", "message": "사람이 읽는 한글 문구" } }` — **에러를 HTTP 200으로 내려주지 않음**
- 예외: 401은 위 포맷이 아니라 **빈 본문**(1-5절 참고), 완료 통보(`POST /api/accidents/{id}/images`)의 개별 이미지 실패는 200 안의 `results[].failureCode`로 옴(2장 참고)

| HTTP | `error.code` | 의미 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 요청 형식/값 오류 |
| 401 | (본문 없음) | 인증 필요 |
| 403 | `SIGNUP_REQUIRED` | 가입 대기 세션 (약관 동의 필요) |
| 403 | `FORBIDDEN` | 권한 부족 |
| 404 | `NOT_FOUND` | 대상 없음 (남의 것도 동일하게 404) |
| 409 | `CONFLICT` | 상태 충돌(중복 가입, DB 제약 위반, PDF 미생성 등) |
| 429 | `TOO_MANY_REQUESTS` | 요청 과다 |
| 503 | `SERVICE_UNAVAILABLE` | 외부 연동(S3/카카오/LLM) 설정 미구성 |
| 500 | `INTERNAL_ERROR` | 예기치 못한 서버 오류 |

**주의**: `error.code`가 항상 위 8개 값 중 하나는 아닙니다. 일부 도메인은 **더 구체적인 문자열 코드**를 그대로 내보냅니다(예: 이미지 검증 실패 시 `TOO_MANY_IMAGES`, `SERVER_CONVERSION_UNSUPPORTED`, `UNKNOWN_ANGLE_CODE` 등; 관리자 도메인의 `VERSION_CONFLICT` 등). 이는 의도된 설계입니다 — 코드 주석에 "한글 메시지로 프론트가 분기하게 만들지 않기 위해서"라고 명시되어 있습니다. **프론트는 `error.code`를 `string` 타입으로 받고, 상태코드+코드 조합으로 분기 로직을 짜야 합니다.**

---

## 6. 백엔드 팀에게 확인해야 할 질문 목록

코드만으로는 확인이 안 되거나, 문서와 코드가 어긋나는 부분입니다.

1. **AI 분석 요청/폴링/결과 조회 API(`S15P21A307-155`)는 언제 나오나요?** 프론트가 어떤 계약(요청 형식, 202 응답 모양, `analysis_stage` 4단계 값 목록)을 기준으로 화면을 미리 만들어두면 되는지 알고 싶습니다.
2. **"견적 PDF" 비동기 API(`estimate_report.status`)의 실제 엔드포인트가 있나요?** 코드에서 `estimate_report` 테이블에 대응하는 컨트롤러/엔티티를 찾지 못했습니다. `GET /api/estimates/{id}/report`(동기)와는 다른 것인지, 아니면 견적서 검증 PDF(`/api/estimate-validations/{id}/pdf`)와 같은 것을 가리키는 것인지 확인 부탁드립니다.
3. **`CurrentMemberProvider` 스텁 이슈가 실제로 해결됐나요?** API 명세서 문서는 "로그인해도 500"이라고 경고하지만, 현재 코드는 정상 구현되어 있습니다. 문서가 낡은 것인지, 아니면 다른 원인으로 여전히 500이 발생하는 케이스가 남아있는지 확인 부탁드립니다.
4. **분석 완료 → 견적 자동 산정(`S15P21A307-256~258`) 연결은 언제 배포되나요?** `EstimateVersioningService.append()`를 호출하는 코드가 현재 어디에도 없어서, 지금은 `estimate` 테이블에 실제 데이터가 쌓일 경로가 없는 것으로 보입니다.
5. **사고 이미지 장수 상한이 10장인가요, 20장인가요?** 코드 기본값(`application.properties`)은 20장인데, API 명세서는 "10장 확정"이라고 되어 있습니다. 배포 환경 실제 설정값을 알려주세요.
6. **견적서 검증 상태 폴링 권장 간격이 있나요?** 명세서에 "폴링이 트래픽의 62%"라는 부하 관련 언급은 있지만 권장 폴링 주기(예: 3초/5초)가 명시되어 있지 않습니다.
7. **`GET /api/vehicle-models`의 정확한 요청 파라미터(검색어/페이지네이션 등)와 용도**를 확인 부탁드립니다 — 컨트롤러가 있다는 것만 확인했고 상세 요청 파라미터는 이 문서에서 다루지 않았습니다.
8. **관리자(`/api/admin/**`) API를 프론트에서 언제부터 붙이면 되는지**, 그리고 관리자 화면이 이 리포지토리의 `frontend/` 안에 함께 들어가는지 별도 프로젝트인지 확인 부탁드립니다.
9. **소셜 계정 unlink(연동 해제)가 탈퇴 시 이뤄지지 않는다고 코드 주석에 적혀 있는데(`S15P21A307-102`)**, 탈퇴 후 같은 소셜 계정으로 재로그인하면 어떤 화면으로 보내야 하는지(재가입 취급인지) 기획 확인이 필요합니다.

---

## 참고 — 함께 보면 좋은 문서
- `Docs/Api/*.md` — 도메인별 "FE 인수인계" 문서 (더 자세한 요청/응답 예시)
- `Docs/Wireframe/*.png` — 화면 와이어프레임
- `Docs/Erd/A307_ddl_final.sql` — DB 테이블 정의 원본
