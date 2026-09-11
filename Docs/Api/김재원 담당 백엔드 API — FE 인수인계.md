# 김재원 담당 백엔드 API — 프론트엔드 인수인계

> **독자** — 화면을 붙이는 프론트엔드 담당자. **Vue 3 + 순수 JavaScript** 기준입니다. TypeScript 를 쓰지 않습니다.
> **이 문서의 역할** — 김재원이 만든 API 를 **한 곳에서 다 보고, 어떤 순서로 붙일지** 알게 하는 지도입니다.
> **상세 계약을 옮겨 적지 않았습니다.** 기능마다 정본 문서를 링크했습니다. 링크가 없는 항목만 여기에 전체 계약이 있습니다.

---

## 1. 문서 기준

| 항목 | 값 |
| --- | --- |
| 기준 커밋 | `origin/develop` **`0ffb7c0`** (2026-09-11 `git fetch` 실측) |
| 지라에서 직접 확인한 김재원 담당 이슈 | **83건** (accountId `712020:1510b171-71ab-4244-909b-48a767b48010`) |
| 개별 상세까지 읽은 이슈 | **83건** (본문·상위 이슈·연결 이슈·댓글) |
| 이 문서가 다루는 엔드포인트 | **58개** (`@GetMapping` 등 실제 핸들러 수) |
| 저장소의 Controller | 23개 (전부 김재원 담당은 아닙니다) |
| 작성일 | 2026-09-11 |

### 1-1. 이 문서를 쓴 방법

지라 이슈 본문은 **요구사항 확인 자료**로만 썼고, **연동 계약은 전부 현재 `origin/develop` 의 Controller·DTO·Enum 에서 옮겼습니다.**
지라 본문과 코드가 다른 곳은 §2 대조표와 §15 에 따로 적었습니다. **코드에 없는 엔드포인트·필드는 이 문서에 없습니다.**

### 1-2. 기존 문서와의 관계 — 어디를 보면 상세 계약이 있는가

`Docs/Api/` 에 FE 인수인계 문서가 **9개**(상세 계약 8 + Vue 예제 1) 이미 있습니다. **이 문서는 그것들을 대체하지 않습니다.**

| 기능 | 정본 상세 계약 | 이 문서의 해당 절 |
| --- | --- | --- |
| 차량·차량 모델 | `Docs/Api/차량 API — FE 인수인계.md` | §5-1 |
| 체크리스트·촬영 가이드 | `Docs/Api/가이드 API — FE 인수인계.md` | §5-2 |
| 사고 접수·이력·상세 | `Docs/Api/사고 조회 API — FE 인수인계.md` ⚠️ **낡음** | §5-3 |
| 이미지 업로드·조회 | `Docs/Api/이미지 업로드 API — FE 인수인계.md` | §6 |
| 견적서 검증·질문·PDF·실제 수리비 | `Docs/Api/견적서 검증 API — FE 인수인계.md` | §5-4 |
| 관리자 마스터·규칙 | `Docs/Api/관리자 마스터·규칙 관리 API — FE 인수인계.md` | §9 |
| 관리자 화면 Vue 코드 | `Docs/Api/관리자 마스터·규칙 관리 — Vue 연동 예제.md` | §11 |
| 주소·좌표·장소 검색 | `Docs/Api/위치 API (주소·좌표·장소 검색) — FE 인수인계.md` | §10 |
| 주변 정비소 | `Docs/Api/정비소 검색 API — FE 인수인계.md` | §10 |

**기존 문서에 없어서 이 문서에 전체 계약을 적은 것은 셋뿐입니다.**

1. `GET /api/admin/rules/overview` — §9-3
2. `GET /api/admin/rules/history` — §9-3
3. `GET /api/accidents/me` 의 **목록 전용 7필드** — §5-3 (사고 조회 문서가 이 필드들이 생기기 전에 작성됨)

### 1-3. 기존 문서에서 고친 낡은 줄 (2026-09-11)

작성 당시 기존 8개 문서의 **상태 서술**이 낡아 있었습니다. **머리말과, 머리말이 가리키는 상태 블록을 모두 고쳤습니다.** 본문 계약(필드·오류·JSON)은 실제 DTO 와 대조해 차이가 없어 **건드리지 않았습니다.**

| 문서 | 낡았던 서술 | 지금 |
| --- | --- | --- |
| `차량 API` · `가이드 API` | 머리말 "**`develop` 에 없다**" | 머지 완료로 정정 |
| `차량 API` | ⚠️ 절 "호출해 볼 수 없다" (미머지 + 로그인 없음) | **둘 다 해소**로 정정 |
| `사고 조회 API` | ⚠️ 절 "로그인해도 **500**" | `-422` 로 해소됨을 명시 |
| `사고 조회 API` | 썸네일 ❌ · 예상 수리비 ❌ | 썸네일 ✅ · 예상 비용 **"필드만 있고 값은 `null`"** 로 정정하고 §5-3 으로 링크 |
| `견적서 검증 API` | ⚠️ 절 "로그인해도 **500**" + 저장소·워커 없음 | **셋 다 코드 있음**, 남은 것은 환경변수임을 표로 정리 |
| `정비소 검색 API` | "워크트리 미커밋" · "카카오 **미검증**" | 머지 완료 · **실호출로 검색어 확정됨**(종단 스모크만 미검증)으로 정정 |
| `관리자 API` | "**아직 커밋되지 않았습니다**" | 머지 완료 + **마이그레이션 선적용 경고**로 교체, 빠진 2개 엔드포인트를 §9-3 으로 링크 |
| `위치 API` · `관리자 API` | 기준 커밋 `25dccdd` / `4a0836d` · 꼬리말 "커밋 전" | `0ffb7c0` 로 갱신 |

**`이미지 업로드 API` 와 `Vue 연동 예제` 는 머리말이 이미 정확해 고치지 않았습니다.**

---

## 2. Jira·코드 구현 대조표

판정 기준 — **구현 완료**: 인수 조건과 실제 HTTP 경로를 충족하고 테스트가 있음 / **부분 구현**: 일부만 있거나 운영 호출 경로가 없음 / **미구현**: 담당 코드 없음 / **명세 불일치**: 지라와 코드가 다름 / **FE 비대상**: 프론트엔드가 호출하지 않음.

| Jira 키 | 기능 | Jira 상태 | 코드 판정 | FE 호출 | 코드 근거 | 주의사항 |
|---|---|---|---|---|---|---|
| -8 | 시스템 아키텍처 작성 | 완료 | FE 비대상 | ✕ | `Docs/Architecture/system-architecture.png` | presigned 직접 업로드·비동기 분석 결정의 출처 |
| -18 | Docs 폴더 정리 | 완료 | FE 비대상 | ✕ | `Docs/` 구조 | — |
| -112 | 제조사·차량명·연식 등록(스토리) | 완료 | 구현 완료 | ○ | `VehicleController`·`VehicleModelController` | API 5종 |
| -113 | 차량 마스터 51행 시드 | 완료 | FE 비대상 | ✕ | `Docs/Erd/vehicle_model_seed.sql` | 제조사 12개사 |
| -114 | 모델→차급 4단계 매핑표 | 완료 | FE 비대상 | ✕ | 같은 시드의 `car_class` | 차급은 **4단계** — 경·소·준중·중·대형이 아닙니다 |
| -115 | 차량 등록·수정·삭제 API | 완료 | 구현 완료 | ○ | `VehicleController` 4개 메서드 | `modelId` 수정 시도는 400 |
| -116 | 차량 모델 목록 조회 API | 완료 | 구현 완료 | ○ | `VehicleModelController.findAll` | **정렬 보장 없음** — FE 재정렬 |
| -121 | 사고 현장 체크리스트(스토리) | 완료 | 구현 완료 | ○ | `ChecklistController` | **비인증** |
| -122 | 체크리스트 조회 API | 완료 | 구현 완료 | ○ | `ChecklistResponse`·`ChecklistStep` | `items` 가 문자열 배열 — 항목 id 없음 |
| -123 | 각도별 촬영 가이드(스토리) | 완료 | **부분 구현** | ○ | `ShootingGuideController` | **오버레이·예시 이미지를 서버가 주지 않습니다** (URL 도 없음) |
| -124 | 촬영 가이드 조회 API | 완료 | 구현 완료 | ○ | `ShootingGuideResponse` | 촬영 주의사항 5개는 응답에 **없음** |
| -125 | 촬영 품질 안내(스토리) | 완료 | **부분 구현** | △ | `ImageQualityAssessor` | `app.image-quality.enabled=false` — 항상 미발동 |
| -126 | 판정 임계값 외부화 | 완료 | 구현 완료 | ✕ | `common/config/ImageQualityProperties` | 지라의 "소비자 없음" 은 **낡음** — `AccidentImageIngestService` 가 씁니다 |
| -137 | 다각도 이미지 업로드(스토리) | 완료 | 구현 완료 | ○ | `AccidentImageController` | **HEIC 거절** · 20장 · 20MB |
| -139 | 사고 이미지 S3 업로드 연동 | 완료 | 구현 완료 | ✕ | `S3AccidentImageStorage` | 실제 S3 PUT **미검증** |
| -141 | 진행률 표시·재시도(스토리) | 완료 | **부분 구현** | ○ | `AccidentImageListResponse` | 4상태→**2상태** · 퍼센트 진행률 BE 미제공 |
| -142 | Presigned URL 발급 API | 완료 | 구현 완료 | ○ | `IssuedUploadUrl` | **신고 크기와 1바이트라도 다르면 S3 403** |
| -143 | 파일별 업로드 상태 관리 | 완료 | 구현 완료 | ○ | `AccidentImageController.list` | `PENDING`/`COMPLETED` 2값 |
| -149 | 사고 차량 선택·입력(스토리) | 완료 | 구현 완료 | ○ | `AccidentController.create` | 즉시 입력이 **영구 차량을 만듭니다** |
| -150 | 사고 접수 생성 API | 완료 | 구현 완료 | ○ | `AccidentCreateRequest` | 두 분기 중 **정확히 하나** |
| -151 | 차량 정보 스냅샷 저장 | 완료 | 구현 완료 | ✕ | `Accident` 스냅샷 6열 | 응답의 차량 필드 출처 |
| -152 | 검색 조건 전달 연동 테스트 | 완료 | **명세 불일치** | ✕ | `AccidentVehicleSearchConditionResolver` | 본문은 "유사사례 API 미구현" 이나 **지금은 `SimilarCaseController` 가 있습니다**(서보영) |
| -186 | 차량 유무·분석 적합성(스토리) | 해야 할 일 | 미구현 | ✕ | 없음 | — |
| -187 | 전체 제외 시 실패·재촬영 안내 | 해야 할 일 | 미구현 | ✕ | 없음 | — |
| -216 | 사고 건 단위 데이터 저장(스토리) | 해야 할 일 | 미구현 | ✕ | 없음 | — |
| -218 | 분석 결과 JSON 적재 로직 | 해야 할 일 | 미구현 | ✕ | 없음 | `analysis_job`·`damaged_part` **엔티티 자체가 없습니다** |
| -223 | 서비스 사고 데이터 증분 적재 | 해야 할 일 | 미구현 | ✕ | 없음 | — |
| -224 | 회원 사고 이력 조회(스토리) | 진행 중 | 구현 완료 | ○ | `AccidentController.findMine`·`findOne` | 예상 비용만 값 없음 |
| -225 | 사고 이력 목록 조회 API | 진행 중 | **부분 구현** | ○ | `AccidentSummaryResponse` 17필드 | **`estimatedCostMin/Median/Max` 가 항상 `null`** |
| -226 | 번호판·얼굴 자동 블러(스토리) | 완료 | 미구현(범위 밖) | ✕ | `ImageVariant.BLURRED` 열거값뿐 | 생성 코드 0건 |
| -227 | 블러본·원본 분리 저장 | 완료 | 미구현(범위 밖) | ✕ | 없음 | 나눌 두 파일이 없음 |
| -228 | 유사 사례에 블러본만 노출 | 완료 | 미구현(범위 밖) | ✕ | 없음 | 검증 대상 없음 |
| -248 | 차종·부품별 수리비 통계(스토리) | 해야 할 일 | 미구현 | ✕ | 없음 | `repair_cost_stat` 은 AI-Hub 적재분만 |
| -249 | 분포 지표 산출 쿼리 | 해야 할 일 | 미구현 | ✕ | 없음 | — |
| -250 | `@Scheduled` 일 1회 배치 | 해야 할 일 | 미구현 | ✕ | 없음 | — |
| -251 | 분산 처리 벤치마크 대체 근거 | 해야 할 일 | 미구현 | ✕ | 없음 | 발표 자료 |
| -277 | 마스터 관리(에픽) | 해야 할 일 | **명세 불일치** | ✕ | 하위 -362·-365 완료·머지 | **에픽 상태만 갱신되지 않았습니다** |
| -305 | 견적서 이미지·PDF 업로드(스토리) | 완료 | 구현 완료 | ○ | `EstimateValidationController` | 업로드는 설정 2개 필요 |
| -306 | 파일 업로드·직접 입력 API | 완료 | 구현 완료 | ○ | `registerManual`·`registerFile` | multipart 10MB 상한(스프링) |
| -307 | 사고 연결·다중 견적서 등록 | 완료 | 구현 완료 | ○ | `EstimateValidationService` | 한 사고에 여러 장 허용 |
| -310 | 과다·불필요 항목 표시(스토리) | 완료 | 구현 완료 | ○ | `EstimateValidationEngine` | 문구는 "확인 권장" 까지 |
| -311 | 과다 청구 검출(OVER_P75) | 완료 | 구현 완료 | ○ | `ValidationFlag.OVER_P75` | P75 **초과**만. 같으면 정상 |
| -312 | 분석에 없는 부품 교환 검출 | 완료 | 구현 완료 | △ | `ValidationFlag.NOT_IN_ANALYSIS` | 분석 결과가 없어 **실사용에서 발동 0** |
| -313 | 중복 공임 검출 | 완료 | 구현 완료 | ○ | `ValidationFlag.DUPLICATE_LABOR` | 첫 항목은 정상 |
| -314 | 검증 요약·등급(스토리) | 완료 | 구현 완료 | ○ | `ValidationGrade` 3값 | `gradeDisplayName` 을 서버가 줍니다 |
| -315 | 3단계 등급 설정값 외부화 | 완료 | 구현 완료 | ✕ | `EstimateValidationProperties` | 지금은 DB 규칙이 우선(-367) |
| -316 | 총액 차이·항목 수·저장·조회 API | 완료 | 구현 완료 | ○ | `ValidationResultResponse` | `aiTotal*` 는 현재 항상 `null` |
| -317 | 정비소 확인 질문 생성(스토리) | 완료 | 구현 완료 | ○ | `RepairShopQuestionGenerator` | 질문 수는 배열 길이로. `reviewItemCount` 아님 |
| -318 | 질문 자동 생성·조회 API | 완료 | 구현 완료 | ○ | `ValidationQuestionResponse` | **`data` 가 바로 배열입니다** |
| -319 | 검증 이력·실제 수리비(스토리) | 완료 | 구현 완료 | ○ | `AccidentController`·`CostComparisonController` | — |
| -320 | 실제 수리비 입력 API | 완료 | 구현 완료 | ○ | `ActualRepairCostRequest` | **PUT 전체 교체** — 세 필드 전부 필수 |
| -321 | AI·정비소·실제 금액 비교 조회 | 완료 | 구현 완료 | ○ | `CostComparisonResponse` | `aiEstimate` 는 현재 비어 있습니다 |
| -322 | 실제 금액 통계 적재 연동 | 완료 | 미구현(범위 밖) | ✕ | `export()` 운영 호출 0건 | 배분 방향 미확정 |
| -349 | 사고 데이터·피드백 검수(스토리) | 해야 할 일 | 미구현 | ✕ | 없음 | — |
| -350 | 검수 대기 큐 적재 | 해야 할 일 | 미구현 | ✕ | 없음 | — |
| -352 | 승인·반려 처리 | 해야 할 일 | 미구현 | ✕ | 없음 | — |
| -362 | 차량·부품·작업 코드 마스터(스토리) | 완료 | **부분 구현** | ○ | `admin/controller/*` | **표준 작업 코드·사고 유형 미구현** |
| -363 | 코드 등록·수정·비활성화 API | 완료 | 구현 완료 | ○ | `AdminVehicleModelController`·`AdminPartCodeController` | **삭제 API 없음**(의도) |
| -364 | 부품명 매핑 관리 | 완료 | 구현 완료 | ○ | `AdminPartNameMappingController` | 식별자가 **경로가 아니라 `?rawName=`** |
| -365 | 수리 방식·이상 탐지 규칙(스토리) | 완료 | **부분 구현** | ○ | `AdminRepairMethodRuleController` 등 | **수리 방식 규칙을 부르는 운영 코드 0건** |
| -366 | 규칙 조회·수정 API·범위 검증 | 완료 | **명세 불일치** | ○ | `AdminRuleController` 외 | **지라 본문의 경로가 실제와 다릅니다** — §9-3 |
| -367 | 변경 이력·즉시 반영 검증 | 완료 | 구현 완료 | ○ | `EstimateValidationRuleProvider` | 이상 탐지만 즉시 반영 |
| -377 | 관리자 행위 기록(스토리) | 해야 할 일 | 구현 완료 | ○ | `AdminAuditLogController` | **상태만 갱신되지 않았습니다** |
| -378 | 관리 행위별 감사 로그 기록 | 완료 | 구현 완료 | ○ | `audit/service/AuditLogService` | 행위자는 세션에서. 위조 불가 |
| -386 | 블러 처리 및 S3 업로드 | 완료 | 미구현(중복) | ✕ | -139 과 중복 | 블러는 -226 |
| -388 | 노출 경로가 블러본만 쓰는지 검증 | 완료 | 구현 완료 | ○ | `AccidentImageAssetResponse.exposed` | 응답에서 `ORIGINAL` 제외. **"블러본만" 은 미달** |
| -398 | 검증 결과 PDF 생성(스토리) | 완료 | 구현 완료 | ○ | `EstimateValidationController.pdf` | **기본 비활성** (`VALIDATION_PDF_ENABLED=false`) |
| -399 | 리포트 테이블 저장·상태 전이 | 완료 | 구현 완료 | ✕ | `EstimateValidationReport` | 재시도 상한 3 |
| -400 | PDF 템플릿·고지 문구 | 완료 | 구현 완료 | ✕ | `validation-report.html` | 고지 문구는 화면과 같은 상수 |
| -401 | Presigned 다운로드·소유자 검사 | 완료 | 구현 완료 | ○ | 302 + `Location` | **미완료·실패 모두 409** |
| -405 | Spring Boot 프로젝트 초기 설정 | 완료 | FE 비대상 | ✕ | `backend/` | Spring Boot 4.1.1 · Java 21 |
| -422 | `CurrentMemberProvider` 스텁 교체 | 완료 | 구현 완료 | ✕ | `common/security/CurrentMemberProvider` | **이전의 "로그인해도 500" 이 해소됐습니다** |
| -423 | GMS 경유 LLM 공통 계층 | 완료 | 구현 완료 | ✕ | `common/llm/*` | 키 없으면 빈 미생성 → 503 |
| -425 | EXIF 보정·리사이즈본 생성 | 완료 | 구현 완료 | ✕ | `AccidentImagePreprocessor` | 파생본에서 EXIF·GPS 제거 |
| -426 | 이미지 업로드·조회 FE 연동(스토리) | 완료 | 구현 완료 | ○ | `AccidentImageResponse` | 조회 URL·`angleCode`·`failureCode` |
| -427 | 조회용 presigned GET URL | 완료 | 구현 완료 | ○ | `AccidentImageAssetResponse.url` | `ORIGINAL` URL 은 절대 안 나옵니다 |
| -428 | 촬영 각도 영구 저장 | 완료 | 구현 완료 | ○ | `AccidentImage.angleCode` | **발급 시점에 저장**. 수정 API 없음 |
| -429 | 업로드 제약 위반 `failureCode` | 완료 | 구현 완료 | ○ | `GlobalExceptionHandler` | `error.code` 에 사유 11종 |
| -431 | CORS 허용 오리진 프로퍼티화 | 완료 | 구현 완료 | ✕ | `CorsProperties` | 배포 시 `CORS_ALLOWED_ORIGINS` 필수 |
| -432 | 항목명→표준 작업 코드 동의어 사전 | 완료 | 구현 완료 | ✕ | `PartNameNormalizer` 등 | 모호한 표기는 사전에서 제외 |
| -443 | 주변 정비소 지도 검색(스토리) | 해야 할 일 | **부분 구현** | ○ | `RepairShopController` | **"지정 지역 기준" 은 FE 2단계** · 상태 미갱신 |
| -444 | 카카오 로컬 API 연동 | 완료 | 구현 완료 | ○ | `LocationController` 5종 | 축 순서 주의 |
| -445 | 거리 계산·가까운 순 정비소 목록 | 완료 | 구현 완료 | ○ | `GET /api/repair-shops` | `query` 를 보내도 무시 |

