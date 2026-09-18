# A307 백엔드 API 명세서 (최신)

> 기준 `origin/develop` **`0416f39`** · 2026-09-16
> **컨트롤러 엔드포인트 93개** — `backend/src/main/java` 의 `@*Mapping` 을 직접 세었다

## 집계

| 구분 | 개수 |
| --- | --- |
| **컨트롤러 엔드포인트** | **93** |
| ├ 사용자 화면 연계 대상 | 61 |
| ├ 관리자 (FE 미착수) | 31 |
| └ 내부 AI 콜백 | 1 |
| Spring Security OAuth 경로 *(93 에 미포함)* | 2 |

`61 + 31 + 1 = 93`

### 담당

| 구분 | 재원 | 보영 | 합 |
| --- | --- | --- | --- |
| 전체 | 72 | 21 | 93 |
| 사용자 화면 연계 대상 | 41 | 20 | 61 |
| 관리자 | 31 | 0 | 31 |
| 내부 AI 콜백 | 0 | 1 | 1 |

담당은 **`git blame` 의 줄 단위 소유**로 판정했다. 엔드포인트 93개의 메서드 블록을 각각 blame 한 결과 **여러 사람이 섞인 블록은 0건**이다. `AuthController`(143줄)·`MemberController`(146줄)도 전부 보영 단독이다.

### 방법별

| method | 개수 |
| --- | --- |
| GET | 49 |
| POST | 19 |
| PATCH | 16 |
| DELETE | 7 |
| PUT | 2 |

---

## 읽는 법

| 열 | 내용 |
| --- | --- |
| 차시 | **전부 `확인 필요`** — 노션을 읽지 못했다. 아래 "확인하지 못한 것" 참조 |
| 기능 | 메서드 javadoc 첫 문장. 없으면 `(메서드명)` |
| 연결 화면 | 와이어프레임명 또는 `화면-서버 대조표` 의 화면 |
| 상태 | `연계` · `FE 미착수` · `화면 비대상` · `확인 필요` |

---

## 인증 — 3개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 가입 화면이 쓸 값 | GET | `/api/auth/signup` | 보영 | 약관동의 | 연계 |  |
| 확인 필요 | 약관 동의와 함께 회원을 만든다 | POST | `/api/auth/signup` | 보영 | 약관동의 | 연계 |  |
| 확인 필요 | 내 정보 | GET | `/api/auth/me` | 보영 | 마이페이지_내차량정보 | 연계 |  |

## 회원 — 6개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | (me) | GET | `/api/members/me` | 보영 | 마이페이지_내차량정보 | 연계 |  |
| 확인 필요 | 닉네임 수정 | PATCH | `/api/members/me` | 보영 | 마이페이지_내차량정보 | 연계 |  |
| 확인 필요 | 탈퇴 | DELETE | `/api/members/me` | 보영 | 마이페이지_내차량정보 | 연계 |  |
| 확인 필요 | 프로필 이미지 업로드 URL 발급 | POST | `/api/members/me/profile-image/upload-url` | 보영 | 마이페이지_내차량정보 | 연계 |  |
| 확인 필요 | 업로드 완료 통보 — 등록과 변경이 같은 요청이다 | PUT | `/api/members/me/profile-image` | 보영 | 마이페이지_내차량정보 | 연계 |  |
| 확인 필요 | 이미지 삭제 | DELETE | `/api/members/me/profile-image` | 보영 | 마이페이지_내차량정보 | 연계 |  |

## 차량 — 5개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | (create) | POST | `/api/vehicles` | 재원 | 정보입력 | 연계 |  |
| 확인 필요 | (findMine) | GET | `/api/vehicles/me` | 재원 | 마이페이지_내차량정보 | 연계 |  |
| 확인 필요 | (update) | PATCH | `/api/vehicles/{vehicleId}` | 재원 | 마이페이지_내차량정보 | 연계 |  |
| 확인 필요 | (delete) | DELETE | `/api/vehicles/{vehicleId}` | 재원 | 마이페이지_내차량정보 | 연계 |  |
| 확인 필요 | 필터 파라미터·페이지네이션 없음 | GET | `/api/vehicle-models` | 재원 | 정보입력 | 연계 |  |

