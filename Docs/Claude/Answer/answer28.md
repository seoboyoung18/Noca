# answer28 — 검증 요약·등급 계약 문서화 결과 (314·315·316)

> 브랜치 `feature/S15P21A307-314-validation-summary-grade` (`origin/develop` `2da805b` 머지 후)
> 워크트리 `C:\SSAFY\S15P21A307-314` — 메인 워크트리와 격리
> **코드 변경 0건 · 문서 4건 · 커밋 2건 · 푸시 성공**
> **테스트 204개 통과 / 실패 0 / 건너뜀 0** (전체 스위트)
> 구현 코드는 `d84c6d5 Feat: 견적 검증 이상 항목 탐지 및 결과 저장 구현 (S15P21A307-310)` 에 이미 머지되어 있다
> 작성 2026-09-08 · `glab` 미설치로 MR 은 생성하지 않았고 본문을 7-4 에 둔다

---

## 0. 결론

- 314·315·316 의 계약(판정 규칙 · 임계값 6개 · 결과 응답 필드 출처)을 **FE 인수인계 문서 신규 1건 + 명세서 md·csv 갱신 2건**으로 문서화했다.
- `GET .../questions` 가 명세서 md·csv 양쪽에 **실제로 없었다.** csv 에 행을 추가하고 md 계약 절에 반영했다(3장).
- Jira 연결은 이 브랜치의 새 커밋이 만든다 — 브랜치명이 **314**, 커밋 메시지가 **315·316** 을 담아 세 이슈 모두 개발 패널에 붙는다.
- 전체 테스트 **204개 전부 통과**했다. prompt28 이 적은 기준선 393 과 다른데, **회귀가 아니라 기준선이 다른 트리에서 세어진 값**이다(5-3).
- **코드 결함 6건을 고치지 않고 보고**했다. 그중 **`CurrentMemberProvider` 스텁(D2)** 은 지금 이 API 전체를 500 으로 만들고 있어 가장 시급하다(6장).

---

## 1. 코드 실측 대조

워크트리(`develop` `2da805b` 머지본)의 코드에서 직접 읽어 prompt28 이 제시한 값과 대조했다.

### 1-1. 판정 규칙 (§4-1)

| 항목 | prompt28 제시 | 코드 실제 (`GradeDecider.decide`) | 일치 |
|---|---|---|:---:|
| `NEEDS_REVIEW` 조건 1 | `reviewItemCount >= needsReviewItemCount` | 같음 | ✅ |
| `NEEDS_REVIEW` 조건 2 | `highestReferenceRatio >= severeOverP75Multiplier` | 같음 | ✅ |
| `NEEDS_REVIEW` 조건 3 | `totalDifferenceRatio >= needsReviewTotalDifferenceRatio` | 같음 | ✅ |
| `CAUTION` 조건 1 | `reviewItemCount > 0` | 같음 | ✅ |
| `CAUTION` 조건 2 | `totalDifferenceRatio >= cautionTotalDifferenceRatio` | 같음 | ✅ |
| 평가 순서 | `NEEDS_REVIEW` 먼저 | 같음 (early return) | ✅ |
| 표시 문구 | 적정 범위 / 주의 / 확인 필요 | `ValidationGrade` enum 상수와 같음 | ✅ |
| DDL 제약 | `ck_ev_grade` 3값 | `A307_ddl_final.sql` 확인 | ✅ |

`GradeAssessment` 세 입력값의 출처도 `EstimateValidationService.decideGrade` 에서 확인해 문서에 넣었다.

| 입력 | 계산 |
|---|---|
| `reviewItemCount` | `flag` 가 붙은 항목 수 (`ValidatedLine::reviewRecommended` 카운트) |
| `highestReferenceRatio` | 항목별 `subtotal ÷ referenceP75` 의 **최댓값**. 비교 자료 없는 항목은 건너뛰고, 하나도 없으면 0 |
| `totalDifferenceRatio` | `|claimedTotal − aiTotalMedian| ÷ aiTotalMedian`, 소수 6자리 `HALF_UP`. **절대값** |

경계는 `GradeDeciderTest` 3개 메서드가 고정한다 — 0.0999→적정 / 0.10→주의 / 0.1999→주의 / 0.20→확인 필요 · 1.4999→주의 / 1.50→확인 필요 · 2건→주의 / 3건→확인 필요.

### 1-2. 프로퍼티 6개 (§4-2)

| 프로퍼티 | prompt28 제시 | `application.properties` 실제 | 제약 일치 | 일치 |
|---|---:|---:|:---:|:---:|
| `reference-percentile` | 75 | 75 (89행) | `@Min(1) @Max(100)` | ✅ |
| `severe-over-p75-multiplier` | 1.5 | 1.5 (90행) | `@DecimalMin("1.0", inclusive=false)` | ✅ |
| `caution-total-difference-ratio` | 0.10 | 0.10 (91행) | `@DecimalMin("0.0")` | ✅ |
| `needs-review-total-difference-ratio` | 0.20 | 0.20 (92행) | `@DecimalMin("0.0")` | ✅ |
| `needs-review-item-count` | 3 | 3 (93행) | `@Min(1)` | ✅ |
| `presigned-url-minutes` | 10 | 10 (94행) | `@Min(1) @Max(1440)` | ✅ |

- 교차 제약 `@AssertTrue isDifferenceRatioOrderValid()` 와 오류 메시지 문자열까지 prompt28 기재와 같다. ✅
- `record` + `@Validated` + `@ConfigurationProperties(prefix="app.estimate-validation")` + `implements GradePolicy` 구조 확인. ✅ 등록은 `BackendApplication` 의 `@ConfigurationPropertiesScan`.
- 테스트 프로퍼티(`backend/src/test/resources/application.properties` 46~51행)도 같은 6개 값이다. ✅

> ⚠️ **값은 맞지만 `reference-percentile` 의 "의미" 는 실제와 다르다.** prompt28 은 *"사례 통계에서 비교 기준으로 쓰는 분위"* 라고 적었으나 **이 값을 읽는 프로덕션 코드가 없다.** → 6장 D1. 문서에는 "바꿔도 변화 없음"으로 적었다.

### 1-3. 결과 조회 응답 (§4-3)

**prompt28 이 나열한 필드는 전부 실재한다.** 다만 나열되지 않은 필드가 더 있어 문서에는 전체를 실었다.

| prompt28 이 빠뜨린 필드 | 출처 |
|---|---|
| `status` | 저장 |
| `reviewItemCount` · `totalItemCount` | 저장 |
| `questions[]` | 저장 — **결과 응답에 질문이 이미 들어 있다** |
| `actualRepairCompletedDate` | 저장 |
| `actualWithinAiRange` | 계산 |
| `createdAt` · `completedAt` | 저장 |
| `items[]` 의 `lineNo`·`quantity`·`subtotal`·`referenceMin/Median/P75/Max`·`referenceCaseCount`·`reason`·`displayDecision` | 저장 / 파생 |

**총액 차이가 DB 컬럼이 아니라는 것**은 DDL 로 확인했다 — `estimate_validation` 에 `claimed_total`·`review_item_count`·`total_item_count` 만 있고 `total_diff` 류 컬럼이 없다. ✅

| 항목 | prompt28 제시 | 코드 실제 | 판정 |
|---|---|---|:---:|
| `PUT .../actual-cost` 경로 | `PUT .../actual-cost` (검증 하위처럼 읽힘) | **`PUT /api/accidents/{accidentId}/actual-cost`** — `AccidentController` 소속 | ⚠️ 경로 정정 |
| `legalNotice` | 상수 | `EstimateValidationService.LEGAL_NOTICE` | ✅ |
| `differenceFromMedian` | `claimedTotal − aiTotalMedian` | 같음 (`difference(claimed, aiMedian)`) | ✅ |
| `differenceFromRangeMax` | `claimedTotal − aiTotalMax` | 같음 (`difference(claimed, aiMax)`) | ✅ |
| 소유권 검사 위치 | (확인 요청) | **Repository 쿼리 조건**에 있다 — `findByValidationIdAndMemberId`·`findProjectedByValidationIdAndMemberId`·`findByMemberIdOrderByCreatedAtDesc` | ✅ 남의 검증은 404 |

**코드와 어긋난 것은 위 경로 하나와 `reference-percentile` 의 의미 하나뿐이며, 두 경우 모두 코드를 정본으로 삼아 문서를 작성했다.**