### 2-1. 판정 집계

| 판정 | 건수 |
| --- | --- |
| 구현 완료 | **50** |
| 부분 구현 | **7** (-123 · -125 · -141 · -225 · -362 · -365 · -443) |
| 미구현 | **17** |
| 명세 불일치 | **3** (-152 · -277 · -366) |
| FE 비대상 | **6** (-8 · -18 · -113 · -114 · -126 · -405) |
| 합계 | **83** |

`FE 호출` 열의 `△` 는 **엔드포인트는 있으나 현재 조건에서 결과가 나오지 않는다**는 뜻입니다.

---

## 3. 공통 API 규약

### 3-1. 응답 봉투

```json
{ "data": { } }
```

```json
{ "error": { "code": "NOT_FOUND", "message": "사고를 찾을 수 없습니다." } }
```

`ApiResponse` · `ErrorResponse` 에서 확인했습니다. **오류를 HTTP 200 으로 내려보내지 않습니다.**

`data` 가 **객체가 아니라 배열**인 곳이 셋 있습니다. 나머지는 전부 객체입니다.

| 엔드포인트 | `data` 의 형태 |
| --- | --- |
| `GET /api/estimate-validations/{validationId}/questions` | 배열 |
| `GET /api/locations/reverse-geocode` | 배열 |
| `GET /api/locations/category-groups` · `GET /api/admin/repair-codes` | 배열 |

### 3-2. JSON 표기와 타입

| 항목 | 규약 |
| --- | --- |
| 키 표기 | **camelCase** |
| 시각 | `Instant` → ISO-8601 UTC (`2026-09-11T04:12:33.412Z`). `createdAt`·`completedAt`·`expiresAt`·`actualCostRecordedAt` |
| 날짜 | `LocalDate` → `YYYY-MM-DD`. `repairCompletedDate`·`actualRepairCompletedDate` |
| 금액 | 전부 **정수(원)**. 소수 없음 |
| 좌표 | `BigDecimal` — JSON 숫자. **`latitude` → `longitude` 순서** |
| 없는 값 | `null`. **0 으로 채우지 않습니다** |

### 3-3. enum 값 — 서버가 주는 그대로

```
VehicleType           SEDAN · SUV · VAN · TRUCK
CarClass              CityCar · Compact · Mid-size · Full-size   ← 하이픈 있음. 대문자 아님
VehicleInputType      REGISTERED · DIRECT
AccidentHistoryStatus RECEIVED · IMAGES_UPLOADED · ESTIMATED · REPAIR_RECORDED
ImageVariant          ORIGINAL · RESIZED · THUMBNAIL · BLURRED
ImageUploadState      PENDING · COMPLETED
ImageProcessingStatus COMPLETED · ALREADY_COMPLETED · FAILED
ImageQualityStatus    PASS · WARN
EstimateFileType      IMAGE · PDF · MANUAL
ValidationStatus      QUEUED · PROCESSING · COMPLETED · FAILED
ValidationGrade       APPROPRIATE · CAUTION · NEEDS_REVIEW
ValidationFlag        OVER_P75 · NOT_IN_ANALYSIS · DUPLICATE_LABOR · UNMAPPED_ITEM · INSUFFICIENT_REFERENCE
angleCode (문자열)     FRONT · REAR · LEFT · RIGHT · FRONT_LEFT · FRONT_RIGHT
                      REAR_LEFT · REAR_RIGHT · DAMAGE_CLOSE
```

`CarClass` 만 표기가 다릅니다 — `Mid-size` 처럼 **하이픈이 들어간 원문 그대로** 내려옵니다. 화면 라벨로 쓰려면 FE 매핑이 필요합니다.

`ValidationGrade` 는 `gradeDisplayName`(`적정 범위`·`주의`·`확인 필요`)을 **서버가 함께 주므로** FE 가 한글 라벨을 만들 필요가 없습니다. 반대로 `AccidentHistoryStatus` 는 **한글 라벨을 주지 않습니다** — 화면 문구는 FE 가 정합니다.

### 3-4. 소유권 — 403 이 아니라 404

남의 자원과 없는 자원을 **모두 404** 로 돌려줍니다. 403 은 "그 자원이 존재한다" 를 알려주기 때문입니다.
소유자 검사는 Repository 쿼리 조건에 있어 목록·집계에도 남의 데이터가 새지 않습니다.

**403 이 나오는 경우는 둘뿐입니다.**

| 상황 | `error.code` |
| --- | --- |
| `ROLE_USER` 가 `/api/admin/**` 호출 | `FORBIDDEN` |
| 소셜 로그인만 하고 가입을 안 끝낸 세션 | `SIGNUP_REQUIRED` |

### 3-5. 낙관적 잠금

관리자 API 는 `version` 을 주고받습니다. 내가 읽은 뒤 남이 먼저 바꿨으면 **409 `VERSION_CONFLICT`** 입니다. 다시 읽어 다시 저장하면 됩니다.

---

## 4. 인증·세션·권한

### 4-1. 세션 쿠키

**토큰이 아니라 서버 세션입니다.** 소셜 로그인 성공 시 `SESSION` 쿠키(HttpOnly)를 받고, 이후 모든 요청이 그 쿠키로 인증됩니다.

```js
// src/api/http.js
import axios from 'axios'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  withCredentials: true   // 없으면 쿠키가 안 실려 전부 401 입니다
})
```

`withCredentials: true` 가 **필수**입니다. 서버가 `setAllowCredentials(true)` 라서 브라우저가 쿠키를 싣지 않으면 보호 API 가 전부 401 이 됩니다.

세션 만료는 `server.servlet.session.timeout=30m` 입니다.

### 4-2. 인가 규칙 (`SecurityConfig` 실측)

| 경로 | 필요 권한 |
| --- | --- |
| `/api/guides/checklist` · `/api/guides/shooting` | **없음 (비인증 허용)** |
| `/api/auth/signup` (GET·POST) | 소셜 로그인만 된 가입 대기 세션 |
| `/api/admin/**` | `ROLE_ADMIN` |
| **그 밖의 모든 `/api/**`** | `ROLE_USER` 또는 `ROLE_ADMIN` |
| `/internal/**` · `/inference/**` | **전면 차단** — FE 가 부를 일 없음 |

비인증 허용은 **가이드 2종뿐입니다.** `/api/guides/**` 전체가 아니라 두 경로만 열려 있습니다.
차량·사고·이미지·견적서 검증·위치·정비소는 **전부 로그인이 필요합니다.**