## 사고 — 6개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | (create) | POST | `/api/accidents` | 재원 | 정보입력 | 연계 |  |
| 확인 필요 | 사고 이력 목록 | GET | `/api/accidents/me` | 재원 | 마이페이지_견적이력 | 연계 |  |
| 확인 필요 | (findOne) | GET | `/api/accidents/{accidentId}` | 재원 | 확인 필요 | 확인 필요 | 화면-서버 대조표(09-12)에 없음 |
| 확인 필요 | (recordActualRepairCost) | PUT | `/api/accidents/{accidentId}/actual-cost` | 재원 | 확인 필요 | 확인 필요 | 화면-서버 대조표(09-12)에 없음 |
| 확인 필요 | 사고에 딸린 견적 이력 | GET | `/api/accidents/{accidentId}/estimates` | 보영 | 마이페이지_견적이력 | 연계 |  |
| 확인 필요 | (compare) | GET | `/api/accidents/{accidentId}/cost-comparison` | 재원 | 견적 세부 결과 | 연계 |  |

## 사고 이미지 — 4개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 발급은 accident_image 행을 새로 만들므로 201 이다 | POST | `/api/accidents/{accidentId}/images/upload-urls` | 재원 | 촬영 가이드 | 연계 |  |
| 확인 필요 | 완료 통보 | POST | `/api/accidents/{accidentId}/images` | 재원 | 촬영 가이드 | 연계 |  |
| 확인 필요 | 파일별 업로드 상태(Task 143) | GET | `/api/accidents/{accidentId}/images` | 재원 | 재촬영 | 연계 |  |
| 확인 필요 | VehicleController | DELETE | `/api/accidents/{accidentId}/images/{imageId}` | 재원 | 재촬영 | 연계 |  |

## AI 분석 — 3개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 진행 상태 | GET | `/api/accidents/{accidentId}/analysis` | 재원 | 분석중 · 손상분석 | 연계 |  |
| 확인 필요 | 검출된 부위와 사진별 좌표 | GET | `/api/accidents/{accidentId}/analysis/result` | 보영 | 분석중 · 손상분석 | 연계 |  |
| 확인 필요 | 접수만 하고 202 다 | POST | `/api/accidents/{accidentId}/analysis` | 보영 | 분석중 · 손상분석 | 연계 |  |

## 견적 — 7개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 견적 상세 | GET | `/api/estimates/{estimateId}` | 보영 | 견적 결과 · 견적 세부 결과 · PDF 만드는중 · 리포트 미리보기 | 연계 |  |
| 확인 필요 | 항목별 산정 근거 | GET | `/api/estimates/{estimateId}/basis` | 보영 | 견적 결과 · 견적 세부 결과 · PDF 만드는중 · 리포트 미리보기 | 연계 |  |
| 확인 필요 | 202 — 생성은 워커가 한다 | POST | `/api/estimates/{estimateId}/pdf` | 보영 | 견적 결과 · 견적 세부 결과 · PDF 만드는중 · 리포트 미리보기 | 연계 |  |
| 확인 필요 | 가장 최근 요청의 상태 | GET | `/api/estimates/{estimateId}/pdf` | 보영 | 견적 결과 · 견적 세부 결과 · PDF 만드는중 · 리포트 미리보기 | 연계 |  |
| 확인 필요 | 302 + 서명된 조회 URL(5분) | GET | `/api/estimates/{estimateId}/pdf/download` | 보영 | 견적 결과 · 견적 세부 결과 · PDF 만드는중 · 리포트 미리보기 | 연계 |  |
| 확인 필요 | (report) | GET | `/api/estimates/{estimateId}/report` | 보영 | 견적 결과 · 견적 세부 결과 · PDF 만드는중 · 리포트 미리보기 | 연계 |  |
| 확인 필요 | 항목이 참고한 유사 사례 | GET | `/api/estimates/{estimateId}/similar-cases` | 보영 | 견적 결과 · 견적 세부 결과 · PDF 만드는중 · 리포트 미리보기 | 연계 |  |

## 유사 사례 — 1개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | (detail) | GET | `/api/repair-cases/{caseId}` | 보영 | 견적 세부 결과 | 연계 |  |