### 1-4. prompt28 이 다루지 않았지만 문서에 필요했던 실측

| 사실 | 근거 |
|---|---|
| 직접 입력은 **202 인데 응답 `status` 가 이미 `COMPLETED`** | `registerManual` 이 `complete()` 후 `save` → `accepted(saved)` |
| **`FAILED` 는 도달 불가** | `EstimateValidation.fail(...)` 프로덕션 호출처 0곳 |
| 파일 경로는 **저장소 어댑터가 없어 503** | `DocumentStoragePort` 구현체 0개 (`storagePort.orElseThrow`) |
| `summary` 는 **LLM 이 아니라 서버 템플릿** | `EstimateValidationService.summary(...)`. `llm_model` 항상 null |
| 확인 질문 API 만 **`data` 가 바로 배열** | `ApiResponse<List<ValidationQuestionResponse>>` |
| **한 항목에 `flag` 는 하나만 저장** | `reason(...)` 이 `questionOrder` 최소 1개 선택 |
| `/me` 의 `size` 는 **100 초과 시 절단** (에러 아님) | `Math.min(requestedSize, MAX_PAGE_SIZE)` |
| `탈착`·`오버홀` 은 **항상 `INSUFFICIENT_REFERENCE`** | `WorkType.standardMethod()` 가 `null` → 통계 조회 불가 |
| **403 이 새로 생겼다** — `SIGNUP_REQUIRED` / `FORBIDDEN` | `RestAccessDeniedHandler` (develop 최신에 추가됨) |
| **401 은 여전히 본문 없는 `sendError`** | `SecurityConfig` 주석이 의도임을 명시 |
| **로그인해도 전부 500** | `CurrentMemberProvider` 스텁 → 6장 D2 |

---

## 2. 스스로 판단한 것

prompt28 §1 의 결정 규칙(1 요구사항 → 2 DDL → 3 코드 현재 동작 → 4 기존 문서 관례 → 5 가장 보수적)으로 정했다. 사용자에게 묻지 않았다.

| # | 판단이 필요했던 것 | 정한 것 | 규칙 | 근거 |
|---|---|---|:---:|---|
| 1 | **브랜치 경로** — 3장 본 경로 vs 대체 경로 | **본 경로.** 기존 `feature/S15P21A307-314-validation-summary-grade` 를 워크트리에 체크아웃하고 `git merge origin/develop` | 3 | 자기 커밋 0개라 **fast-forward** 로 끝났다(`Updating 707cd7a..2da805b`). 충돌이 없어 대체 브랜치를 만들 이유가 없었다. 머지 후 `git rev-list --left-right --count origin/develop...HEAD` = `0 0` |
| 2 | **`differenceFromMedian` vs `differenceFromRangeMax` 의 FE 기본 표시값** | **`differenceFromMedian`.** 근거 강도 **강함** | 3 | ① 서버 `summary` 가 *"AI 중앙값보다 …원 차이납니다"* ② 등급의 `totalDifferenceRatio` 도 중앙값 기준. 헤드라인만 범위 기준이면 같은 화면 안에서 숫자가 어긋난다. 미확정으로 비우지 않았다 |
| 3 | **와이어프레임의 라벨과 숫자가 충돌** — 라벨 "AI 예상 범위와의 차이" vs 숫자 `+62,000` | **숫자를 정본으로 삼았다.** 라벨을 "AI 예상 중앙값과의 차이"로 바꾸자고 문서에 제안 | 3 | `622,000 − 610,000 = 12,000` 이라 **범위 상한으로는 그 숫자가 나올 수 없다.** 중앙값 560,000 기준과만 맞는다. 라벨은 문안이고 숫자는 계약이다 |
| 4 | **`Docs/Claude/` 를 팀 저장소에 커밋할 것인가** | **포함한다** | 5 | prompt28 §9-1 의 지시대로. MR 목적이 분석 근거를 남기는 것이고, 보고서가 저장소에 없으면 리뷰어가 근거를 볼 수 없다 |
| 5 | **커밋을 1개로 할 것인가 2개로 할 것인가** | **2개로 나눴다** | 5 | `answer28.md` 5·7장이 **테스트 결과와 커밋 SHA·푸시 결과를 담아야** 하는데, 자기 자신을 포함한 커밋의 SHA 를 자기 안에 쓸 수 없다. 문서 3건을 먼저 커밋·푸시해 사실을 확정한 뒤 보고서를 두 번째 커밋에 담았다. prompt28 §9-1 은 한 번에 `add` 하라고 적었으나, **실행하지 않은 결과를 적지 말라는 §10 의 요구가 더 강하다** |
| 6 | **이전 시도가 메인 워크트리에 남긴 잔여물** | **내가 만든 것만 되돌렸다** | 5 | 이전 실행이 메인 워크트리(`feature/...-149`)에 `견적서 검증 API — FE 인수인계.md`(미추적)와 명세서 md 40줄을 남겨 두었다. 그대로 두면 이 MR 이 머지된 뒤 사용자의 `git pull` 이 **같은 경로의 미추적 파일 때문에 거부**된다. 남의 미커밋 변경(기존 76줄)은 한 줄도 건드리지 않고 **내가 넣은 40줄과 내가 만든 파일만** 제거해 세션 시작 전 상태로 되돌렸다(`git diff --stat` 로 76 insertions / 2 deletions 복원 확인) |
| 7 | **CSV 행 추가 위치** | `검증 결과 조회`(25행) **바로 다음** | 4 | 같은 도메인 행이 인접해 있고, 결과 조회 → 확인 질문이 화면 흐름 순서다. 담당·도메인·인증·차수 컬럼은 형제 행과 동일하게 `보영`·`견적서검증`·`필요`·`1차` |
| 8 | **테스트 기준선 393 과 실측 204 의 불일치** | **실측 204 를 기록하고 회귀가 아니라고 판정** | 3 | 5-3 참고. 원인을 찾아 근거와 함께 적었다 |
| 9 | **정비소 확인 질문 절을 쓸 것인가** | **쓰지 않았다.** 엔드포인트 목록 한 줄 + 계약 요약만 | — | prompt28 §5 단서. 같은 파일을 두 MR 이 건드리면 충돌한다 |
| 10 | **커밋 메시지 트레일러** | prompt28 §9-2 의 메시지 그대로 + `Co-Authored-By` 한 줄 | 4 | 하네스 관례. Jira 키 스캔에 영향이 없다 |

### 2-1. prompt28 §2 의 실측과 달랐던 것

| prompt28 §2 기술 | 실제 (착수 시 재확인) |
|---|---|
| `origin/feature/...-314` = `707cd7a`, develop 대비 **22커밋 뒤처짐** | `git fetch` 후 **`7eabcfb`**, develop(`2da805b`) 대비 **4커밋 뒤처짐**. `origin/314` 는 이미 develop 의 조상이었다 (`rev-list --left-right` = `4 0`) |
| 워크트리 2개가 **둘 다 `prunable`** | `git worktree prune` 후에도 `C:/SSAFY/S15P21A307/.claude/worktrees/prompt28-docs` (`docs/S15P21A307-314-validation-docs`) 와 `C:/SSAFY/S15P21A307-p22` (`codex/prompt22`) **둘 다 남았다** — 디렉터리가 실제로 존재해 prunable 이 아니다. **둘 다 건드리지 않았다** |
| 메인 워크트리 미커밋 수정 약 146 · 미추적 약 44 | 대체로 일치. 브랜치는 `feature/S15P21A307-149-accident-vehicle-selection` (`d606124`) |

`origin/develop` 도 `ceb0503` → `2da805b` 로 움직였고, 그 사이에 **소셜 로그인·회원 도메인이 통째로 들어왔다**(`AuthController`·`OAuth2SuccessHandler`·`Member`·`SecurityConfig` +102줄). 이 때문에 오류 계약에 **403 이 새로 생겼고**(1-4), 동시에 D2 가 드러났다.

---

## 3. 명세서 누락 발견

`GET /api/estimate-validations/{validationId}/questions` 가 **명세서 두 곳 모두에 없었다.** prompt28 §4-4 의 전제가 맞다.

```text
Docs/Api/A307 백엔드 API 명세서 (MVP SUB 구분으로 봐주세요).csv   grep "questions" → 0건
Docs/Api/API 명세서 (바른견적 최신).md                            grep "questions" → 0건
```