> 위치·정비소 API 를 비로그인에 열지 않은 이유는 **카카오 일일 쿼터** 입니다. 열면 누구나 우리 키의 호출 회수를 태울 수 있습니다.

### 4-3. 401 과 403 의 차이 — 본문 모양이 다릅니다

| 상태 | 본문 | FE 처리 |
| --- | --- | --- |
| **401** | **본문 없음** (`res.sendError(401)`) | **파싱하지 말 것.** 로그인 화면으로 |
| **403 `FORBIDDEN`** | 공통 `ErrorResponse` | "권한이 없습니다" |
| **403 `SIGNUP_REQUIRED`** | 공통 `ErrorResponse` | **가입 완료 화면으로** 보냅니다 |

**401 에 본문이 없는 것은 의도된 현재 동작입니다.** `error.code` 를 읽으려 하면 `undefined` 입니다. `error.response.status` 로 판단하세요.

### 4-4. 김재원 담당 범위의 경계

`AuthController`·`MemberController` 자체는 김재원 담당이 아닙니다. 이 영역에서 김재원이 한 것은 **`S15P21A307-422` 하나** — 보호 API 가 회원 ID 를 얻는 지점(`CurrentMemberProvider`)을 스텁에서 실제 구현으로 교체한 작업입니다.

**FE 에게 중요한 이유** — 그 전에는 로그인에 성공해도 **인증이 필요한 API 22개가 전부 HTTP 500** 이었습니다. 지금은 정상입니다. 예전 문서·메모에 "로그인해도 500" 이라고 적혀 있으면 **낡은 서술입니다.**

---

## 5. 기능별 API

### 5-1. 차량·차량 모델 — 5개

상세 계약: `Docs/Api/차량 API — FE 인수인계.md`

| 메서드·경로 | 성공 | 한 줄 설명 |
| --- | --- | --- |
| `POST /api/vehicles` | 201 | 내 차량 등록. 본문은 `{ modelId, modelYear }` 둘뿐 |
| `GET /api/vehicles/me` | 200 | 내 차량 목록. `{ vehicles: [...] }` |
| `PATCH /api/vehicles/{vehicleId}` | 200 | **연식만** 수정 |
| `DELETE /api/vehicles/{vehicleId}` | 204 | 소프트 삭제. 재삭제도 204 |
| `GET /api/vehicle-models` | 200 | 선택 가능한 모델 전체. `{ vehicleModels: [...] }` |

#### 화면 구현 주의사항

- **목록에 없는 차량은 등록할 수 없습니다.** `modelId` 가 필수 FK 라 "기타 직접 입력" 선택지를 만들면 안 됩니다.
- **차량 유형·차급은 표시 전용입니다.** 모델을 고르면 따라옵니다. 드롭다운으로 받지 마세요.
- `PATCH` 에 `modelId` 를 실으면 **400** 입니다(`@Null` 제약). 연식만 보내세요.
- **정렬 순서에 의미가 없습니다.** DB collation 이 `en_US.utf8` 이라 한글이 글자 수 순으로 나옵니다. 제조사 그룹핑(현대·기아·제네시스·기타 국산·수입)도 서버가 주지 않으므로 **FE 상수로 처리**합니다.
- `GET /api/vehicle-models` 는 파라미터가 없고 활성 모델 전체(51행, 약 4.9KB)를 한 번에 줍니다. 제조사 드롭다운을 바꿀 때마다 재요청하지 말고 **한 번 받아 FE 에서 거르세요.**
- 같은 모델·연식을 두 번 등록해도 막지 않습니다. 실제로 같은 차종 2대를 가질 수 있기 때문입니다.

### 5-2. 사고 현장 가이드 — 2개 (비인증)

상세 계약: `Docs/Api/가이드 API — FE 인수인계.md`

| 메서드·경로 | 성공 | 한 줄 설명 |
| --- | --- | --- |
| `GET /api/guides/checklist` | 200 | 사고 현장 체크리스트 5단계 12항목 |
| `GET /api/guides/shooting` | 200 | 권장 10컷 · 각도 코드 · 차량 유형별 오버레이 세트 |

```
checklist  { steps: [ { order, title, items: [문자열] } ] }
shooting   { recommendedCount, overlaySets: [ { vehicleType, overlaySet } ],
             shots: [ { order, angleCode, title, description, closeUp } ] }
```

#### 화면 구현 주의사항

- **로그인 전에 부를 수 있는 유일한 두 API 입니다.** 랜딩·비로그인 화면에 쓸 수 있습니다.
- **체크 상태를 서버가 저장하지 않습니다.** 저장하려면 브라우저 `localStorage` 를 쓰고, 저장 키는 `order + 배열 index` 밖에 없습니다(항목 id 가 없습니다). **문안이 바뀌면 저장된 체크가 어긋납니다.**
- **오버레이 이미지와 예시 사진을 서버가 주지 않습니다. URL 도 없습니다.** `overlaySet` 은 `"SEDAN"` / `"SUV"` 같은 **코드 문자열**이고, 실제 실루엣 이미지는 **FE 번들에 두고 코드로 꺼내 써야 합니다.** 카메라 화면 위 실시간 오버레이가 네트워크 왕복을 기다릴 수 없어 이렇게 정했습니다.
- `overlaySets` 는 4행(`SEDAN→SEDAN`, `SUV→SUV`, `VAN→SUV`, `TRUCK→SUV`)입니다. **VAN·TRUCK 은 SUV 실루엣으로 폴백**합니다. 이 매핑을 FE 상수로 복사하지 말고 응답을 쓰세요 — 실루엣이 추가되면 FE 배포 없이 서버만 바꿉니다.
- **촬영 주의사항 5개 문장은 응답에 없습니다.** 필요하면 FE 문구로 넣으세요.
- `shots[].description` 10개 중 **2개만 기획 확정 문안**이고 8개는 백엔드가 같은 어투로 쓴 것입니다. 그대로 화면에 띄우기 전에 기획 확인을 받으세요.

### 5-3. 사고 접수·이력 — 4개

상세 계약: `Docs/Api/사고 조회 API — FE 인수인계.md` ⚠️ **목록 전용 7필드가 빠져 있습니다. 아래를 정본으로 쓰세요.**

| 메서드·경로 | 성공 | 한 줄 설명 |
| --- | --- | --- |
| `POST /api/accidents` | 201 | 사고 접수. 등록 차량 선택 또는 즉시 입력 |
| `GET /api/accidents/me` | 200 | 내 사고 이력 목록(페이지) |
| `GET /api/accidents/{accidentId}` | 200 | 사고 상세 |
| `PUT /api/accidents/{accidentId}/actual-cost` | 200 | 실제 수리비 기록 |

#### `POST /api/accidents` 두 분기

```js
// 등록 차량 선택
await api.post('/api/accidents', { vehicleId: 12 })

// 즉시 입력 — 마스터와 정확히 일치해야 합니다
await api.post('/api/accidents', {
  directVehicle: { manufacturer: '현대', modelName: '아반떼', modelYear: 2021 }
})
```

- **둘 중 정확히 하나**만 보내야 합니다. 둘 다 또는 둘 다 없으면 400.
- 즉시 입력은 **마스터에 있는 활성 모델과 정확히 일치**해야 합니다. 자유 입력이 아닙니다 — 안 맞으면 400. 그래서 즉시 입력 화면도 **`GET /api/vehicle-models` 의 값에서 고르게** 만드는 편이 안전합니다.
- ⚠️ **즉시 입력은 영구 차량을 만듭니다.** 접수만 하려고 입력한 차가 `GET /api/vehicles/me` 에 나타납니다. 같은 차로 두 번 즉시 입력하면 **차량이 두 대 생깁니다.** 사용자에게는 중복으로 보입니다. (§15 참고)

#### 목록·상세는 **응답 타입이 다릅니다**

공통 10필드는 같고, **목록에만 7필드가 더 있습니다.**

```
공통 10개 (상세·목록 모두)
accidentId · vehicleId · vehicleInputType · modelId · manufacturer
modelName · vehicleType · carClass · modelYear · createdAt

목록 전용 7개 (GET /api/accidents/me 에만)
status · imageCount · thumbnailUrl · thumbnailExpiresAt
estimatedCostMin · estimatedCostMedian · estimatedCostMax
```