## 견적 검증 — 8개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | (registerManual) | POST | `/api/estimate-validations` | 재원 | 정비소 견적서 검증 결과 · OCR 검토 | 연계 | `consumes=application/json` — 항목 직접 입력 |
| 확인 필요 | (registerFile) | POST | `/api/estimate-validations` | 재원 | 정비소 견적서 업로드 | 연계 | `consumes=multipart/form-data` — 파일 업로드 |
| 확인 필요 | (status) | GET | `/api/estimate-validations/{validationId}` | 재원 | 정비소 견적서 검증 결과 · OCR 검토 | 연계 |  |
| 확인 필요 | (result) | GET | `/api/estimate-validations/{validationId}/result` | 재원 | 정비소 견적서 검증 결과 · OCR 검토 | 연계 |  |
| 확인 필요 | (questions) | GET | `/api/estimate-validations/{validationId}/questions` | 재원 | 정비소 견적서 검증 결과 · OCR 검토 | 연계 |  |
| 확인 필요 | (history) | GET | `/api/estimate-validations/me` | 재원 | 정비소 견적서 검증 결과 · OCR 검토 | 연계 |  |
| 확인 필요 | (pdf) | GET | `/api/estimate-validations/{validationId}/pdf` | 재원 | 정비소 견적서 검증 결과 · OCR 검토 | 연계 |  |
| 확인 필요 | (delete) | DELETE | `/api/estimate-validations/{validationId}` | 재원 | 정비소 견적서 검증 결과 · OCR 검토 | 연계 |  |

## 정비 체크리스트 — 8개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 생성 요청 | POST | `/api/accidents/{accidentId}/repair-checklist` | 재원 | 사고체크리스트 | 연계 |  |
| 확인 필요 | 생성 상태 · 항목 · 진행률 · 고지 문구 | GET | `/api/accidents/{accidentId}/repair-checklist` | 재원 | 사고체크리스트 | 연계 |  |
| 확인 필요 | 사용자 항목 추가 | POST | `/api/accidents/{accidentId}/repair-checklist/items` | 재원 | 사고체크리스트 | 연계 |  |
| 확인 필요 | 사용자 항목 문안 수정 | PATCH | `/api/accidents/{accidentId}/repair-checklist/items/{itemId}` | 재원 | 사고체크리스트 | 연계 |  |
| 확인 필요 | 사용자 항목 삭제 | DELETE | `/api/accidents/{accidentId}/repair-checklist/items/{itemId}` | 재원 | 사고체크리스트 | 연계 |  |
| 확인 필요 | 재생성 | POST | `/api/accidents/{accidentId}/repair-checklist/regenerate` | 재원 | 사고체크리스트 | 연계 |  |
| 확인 필요 | 항목 완료 체크·해제 | PATCH | `/api/accidents/{accidentId}/repair-checklist/items/{itemId}/check` | 재원 | 사고체크리스트 | 연계 |  |
| 확인 필요 | 항목 메모 저장 | PATCH | `/api/accidents/{accidentId}/repair-checklist/items/{itemId}/memo` | 재원 | 사고체크리스트 | 연계 |  |

## 정비소 질문 — 2개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 생성 요청 | POST | `/api/accidents/{accidentId}/repair-questions` | 재원 | 정비소 견적서 검증 결과 | 연계 |  |
| 확인 필요 | 생성 상태와 질문 목록 | GET | `/api/accidents/{accidentId}/repair-questions` | 재원 | 정비소 견적서 검증 결과 | 연계 |  |

## 가이드 — 2개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 사고 현장 체크리스트 | GET | `/api/guides/checklist` | 재원 | 사고체크리스트 | 연계 |  |
| 확인 필요 | 각도별 촬영 가이드 | GET | `/api/guides/shooting` | 재원 | 촬영 가이드 | 연계 |  |

## 위치 — 5개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 주소 문자열 → 좌표 | GET | `/api/locations/geocode` | 재원 | 정비소 견적서 업로드 | 연계 |  |
| 확인 필요 | 좌표 → 주소 | GET | `/api/locations/reverse-geocode` | 재원 | 정비소 견적서 업로드 | 연계 |  |
| 확인 필요 | 키워드로 장소 검색 | GET | `/api/locations/places` | 재원 | 정비소 견적서 업로드 | 연계 |  |
| 확인 필요 | 카테고리 그룹 코드로 주변 장소 검색 | GET | `/api/locations/places/category` | 재원 | 정비소 견적서 업로드 | 연계 |  |
| 확인 필요 | 선택 가능한 카테고리 그룹 코드 18종 | GET | `/api/locations/category-groups` | 재원 | 정비소 견적서 업로드 | 연계 |  |

## 정비소 — 1개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | (searchNearby) | GET | `/api/repair-shops` | 재원 | 정비소 견적서 업로드 | 연계 |  |

