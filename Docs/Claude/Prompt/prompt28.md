# prompt28 — 검증 요약·등급 계약 문서화와 Jira 연결 (314·315·316)

> 대상 이슈
> - `S15P21A307-314 [BE] 검증 요약·등급 기능 구현` — 스토리
> - `S15P21A307-315 [BE] 3단계 등급(적정 범위·주의·확인 필요) 판정 설정값 외부화` — 작업 · 2pt
> - `S15P21A307-316 [BE] 총액 차이·확인 필요 항목 수 산출 및 검증 결과 저장·조회 API 구현` — 작업 · 2pt
>
> 상위 Epic `[BE] 견적서 입력 기능 구현` (S15P21A307-269) · 담당 재원 · MVP 필수 · 중요도 상
> 요구사항 위치: 견적 검증 > 견적서 입력 > 검증 결과
> 작업 브랜치: **`feature/S15P21A307-314-validation-summary-grade`** (이미 존재 · origin에도 있음)
> 기준 저장소: `C:\SSAFY\S15P21A307`
>
> 짝 문서: `prompt29.md` (317·318 정비소 확인 질문). 같은 방식으로 별도 브랜치·별도 MR로 처리한다.

---

## 1. 자율 실행 원칙 — 사용자에게 묻지 않는다

**이 문서는 푸시까지 스스로 실행한다. 어떤 단계에서도 사용자에게 질문하거나 승인을 요청하지 않는다.**

- 판단이 갈리면 **아래 결정 규칙으로 스스로 정하고**, 무엇을 왜 그렇게 정했는지 `answer28.md` 2장에 전부 기록한다
- "확인이 필요합니다", "어느 쪽으로 하시겠어요" 같은 문장으로 작업을 멈추지 않는다
- 확정할 수 없는 항목은 **`미확정`으로 표기하고 그대로 진행**한다. 그것 때문에 다른 작업을 중단하지 않는다
- 중간 보고를 하지 않는다. 3~9장을 모두 끝낸 뒤 결과만 낸다

### 결정 규칙 — 이 순서로 판단한다

1. 요구사항 명세 시트 원문
2. `Docs/Erd/A307_ddl_final.sql` (DDL 제약)
3. **코드의 현재 동작** — 이미 develop에 머지된 것이 사실상의 계약이다
4. `Docs/Api` 기존 문서의 관례와 어투
5. 위로 정해지지 않으면 **가장 보수적인 선택** = 아무것도 바꾸지 않고 현재 동작을 그대로 문서화한다

### 코드를 고치고 싶어지면

**고치지 않는다.** 결함·개선점은 `answer28.md` 6장에 근거와 재현 조건을 적고 후속 이슈 제안으로 남긴다. 이 브랜치는 문서 전용이어야 MR 리뷰가 짧다.

---

## 2. 현재 상태 — 실측

**세 이슈의 구현 코드는 이미 `origin/develop`에 있다.** `d84c6d5 Feat: 견적 검증 이상 항목 탐지 및 결과 저장 구현 (S15P21A307-310)` 커밋에 310뿐 아니라 314·315·316 산출물까지 함께 머지되었다.

```text
origin/develop 에 존재
  config/EstimateValidationProperties.java  + test/config/EstimateValidationPropertiesTest.java
  domain/GradeDecider.java · GradePolicy.java · GradeAssessment.java · ValidationGrade.java
  test/domain/GradeDeciderTest.java
  dto/ValidationResultResponse.java
  GET /api/estimate-validations/{validationId}/result
  DDL estimate_validation : llm_grade · review_item_count · total_item_count · ck_ev_grade
```

Jira 상태는 셋 다 `해야 할 일`이고 개발 패널에 커밋·MR 링크가 없다. 커밋 메시지에 314·315·316 키가 없기 때문이며, 이미 develop에 푸시된 히스토리라 메시지를 고칠 수 없다. **새 커밋과 MR이 그 링크를 만든다.**

### 2-1. 브랜치가 develop보다 뒤처져 있다

```text
origin/feature/S15P21A307-314-validation-summary-grade  = 707cd7a
origin/develop 대비                                      22커밋 뒤처짐 · 자기 커밋 0개
```