```json
{
  "data": {
    "accidents": [
      {
        "accidentId": 41, "vehicleId": 12, "vehicleInputType": "REGISTERED",
        "modelId": 7, "manufacturer": "현대", "modelName": "아반떼",
        "vehicleType": "SEDAN", "carClass": "Mid-size", "modelYear": 2021,
        "createdAt": "2026-09-11T04:12:33.412Z",
        "status": "IMAGES_UPLOADED",
        "imageCount": 8,
        "thumbnailUrl": "https://a307-service.s3.ap-northeast-2.amazonaws.com/...",
        "thumbnailExpiresAt": "2026-09-11T04:22:33.412Z",
        "estimatedCostMin": null,
        "estimatedCostMedian": null,
        "estimatedCostMax": null
      }
    ],
    "page": 0, "size": 20, "totalElements": 21, "totalPages": 2, "hasNext": true
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `status` | 저장 컬럼이 아니라 **유도값** — 이미지 0장이면 `RECEIVED`, 이미지가 있고 견적이 없으면 `IMAGES_UPLOADED`, 견적이 있으면 `ESTIMATED`, 실제 수리비가 있으면 `REPAIR_RECORDED`. 뒤가 앞을 덮습니다 |
| `imageCount` | **완료 통보를 못 받은 이미지도 셉니다** — 사용자가 올린 장수 |
| `thumbnailUrl` | 가장 먼저 올린 이미지의 `THUMBNAIL` presigned GET. **DB 에 저장하지 않고 응답마다 서명**합니다 |
| `thumbnailExpiresAt` | 이 시각이 지나면 URL 이 깨집니다. 목록을 다시 받으세요 |
| `estimatedCost*` | **현재 항상 `null`** — §15 |

#### 화면 구현 주의사항

- **차량 필드는 전부 접수 당시 스냅샷입니다.** 차량 연식을 수정하거나 차량을 삭제해도, 모델 마스터가 바뀌어도 과거 사고의 차량 표시는 **변하지 않습니다.** 예외는 `vehicleId` 하나 — 이것만 현재 연관 차량에서 옵니다.
- **폐차·매각한 차량의 사고도 이력에 남습니다.** 목록에서 빠지지 않습니다.
- **`status` 에 한글 라벨이 없습니다.** 배지 문구는 FE 가 정합니다.
- `thumbnailUrl` 이 `null` 인 경우는 셋입니다 — 이미지가 없거나, `RESIZED`/`ORIGINAL` 만 있거나, 서명에 실패했거나. **그 건만 `null` 이고 목록 전체는 정상 응답합니다.** 빈 이미지 자리를 그릴 준비를 하세요.
- `estimatedCost*` 가 `null` 일 때 **"0원" 으로 표시하면 안 됩니다.** "산정 전" 으로 처리하세요.

#### `PUT /api/accidents/{accidentId}/actual-cost`

**부분 갱신이 아니라 전체 교체입니다.** 세 필드 전부 필수입니다.

| 이름 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `actualRepairCost` | body | 정수 | ○ | 0보다 커야 합니다 |
| `repairCompletedDate` | body | `YYYY-MM-DD` | ○ | **미래일 불가** |
| `repairShopName` | body | 문자열 | ○ | 100자 이하. 공백만은 불가 |

```js
export async function recordActualCost(accidentId, payload) {
  const response = await api.put(`/api/accidents/${accidentId}/actual-cost`, payload)
  return response.data.data
}
```

응답은 `{ accidentId, actualRepairCost, repairCompletedDate, repairShopName, actualCostRecordedAt }` 입니다.

⚠️ **같은 값을 두 이름으로 받게 됩니다.** 이 API 의 응답은 `repairCompletedDate` 인데, 검증 결과 조회(`/result`)의 응답은 `actualRepairCompletedDate` 입니다. **매핑 실수가 나기 쉬운 지점입니다.**

### 5-4. 견적서 검증 — 9개

상세 계약: `Docs/Api/견적서 검증 API — FE 인수인계.md`

| 메서드·경로 | 성공 | 한 줄 설명 |
| --- | --- | --- |
| `POST /api/estimate-validations` (JSON) | **202** | 견적 항목 직접 입력 |
| `POST /api/estimate-validations` (multipart) | **202** | 견적서 이미지·PDF 업로드 |
| `GET /api/estimate-validations/{validationId}` | 200 | **처리 상태** 조회 (폴링 대상) |
| `GET /api/estimate-validations/{validationId}/result` | 200 | 검증 **결과** (완료 후) |
| `GET /api/estimate-validations/{validationId}/questions` | 200 | 정비소 확인 질문 — **`data` 가 배열** |
| `GET /api/estimate-validations/me` | 200 | 내 검증 이력(페이지) |
| `GET /api/estimate-validations/{validationId}/pdf` | **302** | 검증 결과 PDF 다운로드 |
| `DELETE /api/estimate-validations/{validationId}` | 204 | 검증 삭제 (PDF·원본 파일도 함께) |
| `GET /api/accidents/{accidentId}/cost-comparison` | 200 | AI·정비소·실제 금액 3자 비교 |

**하나의 URL 이 `Content-Type` 으로 갈립니다.** `application/json` 이면 직접 입력(`fileType: "MANUAL"`), `multipart/form-data` 면 파일 업로드(`IMAGE`/`PDF`). 응답은 둘 다 `ValidationAcceptedResponse` 로 같습니다.

#### 화면 구현 주의사항

- **`202 Accepted` 입니다.** 등록 즉시 결과가 나오지 않습니다. 응답의 `statusUrl` 로 폴링하세요 — §7.
- `/questions` 는 **`data` 가 바로 배열**입니다. 다른 목록 API 와 다릅니다.
- `/result` 응답에도 같은 `questions` 배열이 들어 있습니다. **결과 화면은 `/result` 한 번만 부르고** `/questions` 는 부분 갱신용으로 쓰세요.
- **화면의 질문 개수는 `questions.length` 로 세세요.** `reviewItemCount` 를 쓰면 안 됩니다 — 한 항목에 사유가 여러 개면 질문도 여러 개 나오지만 `items` 는 대표 플래그 하나만 줍니다.
- 미완료 상태에서 `/questions` 는 **200 + 빈 배열**, `/result` 는 **409** 입니다. 둘의 동작이 다릅니다. 빈 배열로는 "없음" 과 "아직" 을 구별할 수 없으므로 **상태 판단은 `/{validationId}` 로 하세요.**
- `aiTotalMin/Median/Max` 와 `differenceFromMedian` 은 **현재 항상 `null`** 입니다(§15). 화면에 "AI 예상 견적과 비교" 영역을 만들었다면 **비어 있을 때의 문구**가 필요합니다.
- 판정 문구는 **"확인 권장" 까지**입니다. 서버가 "사기"·"바가지"·"과다청구" 같은 단정 표현을 만들지 않고, 코드로 막고 있습니다. **FE 도 그 선을 넘는 라벨을 붙이면 안 됩니다.**
- `legalNotice` 필드가 응답에 들어 있습니다. **화면과 PDF 가 같은 문구를 씁니다** — 직접 쓰지 말고 이 값을 그대로 표시하세요.

---

## 6. 파일 업로드·다운로드

상세 계약: `Docs/Api/이미지 업로드 API — FE 인수인계.md`

### 6-1. 사고 이미지 — 3단계

```
① POST /api/accidents/{accidentId}/images/upload-urls   201  presigned PUT URL 발급
② PUT  {uploadUrl}                                            브라우저 → S3 직접. 백엔드 무관
③ POST /api/accidents/{accidentId}/images               200  완료 통보 (서버가 재검증·전처리)
```

| 메서드·경로 | 성공 | 한 줄 설명 |
| --- | --- | --- |
| `POST /api/accidents/{accidentId}/images/upload-urls` | 201 | 발급. `{ issuedCount, maxCountPerAccident, remainingSlots, files[] }` |
| `POST /api/accidents/{accidentId}/images` | 200 | 완료 통보. `{ requested, succeeded, failed, results[] }` |
| `GET /api/accidents/{accidentId}/images` | 200 | 목록·상태·조회 URL |
| `DELETE /api/accidents/{accidentId}/images/{imageId}` | 204 | 이미지 삭제 |

### 6-2. 🔴 FE 가 모르면 막히는 것 넷

**① 신고한 크기와 정확히 같은 바이트를 올려야 합니다.**

서버가 presigned URL 에 **크기 상한이 아니라 정확값을 서명**합니다(AWS SDK for Java v2 가 presigned POST 를 지원하지 않아 `content-length-range` 를 쓸 수 없습니다).
**1바이트라도 다르면 S3 가 403 `SignatureDoesNotMatch`** 를 줍니다. 파일을 고른 뒤 재인코딩·리사이즈로 크기가 바뀌면 **URL 을 다시 발급받아야 합니다.**
403 을 받으면 **크기 불일치를 가장 먼저 의심하세요.**

**② `requiredHeaders` 키가 소문자입니다.**

```json
"requiredHeaders": { "content-type": "image/jpeg", "content-length": "2048" }
```

프로필 이미지 API 는 `"Content-Type"` 대문자로 줍니다. **키를 하드코딩하지 말고 map 을 순회해 그대로 설정하세요.**
(`content-length` 는 브라우저가 body 에서 자동 계산합니다. 직접 설정하려 하면 브라우저가 거부합니다 — FE 가 할 일은 **신고 크기와 실제 body 크기를 같게 맞추는 것**뿐입니다.)

**③ presigned URL 은 10분입니다. 만료 후 재시도는 2단계입니다.**

| 시점 | 방법 |
| --- | --- |
| 10분 안 | **같은 URL 로 그대로 다시 PUT** |
| 10분 후 | ① `DELETE /images/{imageId}` 로 죽은 행 제거 → ② `POST /upload-urls` 재발급 |

**같은 `imageId` 로 URL 을 재발급하는 엔드포인트가 없습니다.** 그리고 **`PENDING` 행도 20장 슬롯을 차지**하므로, ①을 빠뜨리고 재시도를 반복하면 상한에 막히고 **사용자에게는 이유가 보이지 않습니다.**

**④ HEIC 를 받지 않습니다.**

발급 단계에서 **400 `SERVER_CONVERSION_UNSUPPORTED`** 로 거절합니다. **FE 가 JPEG 로 변환해 올려야 합니다.** (갤럭시는 기본 촬영 포맷이 JPEG 이라 시연 경로에는 영향이 없습니다.)

### 6-3. 업로드 규칙

| 항목 | 값 |
| --- | --- |
| 사고당 최대 | **20장** — 이미 등록된 장수와 **합산**해 판정 |
| 장당 최대 | **20MB** |
| 허용 형식 | **JPG · PNG** (확장자·Content-Type·**매직바이트** 3중 검증) |
| 만료 | 10분 |
| 파생본 | 분석용 긴 변 1600px · 썸네일 320px, 둘 다 JPEG |

**부분 실패를 허용합니다.** 20장 중 3장이 실패해도 17장은 저장됩니다. 개별 실패는 **HTTP 400 이 아니라 `results[].status === "FAILED"`** 입니다. 400·404 는 요청 자체가 잘못된 경우(빈 목록·남의 `imageId`·중복)에만 나옵니다.
이미 완료된 `imageId` 를 다시 보내면 `ALREADY_COMPLETED` 입니다 — **실패한 파일만 다시 통보해도 됩니다.**

### 6-4. 조회 — `ORIGINAL` 은 절대 안 나옵니다

`GET /api/accidents/{accidentId}/images` 의 `images[].assets[]` 에는 **`RESIZED`·`THUMBNAIL` 만** 들어 있습니다. 원본은 EXIF(GPS·기기 정보)가 남아 있어 **응답에서 제외하고, 어댑터가 원본 키 서명 자체를 거부**합니다.

```
assets[] : { variant, url, expiresAt, width, height, fileSize }
```

`url` 은 **DB 에 저장하지 않고 응답을 만들 때마다 서명**합니다. `expiresAt` 이 지나면 목록을 다시 받으세요.

`angleCode` 는 **발급 시점에 영구 저장**되어 조회 응답에 나옵니다. **각도 수정 API 는 없습니다.**

### 6-5. 검증 PDF 다운로드 — 302

```
GET /api/estimate-validations/{validationId}/pdf
```

| 리포트 상태 | 응답 |
| --- | --- |
| `QUEUED` · `PROCESSING` | **409** "완료된 검증 PDF가 없습니다." → "생성 중" 을 그리고 폴링 |
| `FAILED` | **409** "검증 결과 PDF 생성에 실패했습니다." |
| `COMPLETED` | **302** + `Location` 헤더의 presigned URL |

**두 경우 모두 409 이고 메시지로만 구분됩니다.** 실패 여부를 코드로 판단하려면 `GET /api/estimate-validations/{validationId}` 의 `failureReason` 을 보세요.

Axios 는 기본적으로 302 를 따라갑니다. 새 탭으로 열려면 `window.open(url)` 이 간단합니다.

파일명은 `견적검증_{validationId}_{yyyyMMdd}.pdf` 이고 RFC 5987 로 인코딩됩니다. **사용자 입력이 들어가지 않습니다.**

---

## 7. 비동기 처리와 폴링

### 7-1. 어디가 비동기인가

| 흐름 | 즉시 응답 | 폴링 대상 |
| --- | --- | --- |
| 견적서 검증 | `202` + `validationId`·`statusUrl` | `GET /api/estimate-validations/{validationId}` → `status` |
| 검증 PDF | 검증 완료 후 큐에 적재 | `GET .../{validationId}/pdf` 가 409 → 302 로 바뀔 때까지 |

`ValidationStatus` = `QUEUED` → `PROCESSING` → `COMPLETED` \| `FAILED`. `FAILED` 면 `failureReason` 이 채워집니다.

### 7-2. 업로드 진행률은 서버가 모릅니다

presigned 직접 업로드라 **바이트가 브라우저 → S3 로 흐르고 서버는 진행 중인 전송을 보지 못합니다.** 퍼센트 진행률은 **FE 가 만들어야 합니다.**

```js
// fetch 는 업로드 진행 이벤트를 주지 않습니다. XHR 을 써야 합니다.
export function putToS3(uploadUrl, file, requiredHeaders, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', uploadUrl)
    // 키를 하드코딩하지 않습니다 — 서버가 준 그대로 실습니다
    Object.entries(requiredHeaders).forEach(([name, value]) => {
      if (name.toLowerCase() === 'content-length') return // 브라우저가 계산합니다
      xhr.setRequestHeader(name, value)
    })
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => (xhr.status >= 200 && xhr.status < 300)
      ? resolve()
      : reject(new Error(`S3 ${xhr.status}`))
    xhr.onerror = () => reject(new Error('network'))
    xhr.send(file)
  })
}
```

서버가 답할 수 있는 것은 `GET /images` 의 `total`·`completed`·`pending` 까지입니다. **`PENDING` 하나에 FE 관점의 대기·진행·실패가 전부 뭉쳐 있습니다.**

### 7-3. 폴링 — unmount 에서 반드시 끕니다

```vue
<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getValidationStatus } from '@/api/estimateValidations'

const props = defineProps({ validationId: { type: Number, required: true } })
const status = ref('QUEUED')
const failureReason = ref(null)

let timerId = null
let stopped = false

function stopPolling() {
  stopped = true
  if (timerId !== null) {
    clearTimeout(timerId)
    timerId = null
  }
}

async function tick() {
  if (stopped) return
  try {
    const data = await getValidationStatus(props.validationId)
    status.value = data.status
    failureReason.value = data.failureReason
    if (data.status === 'COMPLETED' || data.status === 'FAILED') {
      stopPolling()
      return
    }
  } catch (error) {
    // 401·404 는 되풀이해도 같습니다. 멈추고 화면을 바꿉니다.
    const code = error.response?.status
    if (code === 401 || code === 404) {
      stopPolling()
      return
    }
  }
  // setInterval 을 쓰지 않습니다 — 응답이 느리면 요청이 겹쳐 쌓입니다
  timerId = setTimeout(tick, 2000)
}