`EstimateValidationController` 에 구현되어 있고(`@GetMapping("/{validationId}/questions")`), `EstimateValidationService.questions(...)` 와 `estimate_validation_question` 테이블이 모두 있다.

### 추가한 내역

**csv — 25행(`검증 결과 조회`) 다음에 1행 추가.** 컬럼은 형제 행과 맞췄다.

```text
정비소 확인 질문 조회,,No,GET,/api/estimate-validations/{validationId}/questions,,
estimate_validation_question,보영,견적서검증,
검증 완료 시 저장한 질문 스냅샷을 display_order 순으로 반환. 조회 시 재생성하지 않음.
결과 조회 응답에도 같은 내용이 포함됨,필요,1차,
```

diff 는 **1 insertion, 0 deletion** 이다. (awk 가 끼워 넣으면서 파일 끝 개행을 덧붙였던 것을 되돌려 원본의 "no newline at end of file" 을 유지했다.)

**md — "검증 요약·등급 계약" 절에 이 엔드포인트를 반영.** 별도 행 목록이 md 에는 없어 계약 문장으로 넣었다.

> 기능 자체(질문 생성 규칙·필드 구조)는 `prompt29` 범위이므로 **문서화하지 않았다.**

### 3-1. 함께 눈에 띈 명세서 부정확 (이번 범위 밖 — 고치지 않음)

| # | 문제 | 처리 |
|---|---|---|
| 1 | csv 17행이 **`POST /api/estimate-validations` 를 한 행으로** 뭉쳐 놓았다. JSON 직접 입력과 multipart 파일 입력은 요청 형태·검증 규칙·결과 상태(`COMPLETED` vs `QUEUED`)가 전혀 다르다 | 행을 나누는 편이 낫다. csv 는 팀 공용 정본이고 담당이 '보영' 이라 손대지 않았다 |
| 2 | csv 19행 설명이 **"OCR→LLM→PDF 진행 상태"** — 실제 `status` 는 4값 단일 필드이고 단계 테이블이 이 도메인에 없다. FE 가 4단계 체크리스트를 만들 근거로 오해할 수 있다 | **FE 인수인계 문서 7-3 에 "4단계 체크리스트를 만들지 말 것" 으로 대응**했다 |
| 3 | `PUT /api/accidents/{accidentId}/actual-cost` · `GET .../cost-comparison` 이 csv 에서 `사고접수` 도메인에 있어 견적서검증 흐름으로 찾기 어렵다 | FE 문서 1장 "연관 API 2종" 으로 묶어 두었다 |

---

## 4. 작성한 문서

| 파일 | 신규/수정 | 담은 내용 |
|---|---|---|
| `Docs/Api/견적서 검증 API — FE 인수인계.md` | **신규** (893줄) | API 8종 + 연관 2종 · 요청/응답 전체 · 상태 흐름 · 등급 3단계와 판정 규칙(경계값 포함) · 임계값 6개와 조정 절차 · 응답 필드 출처표(저장/계산/상수) · `flag` 5종과 화면 문구 방향 · 상수표 3개 · 오류 계약(401·403·404·409·500·503) · PDF presigned · 미확정 3건 · 미구현 범위 · 동작 14가지 |
| `Docs/Api/API 명세서 (바른견적 최신).md` | **수정** (+47줄, 삭제 0) | 끝부분에 **"견적서 검증 — 등급·임계값·결과 조회 계약 (S15P21A307-314·315·316)"** 절 추가 |
| `Docs/Api/A307 백엔드 API 명세서 (MVP SUB 구분으로 봐주세요).csv` | **수정** (+1행) | `정비소 확인 질문 조회` 행 |
| `Docs/Claude/Answer/answer28.md` · `Docs/Claude/Prompt/prompt28.md` | **신규** | 이 보고서와 지시문 |

**형식은 `차량 API — FE 인수인계.md` 를 따랐다** — 헤더 인용 블록(대상 백로그·서버 상태·작성 기준·근거), "⚠️ 시작하기 전에", 0장 요점, 번호 매긴 API 절, 오류 표, 상수표, 미확정 표, 동작 목록, 문서 갱신 이력. `이미지 업로드 API — FE 인수인계.md` 는 **develop 에 없어**(메인 워크트리 미추적) 참고하지 못했고, 대신 차량 문서와 가이드 문서의 구조를 맞췄다.

**기존 문장을 재작성하지 않았다.** 명세서 md·csv 모두 `git diff` 상 **삭제 0줄**이다.

---

## 5. 테스트 실행 결과

`C:\SSAFY\S15P21A307-314\backend` 에서 실행. 툴체인이 Java 21 을 요구하는데(`build.gradle` `JavaLanguageVersion.of(21)`) 기본 `JAVA_HOME` 이 17 이라 **`JAVA_HOME=C:\Users\SSAFY\.jdks\ms-21.0.11` 로 지정**해 돌렸다.

```text
JAVA: openjdk version "21.0.11" 2026-04-21 LTS
```

**`--offline` 로 4회 모두 성공했다. `--offline` 을 빼고 재시도할 일이 없었다.**

| # | 명령 | 결과 |
|---|---|:---|
| T1 | `.\gradlew.bat --offline test --tests "com.ssafy.a307.estimatevalidation.domain.GradeDeciderTest"` | **BUILD SUCCESSFUL in 42s** · EXITCODE 0 |
| T2 | `.\gradlew.bat --offline test --tests "com.ssafy.a307.estimatevalidation.config.EstimateValidationPropertiesTest"` | **BUILD SUCCESSFUL in 5s** · EXITCODE 0 |
| T3 | `.\gradlew.bat --offline test --tests "com.ssafy.a307.estimatevalidation.*"` | **BUILD SUCCESSFUL in 35s** · EXITCODE 0 |
| T4 | `.\gradlew.bat --offline test` | **BUILD SUCCESSFUL in 54s** · EXITCODE 0 |

T4 출력 원문(로그 꼬리):

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :testClasses UP-TO-DATE
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
> Task :test