## 관리자 — 31개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 검수 목록 | GET | `/api/admin/accident-reviews` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 비교 상세 | GET | `/api/admin/accident-reviews/{reviewId}` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 승인·반려 | PATCH | `/api/admin/accident-reviews/{reviewId}` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (search) | GET | `/api/admin/audit-logs` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 지금 적용 중인 규칙 | GET | `/api/admin/estimate-validation-rules/current` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 버전 이력 | GET | `/api/admin/estimate-validation-rules/history` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 새 버전을 만들어 즉시 활성화한다 | PATCH | `/api/admin/estimate-validation-rules/current` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (search) | GET | `/api/admin/part-codes` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (findOne) | GET | `/api/admin/part-codes/{partCode}` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (create) | POST | `/api/admin/part-codes` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (update) | PATCH | `/api/admin/part-codes/{partCode}` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (changeStatus) | PATCH | `/api/admin/part-codes/{partCode}/status` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 목록 | GET | `/api/admin/part-name-mappings` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (create) | POST | `/api/admin/part-name-mappings` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 대상 부품만 바꾼다 | PATCH | `/api/admin/part-name-mappings` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (delete) | DELETE | `/api/admin/part-name-mappings` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 행이 8개뿐이라 페이지네이션하지 않는다 | GET | `/api/admin/repair-codes` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (update) | PATCH | `/api/admin/repair-codes/{codeType}/{code}` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (changeStatus) | PATCH | `/api/admin/repair-codes/{codeType}/{code}/status` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (search) | GET | `/api/admin/repair-method-rules` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (findOne) | GET | `/api/admin/repair-method-rules/{ruleId}` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (create) | POST | `/api/admin/repair-method-rules` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (update) | PATCH | `/api/admin/repair-method-rules/{ruleId}` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (changeStatus) | PATCH | `/api/admin/repair-method-rules/{ruleId}/status` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 화면이 처음 그릴 때 필요한 것 전부 — 수리 방식 규칙 목록, 현재 이상 탐지 임 | GET | `/api/admin/rules/overview` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 규칙 변경 이력 | GET | `/api/admin/rules/history` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | active 를 생략하면 비활성 모델까지 전부 나온다 — 공개 목록과 다른 점이다 | GET | `/api/admin/vehicle-models` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (findOne) | GET | `/api/admin/vehicle-models/{modelId}` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (create) | POST | `/api/admin/vehicle-models` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | (update) | PATCH | `/api/admin/vehicle-models/{modelId}` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |
| 확인 필요 | 비활성화와 재활성화가 같은 엔드포인트다 | PATCH | `/api/admin/vehicle-models/{modelId}/status` | 재원 | — | FE 미착수 | 관리자 화면 에픽(-282) 범위 |

## 내부(AI) — 1개

| 차시 | 기능 | method | url | 담당 | 연결 화면 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 확인 필요 | 분석 결과 수신 | POST | `/internal/analysis-jobs/{jobId}/result` | 보영 | — | 화면 비대상 | AI 서버 콜백용 내부 API |

---

## Spring Security 제공 경로 (93 에 미포함)

컨트롤러 코드가 없다. Spring Security 가 제공한다.

| 기능 | method | url | 담당 | 연결 화면 |
| --- | --- | --- | --- | --- |
| 소셜 인증 시작 (카카오·구글) | GET | `/oauth2/authorization/{provider}` | 보영 | 로그인 |
| OAuth 콜백 | GET | `/login/oauth2/code/{provider}` | 보영 | 로그인 |

## 코드에서 확인되지 않는 항목

없다. 노션 표를 읽지 못해 대조하지 못했다 — 아래 참조.

## 확인하지 못한 것

| 항목 | 이유 |
| --- | --- |
| **차시 (1차/2차)** | 노션 페이지(`app.notion.com/p/API-3d0b...`)가 로그인을 요구해 읽지 못했다. **웹 게시(`notion.site`) 링크가 있으면 채울 수 있다** |
| 노션에만 있는 항목 | 같은 이유로 대조하지 못했다 |
| `GET /api/accidents/{accidentId}` 의 화면 | `화면-서버 대조표`(09-12)에 없다 |
| `PUT /api/accidents/{accidentId}/actual-cost` 의 화면 | 같다. 와이어프레임 20장에도 실제 수리비 입력 화면이 없다 |

## 기존 문서와의 차이

| | `API 명세서 (바른견적 최신).md` | 이 문서 |
| --- | --- | --- |
| 작성 | 2026-09-10 | 2026-09-16 |
| 엔드포인트 | **9개** | **93개** |
| 담당 표기 | 없음 | 있음 (blame 근거) |
| 화면 연계 | 없음 | 있음 |

기존 문서는 **9개만 담고 있어 낡았다.** 지우지 않았다. 교체 여부는 팀이 정한다.