브랜치명에 `S15P21A307-314`가 들어 있어 Jira 연결은 되지만, **그 시점 코드로 분석하면 develop 최신과 다를 수 있다.** 3장에서 develop을 머지해 최신화한 뒤 분석한다.

### 2-2. 정리할 워크트리가 남아 있다

```text
C:/SSAFY/S15P21A307-p22                              codex/prompt22                    prunable
C:/SSAFY/S15P21A307/.claude/worktrees/prompt28-docs  docs/S15P21A307-314-validation-docs prunable
```

둘 다 `prunable` — 디렉터리가 없거나 등록만 남은 상태다. 3장에서 정리한다. `docs/S15P21A307-314-validation-docs` 는 이 작업의 이전 시도로 만들어진 브랜치이며, **이 작업은 그 브랜치를 쓰지 않는다.**

착수 시 위 내용을 직접 재확인한다. 다르면 실제 상태를 기준으로 진행하고 차이를 기록한다.

---

## 3. 작업 공간 — 워크트리로 격리한다

메인 워크트리(`C:\SSAFY\S15P21A307`)는 `feature/S15P21A307-149-...` 에 있고 미커밋 변경이 많다(수정 약 146 · 미추적 약 44). 브랜치를 갈아타면 충돌하거나 남의 작업을 끌고 들어간다.

```powershell
cd C:\SSAFY\S15P21A307

# 죽은 워크트리 등록 정리
git worktree prune
git worktree list

git fetch origin

# 기존 314 브랜치를 별도 폴더에 체크아웃
git worktree add C:\SSAFY\S15P21A307-314 feature/S15P21A307-314-validation-summary-grade

cd C:\SSAFY\S15P21A307-314
git status -sb          # 깨끗해야 한다

# develop 최신화 — 분석 대상 코드를 최신으로 맞춘다
git merge origin/develop
git log --oneline -1
git rev-list --left-right --count origin/develop...HEAD   # 왼쪽이 0 이어야 한다
```

`git merge origin/develop` 에서 충돌이 나면 — 이 브랜치는 자기 커밋이 0개이므로 **충돌이 날 수 없다.** 충돌이 난다면 예상과 다른 상태이므로 병합을 중단(`git merge --abort`)하고, `origin/develop` 기준의 새 브랜치를 만들어 진행한다.

```powershell
# 위 머지가 실패한 경우에만
git merge --abort
cd C:\SSAFY\S15P21A307
git worktree remove C:\SSAFY\S15P21A307-314
git worktree add C:\SSAFY\S15P21A307-314 -b feature/S15P21A307-315-validation-thresholds-doc origin/develop
```

이 대체 브랜치명에는 `S15P21A307-315`가 들어가고, 커밋 메시지에 314·316을 넣으면 세 이슈 모두 연결된다. 어느 경로를 택했는지 `answer28.md` 2장에 기록한다.

`git worktree add` 가 이름 충돌로 실패하면 기존 것을 확인해 이어 쓰고, 불가능하면 사유를 기록한 뒤 **메인 워크트리에서 `git checkout` 없이** 진행할 수 없으므로 그때는 작업을 중단하고 `answer28.md` 에 사유만 남긴다.

작업이 끝나면 워크트리를 정리한다 — 9-5에 있다.

---

## 4. 분석·문서화할 내용 — 실측 기준

아래는 작성 시점 실측이다. **문서에 쓰는 모든 수치·필드명·규칙은 워크트리의 코드에서 직접 읽어 확인한다.** 이 문서에 적힌 값도 검증 대상이며, 다르면 코드가 정본이고 차이를 `answer28.md` 1장에 적는다.

읽어야 할 파일:

```text
backend/src/main/java/com/ssafy/a307/estimatevalidation/config/EstimateValidationProperties.java
backend/src/main/java/com/ssafy/a307/estimatevalidation/domain/GradeDecider.java
backend/src/main/java/com/ssafy/a307/estimatevalidation/domain/GradePolicy.java
backend/src/main/java/com/ssafy/a307/estimatevalidation/domain/GradeAssessment.java
backend/src/main/java/com/ssafy/a307/estimatevalidation/domain/ValidationGrade.java
backend/src/main/java/com/ssafy/a307/estimatevalidation/dto/ValidationResultResponse.java
backend/src/main/java/com/ssafy/a307/estimatevalidation/dto/ValidationStatusResponse.java
backend/src/main/java/com/ssafy/a307/estimatevalidation/dto/ValidationHistoryResponse.java
backend/src/main/java/com/ssafy/a307/estimatevalidation/controller/EstimateValidationController.java
backend/src/main/java/com/ssafy/a307/estimatevalidation/service/EstimateValidationService.java
backend/src/main/resources/application.properties            # app.estimate-validation.*
backend/src/test/java/com/ssafy/a307/estimatevalidation/domain/GradeDeciderTest.java
backend/src/test/java/com/ssafy/a307/estimatevalidation/config/EstimateValidationPropertiesTest.java
Docs/Erd/A307_ddl_final.sql                                  # estimate_validation
Docs/Api/API 명세서 (바른견적 최신).md
Docs/Api/차량 API — FE 인수인계.md                            # 문서 형식의 본보기
Docs/Api/이미지 업로드 API — FE 인수인계.md                    # 문서 형식의 본보기
Docs/Wireframe/정비소 견적서 검증 결과.png
```

### 4-1. 판정 규칙 (314)

`GradeDecider.decide(assessment, policy)` 는 결정론적이며 평가 순서가 있다.

```text
NEEDS_REVIEW   reviewItemCount        >= needsReviewItemCount
            OR highestReferenceRatio  >= severeOverP75Multiplier
            OR totalDifferenceRatio   >= needsReviewTotalDifferenceRatio

CAUTION        reviewItemCount        >  0
            OR totalDifferenceRatio   >= cautionTotalDifferenceRatio

APPROPRIATE    위 어느 조건에도 걸리지 않음
```

세 조건 중 하나만 걸려도 `NEEDS_REVIEW` 이고, `NEEDS_REVIEW` 가 `CAUTION` 보다 먼저 평가된다.

```text
ValidationGrade   APPROPRIATE  → "적정 범위"
                  CAUTION      → "주의"
                  NEEDS_REVIEW → "확인 필요"
```

DDL 제약이 같은 값을 강제한다.

```sql
CONSTRAINT ck_ev_grade CHECK (llm_grade IS NULL
                        OR llm_grade IN ('APPROPRIATE','CAUTION','NEEDS_REVIEW'))
```

**문서에 담을 것**: 규칙 표, 각 조건의 의미, 왜 OR 인지, 경계값(`>=` vs `>`)이 어디에 쓰이는지. `reviewItemCount > 0` 이 `CAUTION` 의 하한이라는 사실은 "확인 권장 항목이 하나라도 있으면 적정 범위가 아니다"는 정책 문장으로 옮긴다.

`GradeAssessment` 의 세 입력값(`reviewItemCount`·`highestReferenceRatio`·`totalDifferenceRatio`)이 각각 어디서 계산되는지 `EstimateValidationService` 에서 확인해 함께 적는다.

### 4-2. 설정값 (315)

`EstimateValidationProperties` 는 `record` + `@Validated` + `@ConfigurationProperties(prefix = "app.estimate-validation")` 이며 **`GradePolicy` 인터페이스를 구현한다.** 도메인(`GradeDecider`)이 스프링 설정 타입에 의존하지 않게 분리한 구조다. 이 설계 의도를 문서에 남긴다.

| 프로퍼티 | 제약 | 현재 값 | 의미 |
|---|---|---|---|
| `reference-percentile` | `@Min(1) @Max(100)` | 75 | 사례 통계에서 비교 기준으로 쓰는 분위 |
| `severe-over-p75-multiplier` | `@DecimalMin("1.0", inclusive=false)` | 1.5 | 기준 분위의 몇 배를 넘으면 즉시 확인 필요인지 |
| `caution-total-difference-ratio` | `@DecimalMin("0.0")` | 0.10 | 총액 차이 비율이 이 값 이상이면 주의 |
| `needs-review-total-difference-ratio` | `@DecimalMin("0.0")` | 0.20 | 총액 차이 비율이 이 값 이상이면 확인 필요 |
| `needs-review-item-count` | `@Min(1)` | 3 | 확인 권장 항목이 몇 개 이상이면 확인 필요 |
| `presigned-url-minutes` | `@Min(1) @Max(1440)` | 10 | 검증 PDF 다운로드 URL 유효시간(분) |

교차 제약:

```java
@AssertTrue(message = "needs-review-total-difference-ratio must be at least caution-total-difference-ratio")
public boolean isDifferenceRatioOrderValid()
```

`needsReview >= caution` 이 아니면 **기동 시점에 실패한다.** 두 값을 거꾸로 넣는 실수를 런타임까지 끌고 가지 않는 장치다.

**문서에 담을 것**:

- 표 그대로 + 각 값을 올리면 등급 판정이 완화되는지 강화되는지
- `application.properties` 의 해당 줄 번호
- 값이 누락되면 기동이 실패한다는 사실. `@DefaultValue` 를 두지 않은 것이 의도다 — 근거 없는 기본값으로 사용자에게 확인을 권하지 않는다는 정책
- 교차 제약과 그 오류 메시지
- **현재 값은 전부 잠정치다.** 요구사항에 "예: 60%" 형태로 적힌 값이라 실제 데이터를 보고 조정해야 한다
- 조정 절차: `application.properties` 수정 → 재기동. 배포 환경에서는 환경변수·외부 설정으로 덮을 수 있다는 점
- 테스트 환경(`src/test/resources/application.properties`)에도 같은 값이 있고 main 과 어긋나면 `EstimateValidationPropertiesTest` 가 잡는다는 사실

### 4-3. 검증 결과 조회 계약 (316)

`GET /api/estimate-validations/{validationId}/result` → `ValidationResultResponse`

```text
validationId · accidentId · estimateId
grade · gradeDisplayName · summary
claimedTotal · aiTotalMin · aiTotalMedian · aiTotalMax
differenceFromMedian · differenceFromRangeMax
actualRepairCost · repairShopName
shopEstimateDifferenceFromActual · aiMedianDifferenceFromActual
legalNotice
items[] : rawItemName · normalizedName · partCode · workType · partCost · laborCost · …
```

**총액 차이는 DB 컬럼이 아니다.** `estimate_validation` 에는 `claimed_total`·`review_item_count`·`total_item_count` 만 있고 `total_diff` 류 컬럼이 없다. 차이는 조회 시점에 계산해 응답으로만 내려간다.

이것을 누락이 아니라 **의도된 설계**로 문서화한다 — AI 견적이 재산정되면 차이도 따라 바뀌어야 하므로, 저장하면 낡은 값이 남는다.

**문서에 담을 것**:

- 필드 전체 표와 각 필드의 출처(DB 컬럼 / 조회 시 계산 / 상수)
- `differenceFromMedian` 과 `differenceFromRangeMax` 의 차이와 **FE 가 무엇을 기본으로 표시해야 하는지.** 코드와 와이어프레임(`정비소 견적서 검증 결과.png`)을 근거로 **스스로 판정한다.** 판정 근거가 약하면 권장값을 정하고 근거의 강도를 함께 적는다 — 미확정으로 비워 두지 않는다
- `review_item_count`·`total_item_count` 는 저장값이고 총액 차이는 계산값이라는 비대칭
- `legalNotice` 가 무엇이고 왜 응답에 있는지
- 실제 수리비 관련 필드는 `PUT .../actual-cost` 입력 후에만 채워진다는 사실
- 소유권 검사가 Repository 쿼리 조건에 있는지, 남의 검증이 404인지
- 판정 문구는 **"확인 권장" 표현만** 쓴다는 정책

### 4-4. API 명세서 누락 확인

`GET /api/estimate-validations/{validationId}/questions` 가 명세서 md·csv 목록에 **없다.** 구현되어 있으므로 두 문서에 행을 추가하고, 누락이었음을 `answer28.md` 3장에 적는다. 차수·도메인·담당·인증 컬럼은 같은 도메인의 다른 행과 맞춘다.

> 이 엔드포인트의 기능 자체(정비소 확인 질문)는 `prompt29.md` 가 다룬다. 여기서는 **명세서 행 추가만** 한다. 질문 생성 로직을 분석하거나 문서화하지 않는다.

---

## 5. 산출물 1 — `Docs/Api/견적서 검증 API — FE 인수인계.md`

`Docs/Api/차량 API — FE 인수인계.md` 와 `이미지 업로드 API — FE 인수인계.md` 의 형식·어투를 먼저 읽고 맞춘다. 파일이 없으면 신규 작성한다.

담을 내용:

```text
■ 엔드포인트 목록
  POST   /api/estimate-validations               견적서 업로드 (파일 / 직접 입력 2가지)
  GET    /api/estimate-validations/{id}          검증 상태 조회
  GET    /api/estimate-validations/{id}/result   검증 결과 조회
  GET    /api/estimate-validations/{id}/questions 정비소 확인 질문 조회
  GET    /api/estimate-validations/{id}/pdf      검증 PDF 다운로드
  GET    /api/estimate-validations/me            검증 이력 목록
  DELETE /api/estimate-validations/{id}          검증 삭제
  전부 인증 필수

■ 상태 흐름
  QUEUED → PROCESSING → COMPLETED | FAILED
  ck_ev_done : completed_at 은 COMPLETED·FAILED 에서만 채워진다
  FE 폴링 방법

■ 등급 3단계와 화면 표시
  APPROPRIATE 적정 범위 / CAUTION 주의 / NEEDS_REVIEW 확인 필요
  gradeDisplayName 을 그대로 쓰면 FE 가 한글을 하드코딩하지 않아도 된다
  판정 규칙 표 (4-1)

■ 결과 조회 응답 계약
  필드 전체와 출처 (4-3)
  총액 차이 두 필드의 용도와 FE 기본 표시값
  실제 수리비 입력 전에는 null 인 필드 목록

■ 판정 임계값과 조정 방법
  프로퍼티 6개 표 (4-2)
  잠정치라는 사실과 조정 절차

■ 항목별 판정
  items[] 의 flag 유형과 각 유형의 화면 문구 방향
  "확인 권장" 표현만 쓴다는 정책

■ 오류 계약
  ErrorResponse { "error": { "code", "message" } }
  소유자 아님·없음 → 404 (403 아님) · 비로그인 401
  파일 검증 실패 유형별 코드

■ PDF 다운로드
  presigned URL 유효시간 = app.estimate-validation.presigned-url-minutes (기본 10분)
  만료 시 재요청 방법

■ 미구현·주의
  OCR·LLM·PDF·S3 공급자가 포트만 있고 어댑터가 없는 범위
  그 상태에서 어떤 응답이 오는지
```

**추측으로 쓰지 않는다.** 각 항목을 코드에서 확인하고, 없는 필드를 있는 것처럼 쓰지 않는다.

> `prompt29.md` 도 같은 파일에 정비소 확인 질문 절을 추가한다. **이 작업에서는 질문 절을 쓰지 않고 엔드포인트 목록에만 한 줄 넣는다.** 두 MR이 같은 절을 건드리면 충돌한다.

---

## 6. 산출물 2 — 명세서 갱신

```text
Docs/Api/API 명세서 (바른견적 최신).md
Docs/Api/A307 백엔드 API 명세서 (MVP SUB 구분으로 봐주세요).csv
```

**먼저 diff 를 읽고 필요한 문장만** 편집한다. 문서를 재작성하지 않는다.

- 등급 판정 규칙과 3단계 값 (4-1)
- `app.estimate-validation.*` 6개 프로퍼티 표와 조정 방법 (4-2)
- 총액 차이가 저장이 아니라 계산이라는 사실 (4-3)
- 결과 조회 응답 필드 표
- `GET .../questions` 행 추가 (4-4)

---

## 7. 검증 — 실행 증거를 남긴다

**문서만 있고 "실제로 통과한다"는 증거가 없으면 리뷰에서 가장 먼저 지적된다.** 아래를 실행하고 출력을 `answer28.md` 5장에 그대로 붙인다.

```powershell
cd C:\SSAFY\S15P21A307-314\backend

.\gradlew.bat --offline test --tests "com.ssafy.a307.estimatevalidation.domain.GradeDeciderTest"
.\gradlew.bat --offline test --tests "com.ssafy.a307.estimatevalidation.config.EstimateValidationPropertiesTest"
.\gradlew.bat --offline test --tests "com.ssafy.a307.estimatevalidation.*"
.\gradlew.bat --offline test
```

- `--offline` 로 캐시 누락 오류가 나면 `--offline` 을 빼고 **한 번만** 더 시도한다
- 두 번 실패하면 중단하고 원문 오류를 기록한다. Gradle 배포본 다운로드 실패는 환경 문제이며 이 작업의 결과를 좌우하지 않는다
- 전체 스위트의 기준선은 **393개 통과 / 실패 0** 이다. 이보다 줄어들면 원인을 확인해 기록한다
- **테스트를 통과시키기 위해 코드나 테스트를 수정하지 않는다**