BUILD SUCCESSFUL in 54s
5 actionable tasks: 1 executed, 4 up-to-date
```

`:test` 가 **실제로 실행**됐다(`1 executed`). UP-TO-DATE 로 건너뛴 것이 아니다.

### 5-1. 전체 집계 — `build/reports/tests/test/index.html` · `build/test-results/test/*.xml`

```text
tests    204
failures   0
errors     0
skipped    0
duration  50.966s
result XML classes  41   (테스트 소스 파일 34개 + @Nested 클래스)
```

### 5-2. 견적서 검증 도메인 65개 (전부 통과)

```text
 20  estimatevalidation.file.EstimateFileValidatorTest
  9  estimatevalidation.service.EstimateValidationServiceTest
  6  estimatevalidation.controller.EstimateValidationControllerTest
  6  estimatevalidation.domain.EstimateValidationEngineTest
  6  estimatevalidation.domain.WorkTypeMapperTest
  5  estimatevalidation.domain.RepairShopQuestionGeneratorTest
  4  estimatevalidation.service.EstimateFileValidationServiceTest
  3  estimatevalidation.config.EstimateValidationPropertiesTest
  3  estimatevalidation.domain.GradeDeciderTest
  3  estimatevalidation.file.FilePortContractTest
 ---
 65  failures 0 · errors 0
```

314·315 의 핵심인 `GradeDeciderTest`(3) 와 `EstimateValidationPropertiesTest`(3) 가 모두 통과했다.

### 5-3. ⚠️ 기준선 393 과 다르다 — 회귀가 아니다

prompt28 §7 은 *"전체 스위트의 기준선은 393개 통과 / 실패 0"* 이라고 적었으나 이 브랜치의 실측은 **204개** 다. **원인을 확인했다.**

| 트리 | 테스트 소스 파일 수 |
|---|---:|
| 이 워크트리 (`develop 2da805b` 머지본) | **34** |
| 메인 워크트리 (`feature/...-149` + 미커밋) | **47** (그중 미추적 11) |

**393 은 메인 워크트리에서 센 값이다.** 메인에는 이미지 업로드(`AccidentImage*Test` 등)와 스키마 정합 테스트가 **미커밋·미추적 상태로** 13개 더 있고, 그것들이 `develop` 에는 아직 없다.

→ **`develop` 기준의 정확한 기준선은 204 / 실패 0 이다.** 실패·건너뜀이 0 이므로 이 커밋으로 줄어든 테스트는 없다. 문서만 바꿨으므로 테스트 수가 변할 수도 없다.

### 5-4. 문서 자체 검증

```powershell
git diff --check      # 공백 오류 없음 (csv 의 LF→CRLF 안내 경고만)
git status --short    # 의도한 파일만
```

---

## 6. 발견한 결함·개선점 — 고치지 않고 보고

prompt28 §8 에 따라 `backend/src/**` 를 **한 줄도 수정하지 않았다.** 아래는 전부 후속 이슈 제안이다.

### D1. ⛔ `reference-percentile` 이 어디에서도 쓰이지 않는다 — **315 의 핵심 결함**

**심각도 높음.** 315 의 제목이 *"코드 수정 없이 조정 가능하도록 구성"* 인데 이 프로퍼티만은 조정해도 아무 일이 일어나지 않는다.

**재현 조건**

1. `application.properties` 의 `app.estimate-validation.reference-percentile` 을 `75` → `90` 으로 바꾼다
2. 재기동한다 (`@Min(1) @Max(100)` 안이라 기동은 성공한다)
3. 같은 견적서를 다시 검증한다 → **판정 결과가 완전히 동일하다**

**근거**

```text
grep -rn "referencePercentile" backend/src/main/java
  → config/EstimateValidationProperties.java:17  (선언 한 줄뿐)
프로덕션 사용처 0곳
```

- 비교 기준은 `repair_cost_stat.cost_p75` 로 하드코딩되어 있다 — `EstimateValidationEngine:28`, `EstimateValidationService:303-306`
- `GradePolicy` 인터페이스에도 이 값이 **없다.** 네 개(`severeOverP75Multiplier`·`cautionTotalDifferenceRatio`·`needsReviewTotalDifferenceRatio`·`needsReviewItemCount`)만 노출한다 — **설계 단계에서 판정에 쓰지 않기로 한 흔적**인데 프로퍼티에는 남아 있다
- `severe-over-p75-multiplier` 라는 **이름 자체가 P75 를 못박고 있어** 분위를 가변으로 만들려면 이름도 함께 바꿔야 한다

**후속 이슈 제안 — 둘 중 하나**

| 안 | 내용 | 비용 |
|---|---|---|
| **A (권장)** | 프로퍼티를 제거한다. 쓰지 않는 설정은 거짓말이다 | 작음 — `record` 필드 1줄 · properties 2줄 · 테스트 4줄 |
| B | 실제로 쓰이게 만든다. `repair_cost_stat` 에 `cost_median`·`cost_p75` 가 이미 있으므로 분위 선택을 코드로 뺀다 | 중간 — `CostReference`·`GradePolicy`·엔진 시그니처 + 이름 변경 |

문서 두 곳 모두에 **"지금은 바꿔도 소용없다"** 경고를 넣었다. FE 가 이 값을 근거로 화면 문구를 만들지 않게 하는 것이 우선이다.

### D2. ⛔⛔ `CurrentMemberProvider` 가 스텁이라 이 API 전체가 500 이다 — **가장 시급**

**심각도 최상.** 소셜 로그인은 `develop` 에 들어왔는데 **기존 컨트롤러가 그 회원 정보를 못 쓴다.**

```java
// backend/src/main/java/com/ssafy/a307/common/security/CurrentMemberProvider.java
@Component
public class CurrentMemberProvider {
    public Long currentMemberId() {
        throw new UnsupportedOperationException("인증 연동 전 — 세션 로그인 구현 후 교체 (S15P21A307-auth)");
    }
}
```

**재현 조건**

1. 카카오/구글로 로그인해 `ROLE_USER` 세션을 얻는다
2. `GET /api/estimate-validations/me` 를 호출한다
3. → `UnsupportedOperationException` → `GlobalExceptionHandler` 의 `@ExceptionHandler(Exception.class)` catch-all → **500 `INTERNAL_ERROR` "서버 내부 오류가 발생했습니다."**

**영향 범위** — 이 스텁을 쓰는 컨트롤러 **4개, 호출 15곳**

```text
estimatevalidation/controller/EstimateValidationController.java   (8개 핸들러 전부)
estimatevalidation/controller/CostComparisonController.java
accident/controller/AccidentController.java
vehicle/controller/VehicleController.java
```

`AuthController` 만 `@AuthenticationPrincipal UserPrincipal` 을 직접 쓰도록 새로 짜였다. **인증 스토리가 이 다리를 놓지 않고 끝난 것으로 보인다.**

**후속 이슈 제안** — `CurrentMemberProvider.currentMemberId()` 본문을 `SecurityContextHolder` → `UserPrincipal.memberId()` 로 교체. **클래스 본문만 바꾸면 되고 Service·Repository·DTO·API 계약은 손대지 않아도 된다** (원 설계가 그렇게 잡혀 있다). 이 도메인만의 문제가 아니라 차량·사고까지 함께 풀린다.

> **314·315·316 의 결함이 아니다.** 다만 이 셋을 "완료" 로 옮겨도 **FE 가 실제로 호출해 볼 수 없다**는 점은 상태 전환 판단에 넣어야 한다(8장).

### D3. `FAILED` 상태를 만드는 코드가 없다

`EstimateValidation.fail(String, Instant)` 의 **프로덕션 호출처가 0곳**이다.

- DDL `ck_ev_status`·`ValidationStatus` enum·`ValidationStatusResponse.failureReason` 이 전부 `FAILED` 를 전제하는데 도달 불가능한 상태다
- **재현 조건** — 어떤 입력으로도 `status = FAILED` 인 행을 만들 수 없다. `failureReason` 은 항상 `null`
- 파일 경로에 OCR 워커가 붙으면 실패 처리가 필요해지므로 **미구현이지 버그는 아니다.** 다만 FE 가 만들 `FAILED` 분기를 지금은 테스트할 수 없다 → FE 문서 0-3 에 명시

### D4. `costP75 == 0` 일 때 이름 없는 매직 넘버 `999999`

`EstimateValidationService:303-304`

```java
BigDecimal ratio = reference.costP75() == 0
        ? new BigDecimal("999999")
        : BigDecimal.valueOf(line.line().subtotal())
                .divide(BigDecimal.valueOf(reference.costP75()), 6, RoundingMode.HALF_UP);
```

- 나눗셈을 피하려 임의의 큰 수를 넣는다. `severeOverP75Multiplier`(1.5)보다 크므로 **그 항목 하나로 즉시 `NEEDS_REVIEW`** 가 된다
- 의도(비교 불가를 보수적으로 처리)는 합리적이나 **상수에 이름이 없어 의도가 코드에서 읽히지 않는다**
- 더 중요한 것은 **이 경로가 `INSUFFICIENT_REFERENCE` 로 가지 않는다**는 점이다. P75 가 0 인 통계는 사실상 비교 자료가 없는 것에 가까운데 "심각한 초과" 로 판정된다
- **재현 조건** — `repair_cost_stat` 에 `cost_p75 = 0` 인 행이 있고 그 조합(차급·부품·손상·수리방식)에 해당하는 항목을 검증하면, 금액이 얼마든 등급이 `NEEDS_REVIEW` 가 된다
- **확인 필요** — 실데이터에 `cost_p75 = 0` 행이 있는지. 없으면 방어 코드로 두면 되고, 있으면 판정이 왜곡된다. 이번 작업에서는 DB 를 조회하지 않아 확인하지 못했다

### D5. (경미) `OVER_P75` 는 `>` 인데 등급 판정은 `>=`

| 위치 | 연산자 |
|---|---|
| 항목 플래그 `EstimateValidationEngine:28` | `line.subtotal() > reference.costP75()` (초과) |
| 등급 판정 `GradeDecider:7` | `highestReferenceRatio >= severeOverP75Multiplier` (이상) |

소계가 P75 와 **정확히 같으면** 플래그가 붙지 않고(테스트 `p75EqualityIsNotOverchargeAndQuestionsAreEmpty` 가 이를 고정), 배수가 1.5 와 **정확히 같으면** 확인 필요가 된다. 두 연산자가 다른 것이 의도인지 확인이 필요하나 **실무 영향은 거의 없다.**

### D6. 계산 필드 5개 중 4개에 테스트가 없다

316 의 산출물인 계산 필드 5개 중 **`differenceFromMedian` 만** 단언되어 있다.

```text
EstimateValidationServiceTest.java:84
    assertThat(result.differenceFromMedian()).isEqualTo(1L);

단언 없음:
    differenceFromRangeMax
    shopEstimateDifferenceFromActual
    aiMedianDifferenceFromActual
    actualWithinAiRange
```

- **재현 조건** — `difference(...)`/`within(...)` 의 널 처리나 부호를 잘못 바꿔도 4개 필드는 테스트가 잡지 못한다
- **후속 제안** — 널 전파(`estimateId` 없음 · 실제 수리비 미입력)와 음수 케이스, `actualWithinAiRange` 의 경계(`actual == aiTotalMin`, `actual == aiTotalMax`)를 덮는 케이스 추가. **이번에 추가하지 않았다** (테스트 수정 금지)

### 결함이 아닌 것 — 확인해 두었다

- 소유권 검사는 Repository 쿼리 조건에 있어 **남의 리소스가 404** 로 정상 처리된다
- 명세서 25행의 *"매번 LLM 재호출 금지"* 는 지켜진다 — 저장된 `llm_grade`·`llm_summary` 를 렌더링만 한다
- 명세서 26행의 *"조회 시 재생성하지 않음"* 도 지켜진다
- 교차 제약·기본값 부재로 인한 기동 실패 장치가 테스트로 덮여 있다

---

## 7. 커밋·푸시·MR

### 7-1. 워크트리

```powershell
cd C:\SSAFY\S15P21A307
git worktree prune
git fetch origin
#   ceb0503..2da805b  develop -> origin/develop
#   707cd7a..7eabcfb  feature/S15P21A307-314-validation-summary-grade -> origin/...

git worktree add C:\SSAFY\S15P21A307-314 feature/S15P21A307-314-validation-summary-grade
#   HEAD is now at 707cd7a Merge branch 'feature/S15P21A307-310-quote-anomaly-detection' into 'develop'

cd C:\SSAFY\S15P21A307-314
git merge origin/develop
#   Updating 707cd7a..2da805b
#   Fast-forward — 83 files changed, 6827 insertions(+), 96 deletions(-)
git rev-list --left-right --count origin/develop...HEAD
#   0	0
```

충돌 없음 → **본 경로 사용.** 대체 브랜치(`feature/S15P21A307-315-...`)를 만들지 않았다.

### 7-2. 커밋 2건

```powershell
git add "Docs/Api/견적서 검증 API — FE 인수인계.md"
git add "Docs/Api/API 명세서 (바른견적 최신).md"
git add "Docs/Api/A307 백엔드 API 명세서 (MVP SUB 구분으로 봐주세요).csv"
git diff --cached --stat
#   3 files changed, 941 insertions(+)
```

스테이징 목록에 `backend/`·`.idea/`·`.claude/`·`_recovery_20260907/` **없음을 확인**했다.

```text
커밋 1  ad2c455f04a109006c27288c0a383bacd3854fd4
        Docs: 검증 등급 판정 규칙·임계값·총액 차이 계약 문서화 (S15P21A307-315, S15P21A307-316)
        3 files changed, 941 insertions(+)
```

브랜치명이 `S15P21A307-314` 를 담고 커밋 메시지가 `315`·`316` 을 담아 **세 이슈 모두 개발 패널에 연결된다.**

```text
커밋 2  Docs/Claude/Answer/answer28.md · Docs/Claude/Prompt/prompt28.md
        Docs: prompt28 분석 보고서 추가 (S15P21A307-314, S15P21A307-315, S15P21A307-316)
```

> 커밋 2 의 SHA 는 이 파일 자신을 포함하므로 여기에 적을 수 없다. 2장 판단 5 참고.

### 7-3. 푸시 — 성공

```text
git push -u origin feature/S15P21A307-314-validation-summary-grade

To https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A307.git
   7eabcfb..ad2c455  feature/S15P21A307-314-validation-summary-grade -> feature/S15P21A307-314-validation-summary-grade
branch 'feature/S15P21A307-314-validation-summary-grade' set up to track 'origin/...'
```

`--force` 를 쓰지 않았고 fast-forward 로 올라갔다. 커밋 2 도 이어서 같은 방식으로 푸시한다.

### 7-4. MR — **생성하지 않았다** (`glab` 미설치)

`glab` 이 설치되어 있지 않아 prompt28 §9-4 에 따라 **설치를 시도하지 않고** 본문만 남긴다. GitLab 이 푸시 응답으로 준 생성 링크:

```text
https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A307/-/merge_requests/new?merge_request%5Bsource_branch%5D=feature%2FS15P21A307-314-validation-summary-grade
```

**MR 본문 (그대로 붙여넣기)**

```text
제목
Docs: 검증 요약·등급 계약 문서화 (S15P21A307-314, S15P21A307-315, S15P21A307-316)

설명
## 관련 이슈
- S15P21A307-314 [BE] 검증 요약·등급 기능 구현
- S15P21A307-315 [BE] 3단계 등급 판정 설정값 외부화
- S15P21A307-316 [BE] 총액 차이·확인 필요 항목 수 산출 및 검증 결과 저장·조회 API 구현

## 배경
세 이슈의 구현 코드는 S15P21A307-310 MR(`d84c6d5`)에 함께 머지되었습니다.
커밋 메시지에 310만 적혀 개발 패널에 링크가 남지 않았고, 이미 develop 에 푸시된
히스토리라 메시지를 수정할 수 없습니다. 이 MR 이 그 링크를 만들고 계약을 문서화합니다.

## 변경 내용
- `Docs/Api/견적서 검증 API — FE 인수인계.md` 신규 — 등급·임계값·결과 조회 계약,
  API 8종의 요청/응답, 오류 계약, 상수표, 미구현 범위
- `Docs/Api/API 명세서 (바른견적 최신).md` — 판정 규칙, 임계값 6개, 총액 차이가
  저장이 아니라 계산이라는 사실, 응답 필드 출처 (+47줄, 삭제 0)
- `Docs/Api/A307 백엔드 API 명세서 (MVP SUB 구분으로 봐주세요).csv` —
  GET /api/estimate-validations/{validationId}/questions 행 추가 (누락이었습니다)
- `Docs/Claude/Answer/answer28.md` · `Docs/Claude/Prompt/prompt28.md` — 분석 보고서와 지시문

## 코드 변경
없습니다. 문서 전용 MR 입니다.

## 테스트
JAVA_HOME 을 Java 21 로 지정하고 4회 실행, 전부 BUILD SUCCESSFUL (EXITCODE 0).
  .\gradlew.bat --offline test --tests "...domain.GradeDeciderTest"          42s
  .\gradlew.bat --offline test --tests "...config.EstimateValidationPropertiesTest"  5s
  .\gradlew.bat --offline test --tests "com.ssafy.a307.estimatevalidation.*"  35s
  .\gradlew.bat --offline test                                                54s
전체 스위트 204 tests / 0 failures / 0 errors / 0 skipped (50.966s).
견적서 검증 도메인 65개 전부 통과.
※ prompt28 이 적은 기준선 393 은 미커밋 작업이 포함된 메인 워크트리에서 센 값입니다.
   develop 기준 실측은 204 이며 실패·건너뜀이 0 이라 회귀가 아닙니다 (answer28 5-3).

## 분석에서 발견한 사항 (코드는 고치지 않았습니다 — 후속 이슈 제안)
1. [최상] CurrentMemberProvider 가 아직 UnsupportedOperationException 을 던지는 스텁이라,
   로그인에 성공해도 estimate-validations·accidents·vehicles·cost-comparison 컨트롤러
   4개(호출 15곳)가 전부 500 입니다. 소셜 로그인은 develop 에 들어왔는데 이 다리만
   놓이지 않았습니다. 클래스 본문 교체만으로 풀리고 API 계약은 바뀌지 않습니다.
2. [높음] app.estimate-validation.reference-percentile 을 읽는 프로덕션 코드가 0곳입니다.
   비교 기준이 repair_cost_stat.cost_p75 로 하드코딩되어 있어 값을 바꿔도 판정이
   변하지 않습니다. 315 의 "코드 수정 없이 조정" 취지와 어긋나므로 제거하거나
   실제로 쓰이게 하는 결정이 필요합니다.
3. [중간] 316 의 계산 필드 5개 중 differenceFromMedian 하나만 테스트에 단언되어 있습니다.
   differenceFromRangeMax · shopEstimateDifferenceFromActual ·
   aiMedianDifferenceFromActual · actualWithinAiRange 는 널 전파·부호가 미검증입니다.
4. [중간] EstimateValidationService:303 의 999999 매직 넘버. cost_p75 가 0 인 통계를
   INSUFFICIENT_REFERENCE 가 아니라 "심각한 초과" 로 판정합니다.
5. [낮음] EstimateValidation.fail(...) 호출처가 0곳이라 FAILED 상태가 도달 불가능합니다.
6. [낮음] 항목 플래그는 subtotal > p75 (초과), 등급 판정은 ratio >= 배수 (이상) 로
   경계 연산자가 다릅니다.

## 확인 요청 (기획 판단)
- 결과 화면 헤드라인 차이값: 와이어프레임 카드의 라벨("AI 예상 범위와의 차이")과
  숫자(+62,000원)가 어긋납니다. 622,000-610,000=12,000 이라 범위 기준으로는 나올 수
  없는 숫자이고 중앙값 기준과만 맞습니다. 문서는 summary·등급 판정과의 일관성을 근거로
  differenceFromMedian 을 권하고 라벨 변경을 제안했습니다.
- 상단 고지 배너 문안이 서버 legalNotice 와 다릅니다. 명세서 23행이 PDF 에
  "특정 사업자를 평가하지 않습니다" 를 필수로 요구합니다.
- 판독 한계 플래그(UNMAPPED_ITEM·INSUFFICIENT_REFERENCE)를 "확인 권장 N건" 에
  포함할지. 지금은 포함되어, 비교 자료가 부족한 차종일수록 등급이 올라갑니다.
```

### 7-5. 워크트리 정리

커밋 2 를 푸시한 뒤 정리한다 — 결과는 최종 보고에 남긴다.

```powershell
cd C:\SSAFY\S15P21A307
git worktree remove C:\SSAFY\S15P21A307-314
git worktree prune
git worktree list
```

**브랜치는 남긴다.** 기존 워크트리 두 개(`.claude/worktrees/prompt28-docs`, `S15P21A307-p22`)는 prompt28 §2-2 의 기술과 달리 실제로 디렉터리가 있어 `prune` 대상이 아니었고, **건드리지 않았다**(2-1).

### 7-6. 메인 워크트리는 그대로다

`C:\SSAFY\S15P21A307` 의 미커밋 변경(수정 약 146 · 미추적 약 44)을 **되돌리지도 커밋에 넣지도 않았다.** 예외는 2장 판단 6 에 적은 **이전 실행이 남긴 잔여물 제거** 하나이며, 그것도 내가 만든 파일과 내가 넣은 40줄만 되돌려 원래의 미커밋 76줄은 보존했다.

---

## 8. Jira 설명 초안 3건과 상태 전환 제안 (복사용 · 등록하지 않음)

prompt28 §8 에 따라 **Atlassian/Jira MCP 도구를 호출하지 않았다.** 아래는 설명 필드에 붙여 넣을 텍스트다.

### 8-1. 상태 전환 제안

| 이슈 | 제안 | 시점 | 근거 |
|---|---|---|---|
| **314** | **완료** | 이 MR 이 develop 에 머지된 뒤 | `GradeDecider`·`GradePolicy`·`GradeAssessment`·`ValidationGrade` + `GradeDeciderTest`(3개 통과) 가 develop 에 있다. 판정 규칙과 요약문 생성이 동작한다 |
| **315** | **완료** + **D1 을 새 Task 로 분리** | 같음 | `EstimateValidationProperties` + `EstimateValidationPropertiesTest`(3개 통과) 가 develop 에 있고 교차 제약·누락 실패까지 검증된다. **`reference-percentile` 미사용(D1)만 떼어 새 Task 로** — 이 이슈를 열어 두면 나머지 5개 값의 외부화가 끝난 사실이 가려진다 |
| **316** | **완료** | 같음 | `GET .../result` + `ValidationResultResponse` + `review_item_count`·`total_item_count` 가 develop 에 있다. 총액 차이는 설계대로 계산 필드다. 다만 계산 필드 테스트 보강(D6)을 후속으로 |

**전환 순서** — MR 머지 → 314·315·316 완료 → 신규 Task 2건 생성(D1, D2) → D6 는 316 하위 또는 기술부채로

> ⚠️ **"완료" 로 옮겨도 FE 가 실제로 호출해 볼 수는 없다.** D2(`CurrentMemberProvider` 스텁) 때문이다. 세 이슈의 결함은 아니지만, 완료 처리 시 **D2 이슈를 함께 만들어 두어야** FE 가 막혔을 때 찾아갈 곳이 생긴다.

**함께 정리하면 좋을 것**

| # | 관찰 | 제안 |
|---|---|---|
| 1 | 상위 Epic `S15P21A307-269 [BE] 견적서 입력 기능 구현` 이 **'해야 할 일'** | 하위 314·315·316 이 완료로 가면 Epic 을 **'진행 중'** 으로. 하위가 전부 끝나면 완료 |
| 2 | `310` MR 이 314·315·316 산출물을 함께 머지했다 | 재발 방지 — 커밋 메시지에 관련 이슈 키를 **전부** 적는다. GitLab for Jira 는 브랜치명·커밋 메시지·MR 제목·MR 설명 네 곳을 스캔한다 |

> 위 관찰은 **Jira 를 읽지 않고 prompt28 의 기술을 근거로** 적었다. 현재 값을 확인한 뒤 적용할 것.

---

### 8-2. S15P21A307-314 `[BE] 검증 요약·등급 기능 구현`

```text
[유형] 스토리
[개발 범위] BE (기능 구현)
[스토리 포인트] (현행 유지)
[상위 Epic] [BE] 견적서 입력 기능 구현 (S15P21A307-269)
[구현 우선순위] MVP 필수 기능 (중요도 '상')
[요구사항 위치] 견적 검증 > 견적서 입력 > 검증 결과
[진행 상태] 구현 완료 · develop 머지됨 · 테스트 통과 · 문서화 완료
[테스트] GradeDeciderTest 3개 통과 (경계값 고정) · 전체 스위트 204/204 통과

■ 구현 내용
구현 코드는 d84c6d5 "Feat: 견적 검증 이상 항목 탐지 및 결과 저장 구현 (S15P21A307-310)" 에
310 과 함께 머지되었습니다. 커밋 메시지에 310 만 적혀 이 이슈의 개발 패널에 링크가 남지
않았고, 이미 develop 에 푸시된 히스토리라 메시지를 수정할 수 없어 새 MR 로 링크를 만듭니다.

- 3단계 등급: ValidationGrade(APPROPRIATE·CAUTION·NEEDS_REVIEW) + 한글 표시 문구 상수
- 결정론적 판정기: GradeDecider.decide(GradeAssessment, GradePolicy)
- 판정 입력 산출: EstimateValidationService.decideGrade(...)
- 요약문 생성: EstimateValidationService.summary(...)
- 판정 결과 영속화: EstimateValidation.complete(grade, summary, reviewCount, totalCount, now)

■ 판정 규칙
NEEDS_REVIEW  reviewItemCount        >= needs-review-item-count            (현재 3)
           OR 개별 항목 최대 초과배수 >= severe-over-p75-multiplier          (현재 1.5)
           OR 총액 차이 비율          >= needs-review-total-difference-ratio (현재 0.20)
CAUTION       reviewItemCount        >  0
           OR 총액 차이 비율          >= caution-total-difference-ratio      (현재 0.10)
APPROPRIATE   위 어느 조건에도 걸리지 않음

- NEEDS_REVIEW 를 먼저 평가하며 세 조건 중 하나만 걸려도 그 등급입니다.
- 세 조건이 서로 다른 종류의 이상을 잡습니다 — 개수(넓게 퍼진 이상) · 개별 배수(한
  항목의 심한 이상) · 총액 비율(전체 규모). AND 로 묶으면 각각을 놓치므로 OR 입니다.
- 경계는 항목 수 하한(> 0)만 초과이고 나머지 넷은 전부 이상(>=) 입니다.
- 총액 차이 비율 = |claimedTotal - aiTotalMedian| / aiTotalMedian, 절대값입니다.
- estimateId 가 없거나 aiTotalMedian 이 없으면 비율이 0 으로 고정되어, 등급이 확인 권장
  항목 수와 개별 초과배수만으로 결정됩니다.
- 판정 문구는 "확인 권장" 표현만 씁니다. 단정적 부당청구 표현을 쓰지 않습니다.

■ 변경 파일 (전부 d84c6d5 에 포함)
estimatevalidation/domain/GradeDecider.java
estimatevalidation/domain/GradePolicy.java
estimatevalidation/domain/GradeAssessment.java
estimatevalidation/domain/ValidationGrade.java
estimatevalidation/service/EstimateValidationService.java
estimatevalidation/entity/EstimateValidation.java

■ 테스트
GradeDeciderTest — 스프링 컨텍스트 없이 익명 GradePolicy 로 경계를 고정합니다.
  totalDifferenceBoundariesAreInclusive   0.0999→적정 0.10→주의 0.1999→주의 0.20→확인필요
  severeReferenceRatioBoundaryIsInclusive 1.4999→주의 1.50→확인필요
  reviewCountBoundaryIsInclusive          2건→주의 3건→확인필요
실행: .\gradlew.bat --offline test --tests "...domain.GradeDeciderTest" → BUILD SUCCESSFUL

■ 미검증 범위
- summary 는 현재 LLM 이 아니라 서버 템플릿 문장입니다. SummaryGenerationPort 어댑터가
  없고 llm_model 컬럼은 항상 null 입니다.
- FAILED 상태를 만드는 프로덕션 코드가 없어 실패 경로가 도달 불가능합니다.
- PostgreSQL 실기동 검증은 이 작업에서 수행하지 않았습니다 (문서 전용 작업).

■ 확인·결정 사항
- 임계값 6개는 전부 잠정치입니다. 실데이터 캘리브레이션이 필요합니다.
- 판독 한계 플래그(UNMAPPED_ITEM·INSUFFICIENT_REFERENCE)도 reviewItemCount 에 포함되어
  등급을 올립니다. 의도인지 확인이 필요합니다.
- CurrentMemberProvider 가 스텁이라 지금은 로그인해도 이 API 가 500 입니다 (별도 이슈).

■ 형제 Task 경계
315 는 이 판정에 쓰이는 임계값의 외부화, 316 은 판정 결과를 담아 내보내는 조회 API 입니다.
판정 규칙 자체와 요약문 생성이 이 이슈입니다.
```

---

### 8-3. S15P21A307-315 `[BE] 3단계 등급(적정 범위·주의·확인 필요) 판정 설정값 외부화`

```text
[유형] 작업
[개발 범위] BE (기능 구현)
[스토리 포인트] 2
[상위 Epic] [BE] 견적서 입력 기능 구현 (S15P21A307-269)
[구현 우선순위] MVP 필수 기능 (중요도 '상')
[요구사항 위치] 견적 검증 > 견적서 입력 > 검증 결과
[진행 상태] 구현 완료 · develop 머지됨 · 결함 1건 보고 (아래 확인·결정 사항)
[테스트] EstimateValidationPropertiesTest 3개 통과 — 바인딩·교차제약·누락 실패

■ 구현 내용
구현 코드는 d84c6d5 (S15P21A307-310 MR) 에 함께 머지되었습니다.

EstimateValidationProperties 를 record + @Validated +
@ConfigurationProperties(prefix = "app.estimate-validation") 로 만들고 GradePolicy
인터페이스를 구현하게 했습니다. 등록은 BackendApplication 의
@ConfigurationPropertiesScan 이 담당합니다.

설계 의도 — 판정 도메인(GradeDecider)이 스프링 설정 타입에 의존하지 않도록 GradePolicy
인터페이스로 끊었습니다. 그래서 GradeDeciderTest 가 스프링 컨텍스트 없이 익명 구현으로
경계값을 검증할 수 있습니다.

■ 설정값 (application.properties 89~94행)
프로퍼티                                제약                             현재  올리면
reference-percentile                  @Min(1) @Max(100)                 75   변화 없음(아래)
severe-over-p75-multiplier            @DecimalMin("1.0", exclusive)     1.5  판정 완화
caution-total-difference-ratio        @DecimalMin("0.0")                0.10 판정 완화
needs-review-total-difference-ratio   @DecimalMin("0.0")                0.20 판정 완화
needs-review-item-count               @Min(1)                           3    판정 완화
presigned-url-minutes                 @Min(1) @Max(1440)                10   링크 유효시간 증가

테스트 환경(src/test/resources/application.properties 46~51행)에도 같은 값이 있고,
main 과 어긋나면 EstimateValidationPropertiesTest 가 잡습니다.

교차 제약
@AssertTrue("needs-review-total-difference-ratio must be at least
             caution-total-difference-ratio")
두 값을 거꾸로 넣으면 기동 시점에 실패합니다. 실수를 런타임까지 끌고 가지 않는 장치입니다.

기본값을 두지 않은 것이 의도입니다. @DefaultValue 가 없어 프로퍼티가 하나라도 빠지면
기동이 실패합니다 — 근거 없는 기본값으로 사용자에게 확인을 권하지 않겠다는 정책입니다.

조정 절차
1. application.properties 수정 → 2. 재기동(핫 리로드 없음)
3. 배포 환경에서는 환경변수·외부 설정으로 덮을 수 있습니다
   예: APP_ESTIMATEVALIDATION_NEEDSREVIEWITEMCOUNT=4

현재 값은 전부 잠정치입니다. 요구사항에 "예: 60%" 형태로 적힌 값이라 실데이터를 보고
조정해야 하며, 프로퍼티 주석도 그렇게 적어 두었습니다
("conservative product assumptions until production calibration data is approved").

■ 변경 파일 (전부 d84c6d5 에 포함)
estimatevalidation/config/EstimateValidationProperties.java
estimatevalidation/domain/GradePolicy.java
backend/src/main/resources/application.properties (89~94행)
backend/src/test/resources/application.properties (46~51행)

■ 테스트
EstimateValidationPropertiesTest — ApplicationContextRunner 기반 3개
- bindsExternalizedThresholds : 6개 값이 정확히 바인딩되는지
- rejectsInvalidAndReversedThresholdsAtStartup :
    reference-percentile=101 / severe-over-p75-multiplier=1.0 / 역전된 비율 → 컨텍스트 실패
- rejectsMissingDecimalThresholds : 값 누락 시 컨텍스트 실패
실행: .\gradlew.bat --offline test --tests "...config.EstimateValidationPropertiesTest"
      → BUILD SUCCESSFUL

■ 미검증 범위
- 실데이터 캘리브레이션 미수행. 현재 값의 적정성은 확인되지 않았습니다.
- PostgreSQL 실기동 검증은 이 작업에서 수행하지 않았습니다.

■ 확인·결정 사항 (중요)
reference-percentile 이 어떤 프로덕션 코드에서도 읽히지 않습니다.
- grep 결과 선언 한 줄(EstimateValidationProperties.java:17) 외에 사용처가 0곳입니다.
- GradePolicy 인터페이스에도 이 값이 없습니다 — 판정에 쓰지 않기로 한 흔적입니다.
- 비교 기준은 repair_cost_stat.cost_p75 로 하드코딩되어 있습니다
  (EstimateValidationEngine:28, EstimateValidationService:303-306).
- 재현: 값을 90 으로 바꾸고 재기동해도 같은 견적서의 판정이 완전히 동일합니다.
- severe-over-p75-multiplier 라는 이름 자체가 P75 를 못박고 있어, 분위를 가변으로
  만들려면 이름도 함께 바꿔야 합니다.
선택지
  A) 프로퍼티를 제거한다 — 쓰지 않는 설정은 거짓말이다 (비용 작음, 권장)
  B) 실제로 쓰이게 만든다 — repair_cost_stat 에 cost_median·cost_p75 가 이미 있으므로
     분위 선택을 코드로 뺀다 (CostReference·GradePolicy·엔진 시그니처 변경 필요)
이 이슈 제목이 "코드 수정 없이 조정 가능하도록 구성" 이므로, 조정해도 아무 일이 없는
프로퍼티가 남아 있는 것은 제목과 어긋납니다. 별도 Task 로 분리해 결정하기를 제안합니다.

■ 형제 Task 경계
판정 규칙 자체는 314, 결과 조회 API 는 316 입니다. 이 이슈는 임계값의 외부화와
기동 시점 검증까지입니다.
```

---

### 8-4. S15P21A307-316 `[BE] 총액 차이·확인 필요 항목 수 산출 및 검증 결과 저장·조회 API 구현`

```text
[유형] 작업
[개발 범위] BE (기능 구현)
[스토리 포인트] 2
[상위 Epic] [BE] 견적서 입력 기능 구현 (S15P21A307-269)
[구현 우선순위] MVP 필수 기능 (중요도 '상')
[요구사항 위치] 견적 검증 > 견적서 입력 > 검증 결과
[원 요구사항 명세서 행] API 명세서 25행 (검증 결과 조회)
[진행 상태] 구현 완료 · develop 머지됨 · 테스트 보강 제안 있음
[테스트] EstimateValidationServiceTest 9개 · EstimateValidationControllerTest 6개 통과

■ 구현 내용
구현 코드는 d84c6d5 (S15P21A307-310 MR) 에 함께 머지되었습니다.

- GET /api/estimate-validations/{validationId}/result → ValidationResultResponse
- 확인 권장 항목 수·전체 항목 수를 판정 시점에 산출해 저장
  (estimate_validation.review_item_count · total_item_count)
- 총액 차이 2종을 조회 시점에 계산해 응답에 포함
- 항목별 비교표(items[])와 정비소 확인 질문(questions[])을 같은 응답에 포함
- COMPLETED 가 아니면 409 CONFLICT — 빈 결과를 200 으로 내보내지 않습니다

■ 응답 계약 — 저장값과 계산값의 비대칭이 핵심입니다
저장 (DB 컬럼 그대로)
  claimedTotal · reviewItemCount · totalItemCount · grade · summary · status
  createdAt · completedAt · items[] · questions[]
계산 (조회할 때마다 다시 구함)
  differenceFromMedian             = claimedTotal - aiTotalMedian
  differenceFromRangeMax           = claimedTotal - aiTotalMax
  shopEstimateDifferenceFromActual = claimedTotal - actualRepairCost
  aiMedianDifferenceFromActual     = aiTotalMedian - actualRepairCost
  actualWithinAiRange              = aiTotalMin <= actual <= aiTotalMax
상수
  gradeDisplayName (ValidationGrade enum) · legalNotice (LEGAL_NOTICE)

총액 차이는 DB 컬럼이 아닙니다. estimate_validation 에 total_diff 류 컬럼이 없습니다.
누락이 아니라 의도된 설계입니다 — AI 견적이 재산정되면 차이도 따라 바뀌어야 하므로
저장하면 낡은 값이 남습니다. 반대로 항목 수는 판정 시점의 사실이라 컬럼으로 굳혔습니다.

두 총액 차이의 용도
  differenceFromMedian   중앙값 대비. summary 문장과 등급의 총액 차이 비율이 모두
                         중앙값 기준이므로 화면 헤드라인은 이 값을 씁니다.
  differenceFromRangeMax 범위 상한 대비. "범위를 벗어났는가" 판정용 보조 값입니다.
                         0 이하면 AI 예상 범위 안입니다.
둘 다 음수가 될 수 있습니다 (견적이 AI 예상보다 낮은 경우).

실제 수리비 관련 6개 필드는 PUT /api/accidents/{accidentId}/actual-cost 입력 전에는
전부 null 입니다. 이 엔드포인트는 /api/estimate-validations 아래가 아니라
/api/accidents 아래(AccidentController)입니다.

items[].displayDecision 은 서버가 flag == null ? "범위 내" : "확인 권장" 으로 만들어
내려줍니다. FE 가 한글을 하드코딩하지 않습니다. 한 항목에 플래그가 여럿 붙어도
questionOrder 가 가장 낮은 하나만 저장·응답됩니다.

소유권 검사는 Repository 쿼리 조건에 있습니다 (findByValidationIdAndMemberId 등).
남의 검증과 없는 검증이 서비스에서 구별되지 않으므로 둘 다 404 NOT_FOUND 이며
403 을 쓰지 않습니다. 리소스 존재 여부를 노출하지 않기 위한 선택입니다.

■ 변경 파일 (전부 d84c6d5 에 포함)
estimatevalidation/controller/EstimateValidationController.java
estimatevalidation/dto/ValidationResultResponse.java
estimatevalidation/dto/ValidationStatusResponse.java
estimatevalidation/dto/ValidationHistoryResponse.java
estimatevalidation/service/EstimateValidationService.java
estimatevalidation/entity/EstimateValidation.java
Docs/Erd/A307_ddl_final.sql (review_item_count · total_item_count)

■ 테스트
EstimateValidationServiceTest 9개 · EstimateValidationControllerTest 6개 전부 통과.
전체 스위트 204/204 통과.

■ 미검증 범위 — 테스트 보강 제안
계산 필드 5개 중 differenceFromMedian 하나만 단언되어 있습니다
(EstimateValidationServiceTest:84  assertThat(result.differenceFromMedian()).isEqualTo(1L)).
단언 없음: differenceFromRangeMax · shopEstimateDifferenceFromActual ·
          aiMedianDifferenceFromActual · actualWithinAiRange
널 전파(estimateId 없음 · 실제 수리비 미입력)와 음수 케이스,
actualWithinAiRange 의 경계(actual == aiTotalMin, actual == aiTotalMax)를
덮는 케이스를 추가하는 것이 좋습니다. 이번 문서 작업에서는 추가하지 않았습니다.

그 밖에
- 파일 업로드 경로는 DocumentStoragePort 구현체가 없어 503 입니다. 결과 조회는 직접
  입력으로 만든 검증 건에서만 끝까지 동작합니다.
- PDF 다운로드는 PdfGenerationPort 어댑터가 없어 리포트가 QUEUED 에 머물러 항상 409 입니다.
- CurrentMemberProvider 스텁 때문에 지금은 로그인해도 500 입니다 (별도 이슈).

■ 확인·결정 사항
- 화면 헤드라인에 differenceFromMedian 과 differenceFromRangeMax 중 무엇을 쓸지.
  와이어프레임 "정비소 견적서 검증 결과.png" 의 카드가 라벨("AI 예상 범위와의 차이")과
  숫자(+62,000원)가 어긋납니다. 622,000 - 610,000 = 12,000 이라 범위 상한 기준으로는
  나올 수 없는 숫자이고 중앙값 기준(560,000)과만 맞습니다.
  문서에서는 summary·등급 판정과의 일관성을 근거로 중앙값을 권하고, 카드 라벨을
  "AI 예상 중앙값과의 차이" 로 바꾸자고 제안했습니다. 기획 확정이 필요합니다.
- 와이어프레임 상단 배너 문안이 서버 legalNotice 와 다릅니다. 명세서 23행이 PDF 에
  "특정 사업자를 평가하지 않습니다" 를 필수로 요구하므로 서버 값을 쓰는 것이 맞습니다.

■ 형제 Task 경계
판정 규칙은 314, 임계값 외부화는 315 입니다. 이 이슈는 판정 결과의 산출·저장과
조회 API 계약입니다.
```

---

## 9. 금지 사항 준수 확인

| prompt28 §8 금지 | 준수 |
|---|:---:|
| 사용자에게 질문하지 않는다 | ✅ 0회. 판단은 전부 2장에 기록 |
| `backend/src/**` 수정 | ✅ 0건 |
| 테스트 추가·수정 | ✅ 0건. 부족한 부분은 6장 D6 에 보고만 |
| DDL·`schema-h2.sql`·`build.gradle`·`application.properties` 수정 | ✅ 0건 |
| Atlassian/Jira MCP 호출 | ✅ 0회. 초안만 8장 |
| 메인 워크트리 미커밋 변경 되돌리기·커밋 포함 | ✅ 남의 변경 0건 (내 이전 시도 잔여물만 — 2장 판단 6) |
| `reset`·`clean`·`stash`·`rebase`·`push --force` | ✅ 0회 |
| `git clean -x` | ✅ 0회. `_recovery_20260907/` 무사 |
| develop·master 직접 커밋·푸시 | ✅ 없음. `feature/S15P21A307-314-...` 로만 푸시 |
| 머지된 커밋 메시지 수정 | ✅ 시도하지 않음 |
| `docs/S15P21A307-314-validation-docs` 브랜치 사용 | ✅ 사용하지 않음. 해당 워크트리도 건드리지 않음 |
| prompt29 범위(정비소 확인 질문 로직) 문서화 | ✅ 하지 않음. 목록 한 줄 + 계약 요약만 |
| 단정적 부당청구 표현 | ✅ "확인 권장" 정책 준수 |
| 임계값을 확정 기준처럼 표기 | ✅ 두 문서 모두에 잠정치 명시 |
| 확인하지 않은 값·필드명 기재 | ✅ 전부 워크트리 코드 실측 |
| DB 자격증명·카카오 키·개인정보 | ✅ 없음 |