onMounted(tick)
onUnmounted(stopPolling)   // 이걸 빠뜨리면 화면을 떠나도 요청이 계속 나갑니다
</script>
```

**`setInterval` 대신 `setTimeout` 재귀**를 쓰세요. 응답이 느릴 때 요청이 겹쳐 쌓이지 않습니다.
**`onUnmounted` 해제는 필수입니다.** 빠뜨리면 화면을 떠난 뒤에도 요청이 계속 나가고, 세션이 끊기면 401 을 무한히 두드립니다.

---

## 8. 검색·정렬·페이지네이션

### 8-1. 0-based 입니다

모든 페이지 파라미터가 **0-based** 입니다. 첫 페이지는 `page=0`.

### 8-2. ⚠️ 페이지 봉투가 네 가지입니다 — 공통 함수를 쓰기 전에 확인하세요

| API | 배열 키 | `totalElements` | `totalPages` | `hasNext` | 그 밖 |
| --- | --- | --- | --- | --- | --- |
| `GET /api/accidents/me` | **`accidents`** | ○ | ○ | ○ | — |
| `GET /api/estimate-validations/me` | `content` | ○ | ○ | **✕ 없음** | — |
| `GET /api/admin/**` | `content` | ○ | ○ | ○ | — |
| `GET /api/locations/**` · `/api/repair-shops` | `content` | ○ | ○ | ○ | **`pageableCount`** 추가 |

- 사고 이력만 배열 키가 **`accidents`** 입니다.
- **검증 이력에는 `hasNext` 가 없습니다.** `page + 1 < totalPages` 로 직접 계산하세요.
- 위치 계열의 `pageableCount` 는 **실제로 페이지를 넘길 수 있는 건수**이고 `totalElements`(카카오 `total_count`)와 다릅니다. 장소 검색은 `pageableCount` 가 **최대 45로 잘립니다.** "총 1,200건" 을 보여 주면서 45건까지만 넘어가는 화면이 되지 않게 하세요.

### 8-3. ⚠️ 상한 초과 처리도 두 가지입니다

| API | 상한 초과 | 음수 `page` | 범위 초과 `page` | 숫자 아닌 값 |
| --- | --- | --- | --- | --- |
| `GET /api/accidents/me` | **절단** (size 100) | **0 으로 보정** | 빈 배열 + 200 | 400 |
| `GET /api/admin/**` | **절단** (size 200) | **0 으로 보정** | 빈 배열 + 200 | 400 |
| `GET /api/locations/**` · `/api/repair-shops` | **절단** (page 45 / size 30·15 / radius 20000) | 보정 | 빈 배열 + 200 | 400 |
| `GET /api/estimate-validations/me` | **절단** (size 100) | **400 으로 거절** | 빈 배열 + 200 | 400 |

**검증 이력만 `page < 0` 또는 `size < 1` 을 400 으로 거절합니다.** 나머지는 조용히 보정합니다.
**범위를 넘은 페이지는 404 가 아니라 200 + 빈 배열입니다.** "목록이 빈 것은 오류가 아니다" 가 이 저장소의 규칙입니다.

### 8-4. 정렬

| API | 정렬 |
| --- | --- |
| `GET /api/accidents/me` | **고정** — `createdAt DESC`, 동시각이면 `accidentId DESC`. 정렬 파라미터 없음 |
| `GET /api/estimate-validations/me` | 고정 (최신순) |
| `GET /api/admin/**` | `sort=속성,desc` 지원. **허용 목록 밖 값은 조용히 무시하고 기본 정렬** |
| `GET /api/admin/audit-logs` · `/api/admin/rules/history` | 최신순 고정 |
| `GET /api/repair-shops` | **항상 거리 오름차순.** 바꿀 수 없습니다 |
| `GET /api/vehicle-models` | **보장 없음** — FE 재정렬 필요 |

관리자 목록의 `sort` 는 **허용 목록 밖 값을 400 으로 거절하지 않고 무시합니다.** 오타를 내면 조용히 기본 정렬로 돌아가므로, 정렬이 안 먹으면 속성 이름부터 확인하세요.

### 8-5. 검색 파라미터

| API | 검색·필터 |
| --- | --- |
| `GET /api/admin/vehicle-models` | `keyword` · `manufacturer` · `vehicleType` · `carClass` · `active` |
| `GET /api/admin/part-codes` | `keyword` · `layoutZone` · `scope` · `active` |
| `GET /api/admin/part-name-mappings` | `keyword` · `partCode` · `partActive` · `scope` |
| `GET /api/admin/repair-method-rules` | `active` |
| `GET /api/admin/audit-logs` | `from` · `to` · `actorMemberId` · `actionType` · `targetType` · `targetId` |
| `GET /api/locations/places` | `query`(필수) · `latitude` · `longitude` · `radius` · `sort` · `categoryGroupCode` |
| `GET /api/repair-shops` | `latitude`·`longitude`(필수) · `radius`. **`query` 를 보내도 무시** |

---

## 9. 관리자 기능

상세 계약: `Docs/Api/관리자 마스터·규칙 관리 API — FE 인수인계.md`
Vue 화면 코드: `Docs/Api/관리자 마스터·규칙 관리 — Vue 연동 예제.md`

**전부 `ROLE_ADMIN` 입니다.** `ROLE_USER` 가 부르면 403 `FORBIDDEN`.

### 9-1. 엔드포인트 28개

| 묶음 | 엔드포인트 |
| --- | --- |
| 차량 모델 (5) | `GET /api/admin/vehicle-models` · `GET .../{modelId}` · `POST` · `PATCH .../{modelId}` · `PATCH .../{modelId}/status` |
| 부품 코드 (5) | `GET /api/admin/part-codes` · `GET .../{partCode}` · `POST` · `PATCH .../{partCode}` · `PATCH .../{partCode}/status` |
| 부품명 매핑 (4) | `GET /api/admin/part-name-mappings` · `POST` · `PATCH ?rawName=` · `DELETE ?rawName=&changeReason=` |
| 수리 방식 규칙 (5) | `GET /api/admin/repair-method-rules` · `GET .../{ruleId}` · `POST` · `PATCH .../{ruleId}` · `PATCH .../{ruleId}/status` |
| 이상 탐지 규칙 (3) | `GET /api/admin/estimate-validation-rules/current` · `GET .../history` · `PATCH .../current` |
| 수리 방식·손상 유형 코드 (3) | `GET /api/admin/repair-codes` · `PATCH .../{codeType}/{code}` · `PATCH .../{codeType}/{code}/status` |
| 감사 로그 (1) | `GET /api/admin/audit-logs` |
| **규칙 통합 (2)** | **`GET /api/admin/rules/overview` · `GET /api/admin/rules/history`** — §9-3 |

### 9-2. 공통 규칙

- **삭제 API 가 없습니다.** 차량 모델·부품 코드·규칙은 **비활성화**로 대신합니다. 과거 차량·분석·견적이 가리키는 대상을 잃지 않게 하려는 의도이고, DB 도 `ON DELETE RESTRICT` 로 한 번 더 막습니다. **화면에 삭제 버튼을 만들지 마세요.**
  - **부품명 매핑만 삭제를 허용합니다.** 잘못 등록한 별칭은 지워야 하고, 지워도 과거 검증 결과는 당시 `partCode` 를 들고 있어 깨지지 않습니다.
- **부품명 매핑의 식별자는 경로가 아니라 쿼리입니다.** `PATCH /api/admin/part-name-mappings?rawName=앞범퍼` 입니다. `DELETE` 는 `changeReason` 까지 **필수 쿼리 파라미터**입니다.
- **상태 변경은 멱등입니다.** 이미 비활성인 코드를 또 비활성화해도 오류가 아니고 감사 이력도 늘지 않습니다.
- **모든 변경이 `audit_log` 에 남습니다.** 행위자는 요청 본문이 아니라 **세션**에서 얻습니다 — body 에 `memberId` 를 넣어도 무시됩니다. 감사 기록에 실패하면 변경도 함께 롤백됩니다.
- **비활성 코드를 가리키는 규칙은 만들 수 없습니다.** 재활성화할 때도 다시 검사합니다 — 400 `INACTIVE_CODE`.
- **마지막 남은 활성 규칙은 비활성화할 수 없습니다** — 400.

### 9-3. `GET /api/admin/rules/overview` · `/history` — 기존 문서에 없는 계약

> ⚠️ 지라 `S15P21A307-366` 본문은 이 경로를 `GET /api/admin/rules`, `POST /api/admin/rules/repair-methods`, `PUT /api/admin/rules/estimate-anomaly` 라고 적고 있습니다. **실제 코드는 다릅니다.** 아래가 맞습니다.

#### 규칙 통합 조회

`GET /api/admin/rules/overview`

관련 Jira: `S15P21A307-366` · `S15P21A307-365`
상세 계약: **없음 — 이 절이 정본입니다.**

**인증** — 로그인 필수 · `ROLE_ADMIN`

**요청** — 파라미터 없음

**실제 성공 응답** (`RuleOverviewResponse`)

```json
{
  "data": {
    "repairMethodRules": [
      {
        "ruleId": 3,
        "damageType": "SCRATCH",
        "partCode": "FRONT_BUMPER",
        "severityMin": 0.00,
        "severityMax": 0.40,
        "maxInclusive": false,
        "repairMethod": "COATING",
        "priority": 10,
        "active": true,
        "version": 2,
        "createdAt": "2026-09-10T08:00:00Z",
        "updatedAt": "2026-09-11T01:20:11.004Z"
      }
    ],
    "estimateAnomalyRule": {
      "ruleVersion": 4,
      "referencePercentile": 75,
      "severeOverP75Multiplier": 1.50,
      "cautionTotalDifferenceRatio": 0.10,
      "needsReviewTotalDifferenceRatio": 0.20,
      "needsReviewItemCount": 3,
      "changedBy": 12,
      "changeNote": "주의 기준 완화",
      "createdAt": "2026-09-11T01:22:40.881Z"
    },
    "bounds": {
      "severityScale": 2,
      "severityMaxValue": 9999.99,
      "severityBoundaryNote": "구간은 [하한, 상한) 입니다. 상한은 포함하지 않으며, 마지막 구간만 maxInclusive=true 로 닫아 최대값이 어느 규칙에도 잡히지 않는 구멍을 막습니다.",
      "damageTypes": ["SCRATCH", "DENT", "BROKEN"],
      "repairMethods": ["COATING", "SHEET_METAL", "EXCHANGE", "REPAIR"],
      "priorityMax": 32767,
      "referencePercentile": 75,
      "severeOverP75MultiplierExclusiveMin": 1,
      "severeOverP75MultiplierMax": 99.99,
      "totalDifferenceRatioMax": 9.9999,
      "needsReviewItemCountMin": 1,
      "changeNoteMaxLength": 200
    }
  }
}
```

**`bounds` 를 FE 에 하드코딩하지 마세요.** 입력 폼의 유효 범위·드롭다운 목록(`damageTypes`·`repairMethods`, **활성 코드만**)이 전부 여기서 옵니다. 서버가 규칙을 바꿔도 FE 배포 없이 따라갑니다.

`repairMethodRules` 정렬은 `damageType` → `partCode` → `severityMin` 오름차순, `priority` 내림차순 고정입니다. 규칙이 하나도 없으면 **빈 배열이 정상 응답**입니다.

**JavaScript 요청 예제**

```js
// src/api/adminRules.js
import { api } from './http'

export async function getRuleOverview() {
  const response = await api.get('/api/admin/rules/overview')
  return response.data.data
}

export async function getRuleHistory(params = {}) {
  const response = await api.get('/api/admin/rules/history', { params })
  return response.data.data
}
```

**오류**

| HTTP | `error.code` | 발생 조건 | 프론트엔드 처리 |
|---|---|---|---|
| 401 | (본문 없음) | 미로그인 | 로그인 화면 |
| 403 | `FORBIDDEN` | `ROLE_USER` 로 호출 | 관리자 전용 안내 |
| 403 | `SIGNUP_REQUIRED` | 가입 미완료 세션 | 가입 완료 화면 |

**화면 구현 주의사항**

- 규칙 목록과 현재 임계값과 입력 경계를 **한 번에 받습니다.** 규칙 관리 화면 진입 시 이 호출 하나면 됩니다.
- `estimateAnomalyRule.ruleVersion` 을 화면에 들고 있다가 수정 결과와 비교하면 "내 변경이 반영됐는지" 를 보여 줄 수 있습니다.
- `severityScale`·`severityMaxValue` 는 저장 타입(`NUMERIC(6,2)`)의 한계일 뿐 **운영 범위가 아닙니다.** 심각도 점수가 0~1 인지 0~100 인지는 아직 확정되지 않았습니다(§15).

#### 규칙 변경 이력 조회

`GET /api/admin/rules/history`

관련 Jira: `S15P21A307-367` · `S15P21A307-378`
상세 계약: **없음 — 이 절이 정본입니다.**

**인증** — 로그인 필수 · `ROLE_ADMIN`

**요청**

| 이름 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `targetType` | query | 문자열 | ✕ | `REPAIR_METHOD_RULE` \| `ESTIMATE_VALIDATION_RULE`. **생략하면 두 종류 모두** |
| `actionType` | query | 문자열 | ✕ | 행위 유형 |
| `actorMemberId` | query | 정수 | ✕ | 변경자 |
| `targetId` | query | 문자열 | ✕ | 대상 식별자 |
| `page` | query | 정수 | ✕ | 0-based, 기본 0 |
| `size` | query | 정수 | ✕ | 기본 20, **상한 200(절단)** |

`GET /api/admin/audit-logs` 와 달리 **규칙 두 종류로 범위가 이미 좁혀져 있고, 정렬은 최신순 고정**입니다. 기간(`from`·`to`) 필터는 없습니다 — 기간이 필요하면 `/api/admin/audit-logs` 를 쓰세요.

**실제 성공 응답** (`AdminPageResponse<AuditLogResponse>`)

```json
{
  "data": {
    "content": [
      {
        "auditLogId": 91,
        "actorMemberId": 12,
        "actorNickname": "관리자",
        "actionType": "UPDATE",
        "targetType": "ESTIMATE_VALIDATION_RULE",
        "targetId": "4",
        "requestId": null,
        "beforeData": "{\"needsReviewItemCount\":3}",
        "afterData": "{\"needsReviewItemCount\":2}",
        "changeReason": "주의 기준 완화",
        "ipAddress": null,
        "createdAt": "2026-09-11T01:22:40.881Z"
      }
    ],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
  }
}
```

**화면 구현 주의사항**

- `beforeData`·`afterData` 는 **객체가 아니라 JSON 문자열**입니다. 화면에 쓰려면 `JSON.parse()` 가 필요합니다.
- `ipAddress` 는 **항상 `null`** 입니다. 프록시 헤더 신뢰 범위가 정해지지 않아 기록하지 않습니다. **IP 열을 만들지 마세요.**
- `requestId` 도 현재 채워지지 않습니다.
- `actorNickname` 은 회원 조회 결과이며 탈퇴 등으로 `null` 일 수 있습니다.

### 9-4. 즉시 반영 — 어디까지 되는가

| 규칙 | 즉시 반영 | 근거 |
| --- | --- | --- |
| **이상 탐지 임계값** | ○ — 저장하면 **다음 검증부터** 적용 | `EstimateValidationService` 가 검증마다 `currentRule()` 을 읽습니다 |
| **부품명 매핑** | ○ — 고치면 **다음 검증부터** 적용 | 검증마다 사전을 새로 읽습니다 |
| **수리 방식 규칙** | ✕ — **부르는 운영 코드가 없습니다** | §15 |

**이미 끝난 검증 결과는 바뀌지 않습니다.** `estimate_validation.rule_version` 이 판정 당시 규칙을 붙들고 있습니다. 관리자 화면에 "과거 결과도 바뀝니다" 같은 안내를 넣지 마세요.

### 9-5. 별건 — 표시명이 사용자 화면에 나가지 않습니다

`repair_code.displayName`·`displayOrder` 를 관리 API 로 바꿀 수 있지만, **사용자 화면은 `RepairMethodDisplay` 의 하드코딩을 씁니다.** 관리자가 표시명을 바꿔도 사용자에게는 그대로 보입니다. 관리자 화면에서 "사용자 화면에 즉시 반영됩니다" 라고 안내하면 안 됩니다.

---

## 10. 위치·정비소·외부 API 연동

상세 계약: `Docs/Api/위치 API (주소·좌표·장소 검색) — FE 인수인계.md` · `Docs/Api/정비소 검색 API — FE 인수인계.md`

| 메서드·경로 | 한 줄 설명 |
| --- | --- |
| `GET /api/locations/geocode` | 주소 → 좌표 |
| `GET /api/locations/reverse-geocode` | 좌표 → 주소. **`data` 가 배열** |
| `GET /api/locations/places` | 키워드 장소 검색 |
| `GET /api/locations/places/category` | 카테고리 장소 검색 |
| `GET /api/locations/category-groups` | 선택 가능한 카테고리 18종. **`data` 가 배열** |
| `GET /api/repair-shops` | **주변 정비소 목록 (거리순)** |

### 10-1. 🔴 키가 두 개입니다 — 섞으면 안 됩니다

| 구분 | 제품 | 키 | 어디서 쓰나 | 담당 |
| --- | --- | --- | --- | --- |
| 주소·좌표·장소 데이터 | 카카오 **Local REST API** | **REST API 키** | **서버 전용. 절대 브라우저에 노출 금지** | BE |
| 지도 렌더링·마커·로드뷰 | 카카오 **Maps JavaScript SDK** | **JavaScript 키** + 도메인 등록 | 브라우저 | **FE** |

**서버는 JavaScript 키를 보관하지도, 응답으로 내보내지도 않습니다.** FE 가 따로 발급받아 도메인을 등록해야 합니다(`http://localhost:8080` 포함).
**백엔드는 지도 이미지·로드뷰·길찾기 경로를 주지 않습니다.** 카카오에 서버용 정적 지도 REST API 가 없습니다.

### 10-2. 🔴 좌표 축 순서

```
우리 API      latitude(위도) → longitude(경도)    ← DTO 필드 이름이 이것뿐입니다
카카오 JS SDK  new kakao.maps.LatLng(위도, 경도)   ← 같은 순서
카카오 REST    x = 경도, y = 위도                  ← 반대. 서버가 한 곳에서만 변환합니다
```

우리 응답에 `x`·`y` 라는 필드는 **없습니다.** `latitude`·`longitude` 만 씁니다.
**대한민국 범위(위도 33~39, 경도 124~132)를 벗어난 좌표는 카카오를 부르기 전에 400** 으로 거절합니다 — 축을 바꿔 넣으면 한국 좌표가 인도양으로 가는데 HTTP 오류가 나지 않기 때문입니다.

### 10-3. `GET /api/repair-shops` — 정비소 전용

| 이름 | 필수 | 규칙 |
| --- | --- | --- |
| `latitude` · `longitude` | **○** | 없으면 400 |
| `radius` | ✕ | 0~20000m. 초과는 절단, 음수는 400. 생략하면 반경 없이 거리순 |
| `page` | ✕ | 0-based. 45 초과는 절단 |
| `size` | ✕ | 1~15. 초과는 절단 |

응답 항목: `placeId` · `placeName` · `categoryName` · `phone` · `addressName` · `roadAddressName` · `latitude` · `longitude` · `placeUrl` · `distanceMeters`

- **검색어를 서버가 고정합니다** (`자동차정비`). `query` 를 보내도 무시합니다. 화면마다 다른 검색어를 쓰면 결과가 달라지기 때문입니다.
- **항상 거리 오름차순**입니다. 정렬을 바꿀 수 없습니다. 거리를 모르는 행은 맨 뒤로 갑니다.
- `distanceMeters` 는 카카오 값을 우선하고, 비어 있으면 서버가 하버사인 직선거리로 계산합니다. **직선거리이지 도로 거리가 아닙니다.**
- **"지정 지역 기준" 검색은 이 API 가 직접 받지 않습니다.** 지역명으로 찾으려면 FE 가 2단계로 하세요 — §12.

### 10-4. 🔴 디바운스와 429

**지도를 움직일 때마다 호출하면 일일 쿼터를 태웁니다.**

```js
// src/api/repairShops.js
import { api } from './http'

export async function searchRepairShops(params, signal) {
  const response = await api.get('/api/repair-shops', { params, signal })
  return response.data.data
}
```

```vue
<script setup>
import { ref, onUnmounted } from 'vue'
import { searchRepairShops } from '@/api/repairShops'

const shops = ref([])
const quotaExceeded = ref(false)

let debounceId = null
let controller = null
let lastCenter = null

function movedEnough(center) {
  if (lastCenter === null) return true
  // 대략 위도 0.01° ≈ 1,112m. 150m 미만 이동은 무시합니다
  const dLat = Math.abs(center.latitude - lastCenter.latitude)
  const dLng = Math.abs(center.longitude - lastCenter.longitude)
  return (dLat + dLng) * 111000 >= 150
}

function onMapMoved(center) {
  if (quotaExceeded.value) return       // 429 뒤에는 다시 부르지 않습니다
  if (!movedEnough(center)) return

  if (debounceId !== null) clearTimeout(debounceId)
  debounceId = setTimeout(async () => {
    if (controller !== null) controller.abort()   // 이전 요청 취소
    controller = new AbortController()
    lastCenter = center
    try {
      const paged = await searchRepairShops(
        { latitude: center.latitude, longitude: center.longitude, radius: 3000, size: 15 },
        controller.signal
      )
      shops.value = paged.content
    } catch (error) {
      if (error.code === 'ERR_CANCELED') return
      if (error.response?.status === 429) quotaExceeded.value = true
    }
  }, 400)   // 300~500ms
}

onUnmounted(() => {
  if (debounceId !== null) clearTimeout(debounceId)
  if (controller !== null) controller.abort()
})
</script>
```

- **디바운스 300~500ms** · 150m 미만 이동은 무시.
- **429 에 자동 재시도를 넣지 마세요.** 이미 바닥난 일일 회수를 더 태웁니다. 검색을 멈추고 사용자에게 안내하세요.
- 서버에 캐시가 없습니다. 같은 좌표를 반복 조회하면 그대로 카카오를 부릅니다.

### 10-5. 카카오 장애를 상태 코드로 구분합니다

서버가 카카오 응답 본문의 `code` 를 읽어 이미 분류해 줍니다. **FE 는 HTTP 상태로만 판단하면 됩니다.**

| HTTP | `error.code` | 뜻 | FE 처리 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | 좌표·파라미터 오류 | 입력 안내 |
| **429** | `TOO_MANY_REQUESTS` | **일일 쿼터 초과** | **자동 재시도 금지.** 검색 중지 + 안내 |
| **503** | `SERVICE_UNAVAILABLE` | 카카오 장애·점검·키 미설정 | "일시적으로 사용할 수 없음". 잠시 뒤 재시도는 가능 |

**`KAKAO_REST_API_KEY` 가 없으면 위치·정비소 API 만 503 이고 나머지 API 는 정상**입니다. 지도 기능이 안 된다고 다른 화면까지 막지 마세요.

---

## 11. Vue JavaScript API 모듈

**모든 예제는 순수 JavaScript 입니다.** TypeScript 를 쓰지 않습니다.
관리자 화면 코드는 `Docs/Api/관리자 마스터·규칙 관리 — Vue 연동 예제.md` 가 정본이고, 아래는 이 문서가 다루는 전 영역의 공통 골격입니다.

### 11-1. `src/api/http.js`

```js
import axios from 'axios'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  withCredentials: true
})
```

### 11-2. `src/api/errors.js` — 한글 메시지를 비교하지 않습니다

```js
// 오류를 화면이 쓸 수 있는 모양으로 정규화합니다.
// error.code 로 분기하세요. message 는 문구가 바뀌는 값입니다.
export function normalizeError(error) {
  if (error.code === 'ERR_CANCELED') {
    return { kind: 'canceled', status: null, code: null, message: null }
  }
  const response = error.response
  if (!response) {
    return { kind: 'network', status: null, code: null, message: '네트워크에 연결할 수 없습니다.' }
  }

  const status = response.status
  // 401 은 본문이 없습니다. 파싱하지 않습니다.
  if (status === 401) {
    return { kind: 'unauthenticated', status, code: null, message: '다시 로그인해 주세요.' }
  }

  const code = response.data?.error?.code ?? null
  const message = response.data?.error?.message ?? null

  if (status === 403 && code === 'SIGNUP_REQUIRED') {
    return { kind: 'signupRequired', status, code, message }
  }
  if (status === 403) return { kind: 'forbidden', status, code, message }
  if (status === 404) return { kind: 'notFound', status, code, message }
  if (status === 409) return { kind: 'conflict', status, code, message }
  if (status === 429) return { kind: 'rateLimited', status, code, message }
  if (status === 503) return { kind: 'unavailable', status, code, message }
  if (status === 400) return { kind: 'invalid', status, code, message }

  return { kind: 'unknown', status, code, message: message ?? '알 수 없는 오류가 발생했습니다.' }
}
```

### 11-3. `src/api/vehicles.js`

```js
import { api } from './http'

export async function getVehicleModels() {
  const response = await api.get('/api/vehicle-models')
  return response.data.data.vehicleModels
}

export async function getMyVehicles() {
  const response = await api.get('/api/vehicles/me')
  return response.data.data.vehicles
}

export async function createVehicle(modelId, modelYear) {
  const response = await api.post('/api/vehicles', { modelId, modelYear })
  return response.data.data
}

// 연식만 바꿀 수 있습니다. modelId 를 실으면 400 입니다.
export async function updateVehicleYear(vehicleId, modelYear) {
  const response = await api.patch(`/api/vehicles/${vehicleId}`, { modelYear })
  return response.data.data
}

export async function deleteVehicle(vehicleId) {
  await api.delete(`/api/vehicles/${vehicleId}`)
}
```

### 11-4. `src/api/accidents.js`

```js
import { api } from './http'

export async function createAccidentWithRegisteredVehicle(vehicleId) {
  const response = await api.post('/api/accidents', { vehicleId })
  return response.data.data
}

export async function createAccidentWithDirectVehicle(directVehicle) {
  const response = await api.post('/api/accidents', { directVehicle })
  return response.data.data
}

// 배열 키가 accidents 입니다. content 가 아닙니다.
export async function getMyAccidents(params = {}) {
  const response = await api.get('/api/accidents/me', { params })
  return response.data.data
}

export async function getAccident(accidentId) {
  const response = await api.get(`/api/accidents/${accidentId}`)
  return response.data.data
}

export async function recordActualCost(accidentId, payload) {
  const response = await api.put(`/api/accidents/${accidentId}/actual-cost`, payload)
  return response.data.data
}

export async function getCostComparison(accidentId) {
  const response = await api.get(`/api/accidents/${accidentId}/cost-comparison`)
  return response.data.data
}
```

### 11-5. `src/api/accidentImages.js`

```js
import { api } from './http'

export async function issueUploadUrls(accidentId, files) {
  // files: [{ originalFilename, contentType, size, angleCode }]
  // size 는 실제로 올릴 바이트 수와 정확히 같아야 합니다.
  const response = await api.post(`/api/accidents/${accidentId}/images/upload-urls`, { files })
  return response.data.data
}

export async function completeUpload(accidentId, images) {
  // images: [{ imageId, size }]
  const response = await api.post(`/api/accidents/${accidentId}/images`, { images })
  return response.data.data
}

export async function getAccidentImages(accidentId) {
  const response = await api.get(`/api/accidents/${accidentId}/images`)
  return response.data.data
}

export async function deleteAccidentImage(accidentId, imageId) {
  await api.delete(`/api/accidents/${accidentId}/images/${imageId}`)
}
```

### 11-6. `src/api/estimateValidations.js`

```js
import { api } from './http'

export async function registerManualValidation(payload) {
  // payload: { accidentId, estimateId?, fileType: 'MANUAL', claimedTotal?, items: [...] }
  const response = await api.post('/api/estimate-validations', payload)
  return response.data.data          // 202 { validationId, status, inputType, statusUrl }
}

export async function registerFileValidation(metadata, file) {
  const form = new FormData()
  // metadata 파트는 JSON 이어야 합니다. Blob 으로 타입을 명시합니다.
  form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }))
  form.append('file', file)
  const response = await api.post('/api/estimate-validations', form)
  return response.data.data
}

export async function getValidationStatus(validationId) {
  const response = await api.get(`/api/estimate-validations/${validationId}`)
  return response.data.data
}

export async function getValidationResult(validationId) {
  const response = await api.get(`/api/estimate-validations/${validationId}/result`)
  return response.data.data
}

// 이 API 만 data 가 바로 배열입니다.
export async function getValidationQuestions(validationId) {
  const response = await api.get(`/api/estimate-validations/${validationId}/questions`)
  return response.data.data
}

// hasNext 가 없습니다. page + 1 < totalPages 로 직접 계산하세요.
export async function getValidationHistory(page = 0, size = 20) {
  const response = await api.get('/api/estimate-validations/me', { params: { page, size } })
  return response.data.data
}

export function validationPdfUrl(validationId) {
  // 302 를 브라우저가 따라가게 둡니다.
  return `${import.meta.env.VITE_API_BASE_URL}/api/estimate-validations/${validationId}/pdf`
}

export async function deleteValidation(validationId) {
  await api.delete(`/api/estimate-validations/${validationId}`)
}
```

### 11-7. `src/api/guides.js` (비인증)

```js
import { api } from './http'

export async function getChecklist() {
  const response = await api.get('/api/guides/checklist')
  return response.data.data.steps
}

export async function getShootingGuide() {
  const response = await api.get('/api/guides/shooting')
  return response.data.data
}
```

---

## 12. 화면별 연동 순서

지라와 코드에서 **실제로 확인된 화면만** 적었습니다.

| 화면 | 최초 호출 | 사용자 동작 시 호출 | 성공 후 처리 | 주요 오류 |
|---|---|---|---|---|
| 사고 현장 체크리스트 | `GET /api/guides/checklist` | 없음 (체크는 로컬) | `localStorage` 에 저장 | 없음 (비인증·정적) |
| 촬영 가이드 | `GET /api/guides/shooting` | 없음 | `overlaySet` 코드로 **FE 번들의 실루엣**을 고름 | 없음. **이미지가 없으면 화면을 만들 수 없습니다** |
| 차량 등록 | `GET /api/vehicle-models` | `POST /api/vehicles` | 차량 목록 갱신 | 400 (연식 범위) · 401 |
| 내 차량 목록 | `GET /api/vehicles/me` | `PATCH`(연식) · `DELETE` | 목록 재조회 | 404 (남의·없는 차량) |
| 사고 접수 | `GET /api/vehicles/me` + `GET /api/vehicle-models` | `POST /api/accidents` | `accidentId` 로 업로드 화면 이동 | 400 (두 분기 위반·마스터 불일치) · 404 |
| 사진 업로드 | `GET /api/accidents/{id}/images` | ① `POST .../upload-urls` → ② `PUT` S3 → ③ `POST .../images` | `remainingSlots` 갱신, 실패 건만 재시도 | 400 `SERVER_CONVERSION_UNSUPPORTED`·`TOO_MANY_IMAGES` · **S3 403** |
| 업로드 목록·미리보기 | `GET /api/accidents/{id}/images` | `DELETE .../{imageId}` | 재조회 (URL 재서명) | 404 · `expiresAt` 만료 |
| 사고 이력 목록 | `GET /api/accidents/me?page=0&size=20` | 페이지 이동 | `thumbnailExpiresAt` 지나면 재조회 | 401 · 400 (`page=abc`) |
| 사고 상세 | `GET /api/accidents/{id}` | — | — | 404 |
| 견적서 검증 등록 | — | `POST /api/estimate-validations` | **202 → 폴링 화면**으로 전환 | 400 · 404 (남의 사고) · **503** (저장소 미설정) |
| 검증 진행 중 | `GET /api/estimate-validations/{id}` (폴링) | 취소 = 폴링 중지 | `COMPLETED` → 결과 화면 · `FAILED` → `failureReason` | 401 → 폴링 중지 |
| 검증 결과 | `GET /api/estimate-validations/{id}/result` | PDF 버튼 | `questions` 를 같은 응답에서 렌더 | **409** (미완료) |
| 정비소 확인 질문 | `/result` 의 `questions` | 복사 | 토스트 | 200 + 빈 배열 (미완료) |
| 검증 PDF | `GET .../{id}/pdf` | — | 302 → 다운로드 | **409** (생성 중·실패) · **503** (기능 꺼짐) |
| 검증 이력 | `GET /api/estimate-validations/me` | 페이지 이동 | — | **400** (`page<0`·`size<1`) |
| 실제 수리비 입력 | `GET /api/accidents/{id}` | `PUT .../actual-cost` | `GET .../cost-comparison` 재조회 | 400 (미래일·0 이하) · 404 |
| 견적 비교 | `GET /api/accidents/{id}/cost-comparison` | — | `aiEstimate` 가 비면 "비교 없음" | 404 |
| 주변 정비소 지도 | `GET /api/repair-shops?latitude=&longitude=` | 지도 이동(디바운스) | 마커 갱신 | **429** (중지) · **503** |
| 지역명으로 정비소 | `GET /api/locations/geocode?query=지역명` | 결과 좌표로 `GET /api/repair-shops` | 지도 이동 + 목록 | 400 · 429 · 503 |
| 관리자 규칙 관리 | `GET /api/admin/rules/overview` | `POST`·`PATCH` 각 규칙 | `overview` 재조회 | **409 `VERSION_CONFLICT`** · 400 `INACTIVE_CODE` · 403 |
| 관리자 변경 이력 | `GET /api/admin/rules/history` | 필터·페이지 | — | 403 |
| 관리자 마스터 관리 | `GET /api/admin/vehicle-models` · `/part-codes` · `/part-name-mappings` | `POST`·`PATCH`·(매핑만) `DELETE` | 목록 재조회 | 409 (중복·버전) · 400 |

### 12-1. "지정 지역 기준" 정비소 검색은 2단계입니다

```js
import { api } from './http'

// 1단계 — 지역명을 좌표로 바꿉니다
export async function geocode(query) {
  const response = await api.get('/api/locations/geocode', { params: { query } })
  return response.data.data.content   // [{ addressName, latitude, longitude, ... }]
}

// 2단계 — 그 좌표로 정비소를 찾습니다
export async function searchRepairShopsByRegion(query) {
  const addresses = await geocode(query)
  if (addresses.length === 0) return { content: [], page: 0, size: 0, totalElements: 0 }
  const { latitude, longitude } = addresses[0]
  const response = await api.get('/api/repair-shops', {
    params: { latitude, longitude, radius: 5000, size: 15 }
  })
  return response.data.data
}
```

`GET /api/repair-shops` 는 **지역명을 받지 않습니다.** 좌표만 받습니다.

### 12-2. API 가 없는 화면

| 화면 | 상태 |
| --- | --- |
| 8방향 실루엣 오버레이 이미지 | **현재 백엔드 API 없음** — FE 번들에 두어야 하고, 디자인 16장이 아직 없습니다 |
| 업로드 퍼센트 진행률 | **현재 백엔드 API 없음** — FE 가 `XHR` 로 계산 |
| 분석 진행률("3/4 부품을 연결하고 있어요") | **현재 백엔드 API 없음** — `analysis_job` 엔티티가 없습니다 |
| 업로드 직후 품질 경고(WARN) | 엔드포인트는 있으나 **판정이 꺼져 있어 항상 `PASS`** |
| 사고 이력의 예상 비용 | 필드는 있으나 **항상 `null`** |
| 관리자 표준 작업 코드·사고 유형 마스터 | **현재 백엔드 API 없음** |
| 관리자 검수 큐·승인/반려 | **현재 백엔드 API 없음** |

---

## 13. 오류 처리

### 13-1. 공통 코드 (`ErrorCode`)

| HTTP | `error.code` | 뜻 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 입력값 오류 |
| 401 | (본문 없음) | 미인증 |
| 403 | `FORBIDDEN` | 권한 부족 |
| 403 | `SIGNUP_REQUIRED` | 가입 미완료 세션 |
| 404 | `NOT_FOUND` | 없거나 **남의 것** |
| 409 | `CONFLICT` | 상태 충돌 (미완료 결과 조회, 데이터 무결성 위반 등) |
| 429 | `TOO_MANY_REQUESTS` | 외부 API 쿼터 초과 |
| 503 | `SERVICE_UNAVAILABLE` | 저장소·외부 서비스 미설정 또는 장애 |
| 500 | `INTERNAL_ERROR` | 그 밖 |

### 13-2. `error.code` 가 공통 목록 밖인 곳 — **문자열로 받으세요**

`error.code` 는 **enum 이 아니라 문자열**입니다. 아래 두 영역은 공통 목록에 없는 값을 냅니다.

**① 사고 이미지 발급 실패 — 전부 HTTP 400**

```
MISSING_FILE · FILE_TOO_LARGE · INVALID_FILE_NAME · UNSUPPORTED_EXTENSION
UNSUPPORTED_CONTENT_TYPE · SIGNATURE_MISMATCH · SERVER_CONVERSION_UNSUPPORTED
TOO_MANY_IMAGES · SIZE_MISMATCH · UNKNOWN_ANGLE_CODE · DUPLICATE_IMAGE
```

`SERVER_CONVERSION_UNSUPPORTED` 가 **HEIC 거절**입니다. 같은 값 체계가 완료 통보 응답의 `results[].failureCode` 에도 쓰입니다.

**② 관리자 작업 실패 (`AdminErrorCode`)**

| HTTP | `error.code` | FE 가 해야 할 일 |
| --- | --- | --- |
| 404 | `ADMIN_TARGET_NOT_FOUND` | 목록 재조회 |
| 409 | `DUPLICATE_VEHICLE_MODEL` · `DUPLICATE_PART_CODE` · `DUPLICATE_PART_NAME_MAPPING` | **입력을 고치게** 안내 |
| 409 | `VERSION_CONFLICT` | **다시 읽고 재시도** |
| 409 | `CODE_IN_USE` · `REFERENCED_BY_ACTIVE_RULE` · `LAST_ACTIVE_RULE` | **먼저 다른 것을 바꾸라고** 안내 |
| 409 | `NORMALIZED_NAME_CONFLICT` | 같은 뜻의 매핑이 이미 있음 |
| 409 | `OVERLAPPING_RULE_RANGE` | 심각도 구간이 겹침 |
| 400 | `INACTIVE_CODE` · `UNKNOWN_CODE` · `INVALID_SEVERITY_RANGE` · `INVALID_RULE_VALUE` | 입력 안내 |

**셋 다 409 지만 FE 가 해야 할 일이 다릅니다.** 상태 코드 하나로 뭉개면 한글 메시지를 비교하게 됩니다.

### 13-3. 분기는 `error.code` 로

**`error.message` 를 문자열 비교하지 마세요.** 문구는 바뀌는 값이고, 바뀌는 순간 조용히 깨집니다.

```js
import { normalizeError } from '@/api/errors'

try {
  await issueUploadUrls(accidentId, files)
} catch (rawError) {
  const e = normalizeError(rawError)
  if (e.code === 'SERVER_CONVERSION_UNSUPPORTED') {
    showToast('HEIC 사진은 JPG 로 변환한 뒤 올려 주세요.')
  } else if (e.code === 'TOO_MANY_IMAGES') {
    showToast('사고 한 건에 최대 20장까지 올릴 수 있어요.')
  } else if (e.kind === 'unauthenticated') {
    goLogin()
  } else if (e.kind === 'unavailable') {
    showToast('이미지 저장소를 사용할 수 없어요. 잠시 뒤 다시 시도해 주세요.')
  } else {
    showToast(e.message ?? '알 수 없는 오류가 발생했습니다.')
  }
}
```

### 13-4. 503 이 나오는 조건 — 버그가 아니라 설정입니다

| 기능 | 503 조건 |
| --- | --- |
| 사고 이미지 업로드·조회 | `STORAGE_STAGING_BUCKET` · `STORAGE_SERVICE_BUCKET` 미설정 |
| 견적서 파일 업로드 | `DOCUMENT_STORAGE_PROVIDER` · `STORAGE_SERVICE_BUCKET` 미설정 |
| 검증 PDF | `VALIDATION_PDF_ENABLED=false` (기본값) 또는 저장소 미설정 |
| 위치·정비소 | `KAKAO_REST_API_KEY` 미설정 |
| LLM 요약·OCR | `GMS_KEY` 미설정 또는 크레딧 부족 |

**전부 기본값이 꺼짐입니다.** 로컬 개발에서 503 이 나오면 먼저 환경변수를 의심하세요. 이 상태에서도 **나머지 API 는 정상 동작합니다.**

### 13-5. CORS

허용 오리진이 `CORS_ALLOWED_ORIGINS` 에서 옵니다(기본값 `http://localhost:5173`).
`allowCredentials=true` 라 **`*` 를 쓸 수 없습니다.** 배포 시 FE 오리진을 반드시 넣어야 하고, 빠지면 **전 화면의 API 호출이 브라우저에서 막힙니다.** 이 증상은 **브라우저 콘솔에만 나오고 서버 로그에는 아무것도 남지 않습니다.**

---

## 14. 환경변수

**실제 키·비밀번호·토큰 값은 이 문서에 없습니다. 이름만 적습니다.**

### 14-1. 프론트엔드 환경변수 (브라우저 노출 가능)

| 이름 | 용도 |
| --- | --- |
| `VITE_API_BASE_URL` | 백엔드 주소 |
| `VITE_KAKAO_MAP_JS_KEY` | **카카오 Maps JavaScript 키.** FE 가 따로 발급받고 도메인을 등록합니다. 노출을 전제로 한 키입니다 |

> `VITE_KAKAO_MAP_JS_KEY` 는 아직 저장소의 백엔드 설정에 없는 값입니다 — **FE 가 정하는 이름**입니다. 서버는 이 키를 보관하지도 내려주지도 않습니다.

### 14-2. 백엔드 환경변수 — **브라우저 노출 금지**

| 이름 | 용도 |
| --- | --- |
| `KAKAO_REST_API_KEY` | 카카오 Local **REST** 키. **서버 전용** |
| `KAKAO_CLIENT_ID` · `KAKAO_CLIENT_SECRET` | 카카오 소셜 로그인 |
| `GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` | 구글 소셜 로그인 |
| `GMS_KEY` | GMS 경유 LLM. **견적서 원본을 외부로 보내는 경로라 개인정보 승인 전까지 채우면 안 됩니다** |
| `DB_USERNAME` · `DB_PASSWORD` | PostgreSQL |

### 14-3. 백엔드 환경변수 — 기능 스위치 (비밀 아님)

| 이름 | 기본값 | 영향 |
| --- | --- | --- |
| `CORS_ALLOWED_ORIGINS` | `app.frontend-base-url` | **배포 시 필수.** 빠지면 전 화면 CORS 차단 |
| `FRONTEND_BASE_URL` | `http://localhost:5173` | 로그인 후 리다이렉트 |
| `STORAGE_STAGING_BUCKET` · `STORAGE_SERVICE_BUCKET` | 빈 값 | 비면 이미지·견적서 업로드가 503 |
| `DOCUMENT_STORAGE_PROVIDER` | 빈 값 | `s3` 여야 견적서가 S3 로 갑니다 |
| `VALIDATION_PDF_ENABLED` | `false` | `true` 여야 PDF 가 생성됩니다 |
| `ESTIMATE_WORKER_ENABLED` | `false` | `true` 여야 견적서 판독 워커가 돕니다 |
| `ESTIMATE_OCR_PROVIDER` | 빈 값 | 견적서 판독 |
| `ESTIMATE_SUMMARY_ENABLED` | `false` | LLM 요약 문장 |
| `AWS_REGION` | `ap-northeast-2` | S3 리전 |

### 14-4. 코드에 고정되어 환경변수가 아닌 값 (참고)

```
app.accident-image.max-count-per-accident = 20
app.accident-image.max-file-size-bytes    = 20971520   (20MB)
app.accident-image.presigned-url-minutes  = 10
app.accident-image.download-url-minutes   = 10
app.image-quality.enabled                 = false
spring.servlet.multipart.max-file-size    = 10MB       (견적서 업로드 상한)
server.servlet.session.timeout            = 30m
```

⚠️ **사고 이미지는 20MB 까지지만 견적서 파일은 10MB 까지입니다.** 스프링 multipart 상한이 10MB 라 그보다 큰 견적서는 400 "견적서 파일은 10MB 이하여야 합니다." 로 막힙니다.

---

## 15. 부분 구현·미구현·제한사항

| 기능 | 판정 | FE 가 쓸 수 있는 범위 | 쓰면 안 되는 범위 | 후속 작업 |
|---|---|---|---|---|
| 사고 이력 **예상 비용** (`-225`) | 부분 구현 | 목록·상세·썸네일·상태·장수 전부 | `estimatedCostMin/Median/Max` 를 값으로 취급 — **항상 `null`** | AI 견적 생성 경로(`-50`). `EstimateVersioningService` 를 부르는 코드가 0건입니다 |
| 견적서 검증의 **AI 견적 비교** (`-316`·`-321`) | 부분 구현 | 정비소 견적 총액·항목 판정·등급·질문·실제 수리비 | `aiTotalMin/Median/Max` · `differenceFromMedian` · `cost-comparison.aiEstimate` — **전부 `null`** | 같은 원인 |
| 분석 기반 판정 `NOT_IN_ANALYSIS` (`-312`) | 부분 구현 | 나머지 4개 플래그 | 이 플래그가 나올 것으로 가정한 화면 | `analysis_job`·`damaged_part` **엔티티 자체가 없습니다** |
| **촬영 오버레이·예시 이미지** (`-123`) | 부분 구현 | `overlaySet` 코드·각도·순서·문안 | 서버에서 이미지 URL 을 기대 — **없습니다** | 실루엣 16장 디자인 |
| **업로드 진행률·4상태** (`-141`) | 부분 구현 | `total`·`completed`·`pending`·`remainingSlots` · 부분 실패 재시도 | 퍼센트 진행률 · `진행`/`실패` 상태 — **BE 가 구조적으로 모릅니다** | 스키마에 `upload_status` 추가 |
| **촬영 품질 경고** (`-125`) | 부분 구현 | `qualityStatus` 필드 읽기 | WARN 배지 — **`enabled=false` 라 항상 `PASS`** | 임계값 실험 후 활성화 |
| **수리 방식 규칙** (`-365`) | 부분 구현 | 관리 API(등록·수정·활성 전환·이력) 전부 | "저장하면 사용자 화면에 반영됩니다" 안내 — **부르는 운영 코드가 0건입니다** | 분석 파이프라인이 `decide()` 호출 |
| **관리자 마스터 범위** (`-362`) | 부분 구현 | 차량 모델·부품 코드·부품명 매핑·수리 방식/손상 유형 코드 | **표준 작업 코드 · 사고 유형** 관리 화면 — 미구현 | 스키마 승인(작업 코드) · 기획 정의(사고 유형) |
| **"지정 지역 기준" 정비소** (`-443`) | 부분 구현 | 좌표 기준 검색 | `GET /api/repair-shops` 에 지역명 전달 — **받지 않습니다** | FE 가 `geocode` 로 2단계 (§12-1) |
| **번호판·얼굴 블러** (`-226`·`-227`·`-228`·`-386`) | 미구현 (범위 밖) | `ORIGINAL` 이 응답에 안 나온다는 보장 | `BLURRED` variant 를 기대 — **생성 코드 0건** | LLM 기반 블러 신규 이슈 |
| **통계 배치** (`-248`~`-250`) | 미구현 | 현재 `repair_cost_stat` (AI-Hub 적재분) | 실제 수리비 반영 통계 | 배분 방향 확정 |
| **검수 큐·승인/반려** (`-349`·`-350`·`-352`) | 미구현 | 없음 | 관리자 검수 화면 | — |
| **차량 검출·적합성 검사** (`-186`·`-187`) | 미구현 | 없음 | "분석에서 제외됐습니다" 안내 | — |

### 15-1. 지라와 코드가 어긋난 항목 — 코드가 맞습니다

| Jira | 지라 본문 | 실제 코드 | FE 영향 |
| --- | --- | --- | --- |
| **`-366`** | `GET /api/admin/rules` · `POST /api/admin/rules/repair-methods` · `PUT /api/admin/rules/estimate-anomaly` | `GET /api/admin/rules/overview` · `POST /api/admin/repair-method-rules` · `PATCH /api/admin/estimate-validation-rules/current` | **큼** — 지라 경로로 붙이면 404 |
| **`-364`** | `PATCH`·`DELETE /api/admin/part-name-mappings/{id}` | `PATCH`·`DELETE /api/admin/part-name-mappings?rawName=` | **큼** — 경로 변수가 아닙니다 |
| **`-122`** | `SecurityConfig` 의 `permitAll` 에 `/api/guides/**` | `/api/guides/checklist` · `/api/guides/shooting` **두 경로만** | 가이드 API 를 늘리면 기본값은 인증 필요 |
| **`-126`** | "이 값을 읽어 쓰는 판정 코드가 없다" | `ImageQualityAssessor` 가 있고 `AccidentImageIngestService` 가 씁니다 | 없음 (여전히 `enabled=false`) |
| **`-152`** | "유사 사례 검색 API 3종이 미구현" | `SimilarCaseController` · `RepairCaseController` 가 develop 에 있습니다 (서보영 담당) | 없음 |
| **`-277`** (에픽) | 상태 `해야 할 일` | 하위 `-362`·`-365` 완료·머지 | 없음 (상태만 미갱신) |
| **`-377`** (스토리) | 상태 `해야 할 일` | 하위 `-378` 완료. `GET /api/admin/audit-logs` 동작 | 없음 (상태만 미갱신) |
| **`-443`** (스토리) | 상태 `해야 할 일` | 하위 `-444`·`-445` 완료·머지 | 없음 (상태만 미갱신) |
| **`-224`·`-225`** | 상태 `진행 중` | 코드는 머지됨. 예상 비용 값만 대기 | 없음 |

### 15-2. 실제 외부 서비스로 검증되지 않은 것

- **실제 S3 에 PUT·GET 을 해 본 적이 없습니다.** presigned 서명이 실제 S3 에서 통과하는지, `Content-Length` 강제가 현장에서 기대대로 도는지는 미검증입니다. 첫 연동 때 **403 `SignatureDoesNotMatch` 가 나오면 §6-2 ①** 을 먼저 보세요.
- **실제 GMS(LLM) 를 호출한 적이 없습니다.**
- **`/api/repair-shops` 를 서버를 띄워 끝에서 끝까지 불러본 적이 없습니다.** 카카오 로컬 API 자체는 2026-09-11 에 실호출로 키와 검색어를 확인했습니다.
- 대량 데이터(수천 건) 성능 실측치가 없습니다.

### 15-3. 그 밖에 FE 가 알아야 할 동작

- **즉시 입력(`DIRECT`) 사고 접수가 영구 차량을 만듭니다.** 접수만 하려던 차가 `GET /api/vehicles/me` 에 나타나고, 같은 차로 두 번 접수하면 차량이 두 대 생깁니다. 기획 의도와 맞는지 확인이 필요합니다.
- **실제 수리비 입력에 검증 이력 선행 조건이 없습니다.** 소유한 사고면 검증 없이도 저장됩니다. 지라 스토리 문장("검증 결과가 저장된 사고 건에")과 다릅니다.
- **`repairCompletedDate` 가 사고 접수일보다 이전이어도 통과합니다.** 도메인 정합성 검증이 없습니다. 또 `@PastOrPresent` 가 서버 타임존 기준이라 **KST 사용자가 오늘 날짜를 넣을 때 서버가 UTC 면 거부될 수 있습니다.**
- **부품명 매핑의 런타임 키 충돌이 조용히 일어납니다.** `앞범퍼` 가 이미 매핑돼 있을 때 `앞범퍼 교환` 을 다른 부품으로 등록하면 등록은 통과하지만 **기존 정상 매핑까지 사전에서 빠집니다.** 새 행에는 `inDictionary=false` 가 보이지만 **무력화된 기존 행에는 아무 표시가 없습니다.** 관리자 화면에 `inDictionary` 를 반드시 노출하세요.
- **심각도 점수의 운영 범위가 확정되지 않았습니다.** `0~1` 인지 `0~100` 인지 계약이 없어 서버가 전역 CHECK 를 넣지 않았습니다. 규칙 입력 폼에 절대 범위를 하드코딩하지 말고 `bounds` 를 쓰세요.
- **`RESIZED`·`THUMBNAIL` 에도 번호판과 얼굴이 그대로 보입니다.** EXIF 만 제거된 것입니다. 사용자 본인만 보는 화면에만 쓰세요.

---

## 16. 프론트엔드 체크리스트

착수 전에 확인하세요.

**공통**

- [ ] `axios` 인스턴스에 **`withCredentials: true`** 를 넣었다 (없으면 전부 401)
- [ ] 성공은 `response.data.data`, 실패는 `error.response.data.error.code` 로 읽는다
- [ ] **401 응답 본문을 파싱하지 않는다** (본문이 없다)
- [ ] 분기를 `error.code` 로 한다. **한글 `message` 를 문자열 비교하지 않는다**
- [ ] `error.code` 를 문자열로 받는다 (공통 8개 밖의 값이 온다 — 이미지 11종 · 관리자 15종)
- [ ] 404 를 "없음" 과 "남의 것" 모두로 처리한다 (403 이 오지 않는다)
- [ ] 배포 전 `CORS_ALLOWED_ORIGINS` 에 FE 오리진이 들어갔는지 확인했다

**페이지네이션**

- [ ] `page` 가 **0-based** 인 것을 반영했다
- [ ] 사고 이력만 배열 키가 **`accidents`** 인 것을 반영했다
- [ ] 검증 이력에 **`hasNext` 가 없는** 것을 반영했다 (`page + 1 < totalPages`)
- [ ] 검증 이력만 `page<0`·`size<1` 을 **400 으로 거절**하는 것을 반영했다
- [ ] 위치 계열의 `pageableCount`(최대 45)와 `totalElements` 를 구분했다

**이미지 업로드**

- [ ] `files[].size` 와 **실제 업로드 바이트가 정확히 같다**
- [ ] `requiredHeaders` 를 **키 이름 그대로 순회**해 실었다 (하드코딩 금지)
- [ ] `content-length` 를 직접 설정하지 않았다
- [ ] **HEIC 를 JPEG 로 변환**해 올린다
- [ ] 10분 만료 후 재시도를 **`DELETE` → 재발급 2단계**로 만들었다
- [ ] `PENDING` 행도 20장 슬롯을 먹는 것을 반영했다
- [ ] 진행률을 **`XMLHttpRequest.upload.onprogress`** 로 계산한다 (`fetch` 불가)
- [ ] 개별 실패를 `results[].status === 'FAILED'` 로 읽는다 (HTTP 400 아님)
- [ ] `angleCode` 를 **발급 요청**에 실었다 (완료 통보에는 안 실린다)
- [ ] `assets[].expiresAt` 만료 시 목록을 재조회한다

**폴링**

- [ ] `setInterval` 이 아니라 **`setTimeout` 재귀**를 쓴다
- [ ] **`onUnmounted` 에서 폴링을 해제**한다
- [ ] `COMPLETED`·`FAILED`·401·404 에서 폴링을 멈춘다

**지도·정비소**

- [ ] 카카오 **JavaScript 키**를 FE 가 발급받고 도메인을 등록했다
- [ ] **REST API 키를 브라우저에 넣지 않았다**
- [ ] 좌표를 `latitude` → `longitude` 순서로 보낸다
- [ ] 지도 이동에 **300~500ms 디바운스** + 최소 이동거리를 넣었다
- [ ] **429 에 자동 재시도를 넣지 않았다**
- [ ] 이전 요청을 `AbortController` 로 취소한다

**값이 비는 곳**

- [ ] `estimatedCost*` 가 `null` 일 때 **"0원" 이 아니라 "산정 전"** 으로 표시한다
- [ ] `aiTotal*` 이 `null` 일 때 "비교 없음" 문구를 준비했다
- [ ] `thumbnailUrl` 이 `null` 일 때의 자리를 그렸다
- [ ] 촬영 오버레이 실루엣을 **FE 번들**에 준비했다 (서버가 주지 않는다)

**관리자 화면**

- [ ] **삭제 버튼을 만들지 않았다** (부품명 매핑 제외)
- [ ] 부품명 매핑의 식별자를 **`?rawName=`** 쿼리로 보낸다
- [ ] `DELETE` 에 **`changeReason`** 을 함께 보낸다
- [ ] 입력 유효 범위를 **`bounds` 에서** 가져온다 (하드코딩 금지)
- [ ] 409 `VERSION_CONFLICT` 에 **"다시 읽고 재시도"** 흐름을 만들었다
- [ ] 409 중복 / 409 참조 중 / 409 버전 충돌을 **서로 다르게** 안내한다
- [ ] `beforeData`·`afterData` 를 `JSON.parse()` 한다
- [ ] 감사 로그에 **IP 열을 만들지 않았다** (항상 `null`)
- [ ] 부품명 매핑 목록에 **`inDictionary`** 를 노출한다

**금지**

- [ ] `.ts` 파일 · `lang="ts"` · `interface` · `type` 선언 · 타입 어노테이션이 **없다**
- [ ] 서버가 주지 않는 필드를 화면에서 기대하지 않는다
- [ ] 문서에 시크릿 값을 적지 않았다