문서 자체 검증:

```powershell
cd C:\SSAFY\S15P21A307-314
git diff --check
git status --short
```

---

## 8. 금지 사항

- **사용자에게 질문하지 않는다** (1장)
- `backend/src/**` 를 수정하지 않는다. 결함은 보고만 한다
- 테스트를 추가·수정하지 않는다
- `Docs/Erd/A307_ddl_final.sql`, `schema-h2.sql`, `build.gradle`, `application.properties` 를 수정하지 않는다
- Atlassian/Jira MCP 도구를 호출하지 않는다. 상태 전환·코멘트 등록은 사람이 한다
- 메인 워크트리(`C:\SSAFY\S15P21A307`)의 미커밋 변경을 되돌리거나 커밋에 포함하지 않는다
- `git reset`, `git clean`, `git stash`, `git rebase`, `git push --force` 를 사용하지 않는다
- `git clean -x` 계열을 절대 쓰지 않는다. `_recovery_20260907/` 이 지워진다
- develop·master 에 직접 커밋하거나 푸시하지 않는다
- 이미 머지된 커밋의 메시지를 고치려 하지 않는다
- `docs/S15P21A307-314-validation-docs` 브랜치를 쓰지 않는다 (이전 시도 잔여물)
- `prompt29.md` 가 다루는 정비소 확인 질문 로직을 분석·문서화하지 않는다 (4-4 · 5장 단서)
- 판정 문구에 "부당청구", "과다청구 확정" 같은 단정적 표현을 쓰지 않는다
- 임계값 현재 값을 확정된 기준처럼 쓰지 않는다. 잠정치임을 명시한다
- 코드에서 확인하지 않은 값·필드명을 문서에 쓰지 않는다
- DB 자격증명·카카오 키·개인정보를 문서에 쓰지 않는다

---

## 9. 커밋·푸시 — 실행한다

브랜치 최신화부터 푸시까지 **스스로 실행한다.** 사용자 승인을 기다리지 않는다.

### 9-1. 스테이징 — 경로 명시

```powershell
cd C:\SSAFY\S15P21A307-314
git add "Docs/Api/견적서 검증 API — FE 인수인계.md"
git add "Docs/Api/API 명세서 (바른견적 최신).md"
git add "Docs/Api/A307 백엔드 API 명세서 (MVP SUB 구분으로 봐주세요).csv"
git add "Docs/Claude/Answer/answer28.md"
git add "Docs/Claude/Prompt/prompt28.md"

git status --short
git diff --cached --stat
```

`git add .` 을 쓰지 않는다. 스테이징 목록에 `backend/`, `.idea/`, `.claude/`, `_recovery_20260907/` 가 **없어야 한다.** 있으면 `git restore --staged <경로>` 로 빼고 다시 확인한다.

`Docs/Claude/` 디렉터리가 없으면 만들어 파일을 쓴다.

> `Docs/Claude/` 를 팀 저장소에 올릴지는 결정되지 않은 사안이다. **결정 규칙 5번에 따라 포함한다** — 이 MR의 목적이 분석 근거를 남기는 것이고, 보고서가 저장소에 없으면 리뷰어가 근거를 볼 수 없다. 이 판단을 `answer28.md` 2장에 기록한다.

### 9-2. 커밋 — 남은 두 키를 넣는다

```powershell
git commit -m "Docs: 검증 등급 판정 규칙·임계값·총액 차이 계약 문서화 (S15P21A307-315, S15P21A307-316)"
```

브랜치명이 이미 `S15P21A307-314` 를 담고 있으므로 **커밋 메시지에 315·316 을 넣어 세 이슈 모두 연결된다.**

3장의 대체 경로(`feature/S15P21A307-315-...`)를 택했다면 커밋 메시지에 314·316 을 넣는다.

### 9-3. 푸시

```powershell
git push -u origin feature/S15P21A307-314-validation-summary-grade
```

푸시가 인증·네트워크 문제로 실패하면 **재시도하지 않고** 사유를 `answer28.md` 7장에 기록한다. 커밋은 남아 있으므로 사용자가 나중에 푸시할 수 있다.

### 9-4. MR

`glab` CLI 가 설치되어 있으면 MR 을 생성한다. 없으면 **생성하지 않고** 본문을 `answer28.md` 7장에 그대로 붙인다. `glab` 설치를 시도하지 않는다.

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
- `Docs/Api/견적서 검증 API — FE 인수인계.md` — 등급·임계값·결과 조회 계약
- `Docs/Api/API 명세서 (바른견적 최신).md` · CSV — 판정 규칙, 임계값 6개, GET .../questions 행 추가
- `Docs/Claude/Answer/answer28.md` — 분석 보고서

## 코드 변경
없습니다. 문서 전용 MR 입니다.

## 테스트
(7장 실행 결과를 여기에 붙인다 — 통과 개수와 실행한 명령)

## 분석에서 발견한 사항
(answer28 6장의 항목을 여기에 나열)
```

### 9-5. 워크트리 정리

```powershell
cd C:\SSAFY\S15P21A307
git worktree remove C:\SSAFY\S15P21A307-314
git worktree prune
git worktree list
```

`remove` 가 미커밋 변경 때문에 거부하면 강제 삭제하지 않고, 남은 변경을 확인해 커밋하거나 사유를 기록한다. **브랜치는 남긴다.**

### 9-6. 상태 전환은 하지 않는다

MR 이 develop 에 머지된 뒤 사람이 314·315·316 을 완료로 옮긴다. 근거와 제안은 `answer28.md` 8장에 적는다.

---

## 10. 결과 문서 — `Docs/Claude/Answer/answer28.md`

```markdown
# answer28 — 검증 요약·등급 계약 문서화 결과 (314·315·316)

> 브랜치 feature/S15P21A307-314-validation-summary-grade (develop 최신화 후)
> 코드 변경 0건 · 문서 n건 · 커밋 n건 · 푸시 성공/실패
> 테스트 n개 통과 / 실패 n개
> 구현 코드는 d84c6d5 (S15P21A307-310) 에 이미 머지되어 있다

## 0. 결론
   무엇을 문서화했고 Jira 연결이 어떻게 만들어지는지 5줄 이내.

## 1. 코드 실측 대조
   | 항목 | prompt28 이 제시한 값 | 코드 실제 값 | 일치 |
   판정 규칙 · 프로퍼티 6개 · 응답 필드. 다른 것은 코드를 정본으로 삼았음을 적는다.

## 2. 스스로 판단한 것
   | 항목 | 판단 | 결정 규칙 몇 번 | 근거 |
   브랜치 경로(3장 본 경로 vs 대체 경로), differenceFromMedian vs RangeMax 의 FE 기본값,
   Docs/Claude 커밋 포함 여부 등. 미확정으로 남긴 항목은 이유를 적는다.

## 3. 명세서 누락 발견
   GET .../questions 가 명세서 md·csv 에 없었다는 사실과 추가한 내역.

## 4. 작성한 문서
   파일별로 무엇을 담았는지. 신규와 수정 구분.

## 5. 테스트 실행 결과
   실행한 명령과 출력 원문. 통과 개수. 기준선 393 대조.
   실행하지 못했으면 원문 오류와 사유.

## 6. 발견한 결함·개선점 — 고치지 않고 보고
   부족한 테스트, 문서와 어긋나는 동작 등. 재현 조건 포함. 없으면 없다고 명시한다.

## 7. 커밋·푸시·MR
   실행한 명령과 결과. 커밋 SHA. 푸시 성공 여부.
   MR 을 생성했으면 URL, 못 했으면 본문 전문.
   워크트리 정리 결과.

## 8. Jira 설명 초안 3건과 상태 전환 제안 (복사용, 등록하지 않음)
   314 / 315 / 316 각각의 설명 필드 초안.
   각 이슈를 어떤 상태로 옮겨야 하는지와 근거.
   상위 Epic 269 가 '해야 할 일' 인 점도 함께 적는다.
```

`answer28.md` 만 읽어도 **무엇을 문서화했고, 무엇을 스스로 판단했고, 테스트가 실제로 통과했는지**가 분명해야 한다. **코드에서 확인하지 않은 값을 쓰지 말 것. 실행하지 않은 검증을 완료로 적지 말 것.**
