# BE 구현 증거 대조표

> **목적** — 구현은 끝났으나 커밋 메시지에 Jira 이슈 키가 누락되어 Jira 에서 연결이 보이지
> 않는 작업들의 **실제 구현 위치를 커밋·파일 단위로 남긴다.**
>
> 기준 커밋: `82d9a56` (`origin/develop`, `Merge branch 'feature/S15P21A307-424-be-estimate-raw-load'`)
> 대상: BE 에픽 5개 — `S15P21A307-27` · `-30` · `-34` · `-44` · `-269` (하위 51개)
> 작성 시점: 2026-09-09
>
> 저장소: `https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A307`

---

## 1. 왜 이 문서가 필요한가

작업 단위(작업/스토리)로 브랜치를 나누지 않고 **한 브랜치에서 여러 하위 작업을 함께 구현한
구간이 두 곳** 있다. 그 브랜치의 커밋 메시지에는 대표 키 하나만 적혀 있어서, GitLab-for-Jira
연동이 나머지 하위 작업을 인식하지 못한다.

| 뭉친 커밋 | 커밋 메시지의 키 | 실제로 함께 구현된 작업 |
|---|---|---|
| `d84c6d5` (2026-09-06) | `S15P21A307-310` 하나 | `-307` · `-309` · `-319` · `-320` · `-321` · `-322` |
| `d606124` (2026-09-08) | `S15P21A307-149` 하나 | `-150` · `-151` · `-152` |

**코드는 develop 에 있다.** 없는 작업을 했다고 주장하는 문서가 아니라, **이미 있는 코드가
어디에 있는지 지목하는 문서**다. 각 항목은 파일 경로와 클래스·메서드까지 적어 검증할 수 있게 했다.

이 문서를 담은 커밋 메시지에 누락된 키 9개를 전부 적어 Jira 연결을 복구한다.

---

## 2. 이 커밋이 연결하는 누락 키 — 9개

| 이슈 | 종류 | 제목 | 구현 커밋 | 머지 커밋 |
|---|---|---|---|---|
| `-150` | 작업 | 사고 접수 생성 API 구현 | `d606124` | `7eabcfb` |
| `-151` | 작업 | 사고 접수 차량 정보 스냅샷 저장 | `d606124` | `7eabcfb` |
| `-152` | 작업 | 선택·입력 차량 정보의 유사 사례 검색 조건 연동 | `d606124` | `7eabcfb` |
| `-307` | 작업 | 사고 건 연결 및 다중 견적서 등록 처리 | `d84c6d5` | `707cd7a` |
| `-309` | 작업 | 항목명 → 표준 작업 코드 동의어 사전 구축 | `d84c6d5` | `707cd7a` |
| `-319` | 스토리 | 검증 이력·실제 수리비 기록 기능 구현 | `d84c6d5` | `707cd7a` |
| `-320` | 작업 | 실제 수리비 입력 API 구현 | `d84c6d5` | `707cd7a` |
| `-321` | 작업 | AI·정비소·실제 금액 비교 조회 구현 | `d84c6d5` | `707cd7a` |
| `-322` | 작업 | 실제 금액의 통계·모델 개선 데이터 적재 연동 | `d84c6d5` | `707cd7a` |

> `-322` 는 **완료가 아니다.** 3-9 참조 — 서비스 메서드는 있으나 엔드포인트가 없다.

---

## 3. 항목별 구현 위치

경로는 `backend/src/main/java/com/ssafy/a307/` 기준. 테스트는 `backend/src/test/java/com/ssafy/a307/`.

### 3-1. `-150` 사고 접수 생성 API (등록 차량 선택 / 즉시 입력 분기, 로그인 필수)

| 파일 | 역할 |
|---|---|
| `accident/controller/AccidentController.java` | `POST /api/accidents` |
| `accident/dto/AccidentCreateRequest.java` | 등록 차량 선택 / 즉시 입력 분기 요청 |
| `accident/dto/DirectVehicleInput.java` | 즉시 입력 경로의 차량 정보 |
| `accident/entity/VehicleInputType.java` | 두 경로를 구분하는 enum |
| `accident/service/AccidentService.java` | 생성 트랜잭션 |
| `vehicle/service/VehicleRegistrationService.java` | 즉시 입력 시 차량 등록 |

**로그인 필수** — `SecurityConfig` 의 `anyRequest().hasAnyRole("USER","ADMIN")` 로 걸린다.
검증 테스트: `accident/controller/AccidentSecurityTest.java`

### 3-2. `-151` 사고 접수 차량 정보 스냅샷 저장

`accident/entity/Accident.java` 에 스냅샷 6개 열. **전부 `updatable = false`** —
차량 정보가 나중에 수정되어도 접수 당시 값이 보존된다.

```java
@Column(name = "snapshot_model_id",      nullable = false, updatable = false) private Long   snapshotModelId;
@Column(name = "snapshot_manufacturer",  nullable = false, updatable = false) private String snapshotManufacturer;
@Column(name = "snapshot_model_name",    nullable = false, updatable = false) private String snapshotModelName;
@Column(name = "snapshot_vehicle_type",  nullable = false, updatable = false) private VehicleType snapshotVehicleType;
@Column(name = "snapshot_car_class",     nullable = false, updatable = false) private CarClass    snapshotCarClass;
@Column(name = "snapshot_model_year",    nullable = false, updatable = false) private Short       snapshotModelYear;
```

DDL 반영: `Docs/Erd/A307_ddl_final.sql` (같은 커밋에서 12줄 추가)
롤백 검증: `accident/service/AccidentCreationRollbackTest.java`

### 3-3. `-152` 선택·입력 차량 정보의 유사 사례 검색 조건 연동

| 파일 | 역할 |
|---|---|
| `accident/dto/AccidentVehicleSearchCondition.java` | 검색 조건 투영 record |
| `accident/service/AccidentVehicleSearchConditionResolver.java` | 사고 → 검색 조건 변환 |
| `accident/repository/AccidentRepository.java` | `findVehicleSearchCondition(...)` 생성자 투영 |

**스냅샷 값이 검색 조건으로 전달된다** — 차량 테이블을 다시 읽지 않는다.
연동 테스트: `accident/service/AccidentServiceTest.java` · `estimatevalidation/service/EstimateValidationServiceTest.java`

### 3-4. `-307` 사고 건 연결 및 다중 견적서 등록 처리

`estimatevalidation/entity/EstimateValidation.java`

```java
@JoinColumn(name = "accident_id", nullable = false)
private Accident accident;
```

**`nullable = false`** — 모든 검증이 사고 건에 반드시 연결된다.

**한 사고에 여러 견적서**가 붙는 것은 테스트로 고정되어 있다:
`estimatevalidation/service/EstimateValidationServiceTest.java` →
`sameAccidentAcceptsMultipleValidationsAndHistoryIsNewestFirst()`

> **알려진 결함** — 이 테스트는 간헐적으로 실패한다.
> `EstimateValidationRepository.findByMemberIdOrderByCreatedAtDesc` 에 동점 방지 키가 없어
> 같은 시각에 만들어진 두 건의 순서가 비결정적이다.
> `AccidentRepository` 는 `order by a.createdAt desc, a.accidentId desc` 로 이미 막아 두었다.
> **기능 결함이 아니라 정렬 안정성 문제이며, 별도 이슈로 다룬다.**

### 3-5. `-309` 항목명 → 표준 작업 코드 동의어 사전 구축

| 파일 | 역할 |
|---|---|
| `estimatevalidation/entity/PartNameMapping.java` | 매핑 엔티티 |
| `estimatevalidation/repository/PartNameMappingRepository.java` | 조회 |
| `estimatevalidation/service/PartNameMappingService.java` | `loadDictionary()` · `map(rawItemName, dictionary)` |
| `pipeline/jobs/generate_part_name_mapping_seed.py` | 시드 SQL 생성 |

**실사용 지점** — `estimatevalidation/service/EstimateValidationService.java:88, 236`

```java
Map<String, PartNameMappingService.MappedPart> dictionary = partNameMappingService.loadDictionary();
...
PartNameMappingService.MappedPart mapped = partNameMappingService.map(item.rawItemName(), dictionary);
```

시드 생성 스크립트는 **확정 매핑(`MAPPED`, `MAPPED_EXTENDED`)만 포함**하고
사람 확인이 필요한 항목(`REVIEW_CONFLICT`, `MAPPED_SIDE_UNKNOWN`, `MAPPED_SPLIT`,
`OUT_OF_SCOPE_PART`, `NOT_A_PART`)은 억지로 확정하지 않는다.

### 3-6. `-319` 검증 이력·실제 수리비 기록 (스토리)

하위 세 작업의 합이다 — 3-7 · 3-8 · 3-9.

**검증 이력 조회**: `estimatevalidation/controller/EstimateValidationController.java` →
`GET /api/estimate-validations/me`

### 3-7. `-320` 실제 수리비 입력 API (금액·수리 완료일·정비소)

| 파일 | 역할 |
|---|---|
| `accident/controller/AccidentController.java` | `PUT /api/accidents/{accidentId}/actual-cost` |
| `accident/dto/ActualRepairCostRequest.java` | 요청 |
| `accident/dto/ActualRepairCostResponse.java` | 응답 |
| `accident/entity/Accident.java` | `actualRepairCost` · `actualCostRecordedAt` · `actualRepairCompletedDate` · `repairShopName` |

**후속 수정** — 수리 완료일이 사고 접수일보다 이전이면 400 으로 거절하는 검증을 별도 커밋으로
반영했다. 시간대는 `Asia/Seoul` 로 고정한다 (UTC 로 계산하면 09:00 KST 이전에 접수된 사고의
접수일이 하루 밀린다).

```
eef1101  Fix: 수리 완료일이 사고 접수일보다 이전이면 거절 (S15P21A307-320)
브랜치   fix/S15P21A307-320-be-repair-date-lower-bound
```

> 이 커밋은 이 문서 작성 시점에 **아직 원격에 푸시되지 않았다.** 푸시되면 `-320` 에 대한
> 정상적인 브랜치 증거가 별도로 생긴다.

### 3-8. `-321` AI 견적·정비소 견적·실제 금액 비교 조회

| 파일 | 역할 |
|---|---|
| `estimatevalidation/controller/CostComparisonController.java` | `GET /api/accidents/{accidentId}/cost-comparison` |
| `estimatevalidation/service/CostComparisonService.java` | 비교 로직 |
| `estimatevalidation/dto/CostComparisonResponse.java` | 응답 |

**소유자 검사는 쿼리 조건에 있다** — 남의 사고 건은 404 다 (403 은 그 자원이 존재한다는
사실을 알려주므로 쓰지 않는다).

### 3-9. `-322` 실제 금액의 통계·모델 개선 데이터 적재 연동 — **부분 구현**

| 구현된 것 | 위치 |
|---|---|
| 적재용 record | `estimatevalidation/dto/ModelImprovementRecord.java` |
| 추출 메서드 | `estimatevalidation/service/CostComparisonService.java` → `export(afterAccidentId, requestedSize)` |
| 조회 쿼리 | `estimatevalidation/repository/EstimateValidationRepository.findModelImprovementRecords` |

**남은 것** — `export()` 를 노출하는 엔드포인트가 없다. 현재 호출되는 곳은 테스트뿐이다
(`estimatevalidation/service/EstimateValidationServiceTest.java:222`).

**그래서 이 작업은 완료가 아니라 진행 중이다.** 적재 대상·주기·인증 방식이 정해지지 않았다.

---

## 4. 이미 Jira 에 증거가 있는 스토리 — 9개

아래는 브랜치명에 키가 포함되어 머지되었으므로 **이 문서의 대상이 아니다.**

| 스토리 | 머지 브랜치 | 에픽 |
|---|---|---|
| `-112` 제조사·차량명·연식 등록 | `feature/S15P21A307-112-vehicle-registration` | `-27` |
| `-121` 사고 현장 체크리스트 제공 | `feature/S15P21A307-121-accident-checklist` | `-30` |
| `-123` 각도별 촬영 가이드 | `feature/S15P21A307-123-shooting-guide` | `-30` |
| `-125` 촬영 품질 안내 | `feature/S15P21A307-125-image-quality-threshold` | `-30` |
| `-149` 사고 차량 선택·입력 | `feature/S15P21A307-149-accident-vehicle-selection` | `-34` |
| `-224` 회원 사고 이력 조회 | `feature/S15P21A307-224-member-accident-history` | `-44` |
| `-310` 과다·불필요 항목 표시 | `feature/S15P21A307-310-quote-anomaly-detection` | `-269` |
| `-314` 검증 요약·등급 | `feature/S15P21A307-314-validation-summary-grade` | `-269` |
| `-317` 정비소 확인 질문 생성 | `feature/S15P21A307-317-repair-shop-questions` | `-269` |

---

## 5. 에픽별 현황 — 실제 코드 기준

### `-27` [BE] 차량 등록 기능 구현 — 5/5

| 이슈 | Jira | 코드 |
|---|---|---|
| `-112` 제조사·차량명·연식 등록 | 완료 | ✅ |
| `-113` 차량 마스터 데이터 구축 | 완료 | ✅ `Docs/Erd/vehicle_model_seed.sql` |
| `-114` 차급 4단계 매핑표 | 완료 | ✅ `vehicle/entity/CarClass.java` — `CITY_CAR`·`COMPACT`·`MID_SIZE`·`FULL_SIZE` |
| `-115` 차량 등록·수정·삭제 API | 완료 | ✅ `VehicleController` — POST · GET `/me` · PATCH · DELETE |
| `-116` 차량 모델 목록 조회 API | 완료 | ✅ `VehicleModelController` — GET `/api/vehicle-models` |

**Jira 와 코드가 완전히 일치하는 에픽이다.**

### `-30` [BE] 사고 현장 가이드 기능 구현 — 6/6 (단서 1)

| 이슈 | Jira | 코드 |
|---|---|---|
| `-121`·`-122` 체크리스트 | 완료 | ✅ `guide/controller/ChecklistController` GET `/api/guides/checklist` · `checklist.json` |
| `-123`·`-124` 촬영 가이드 | 완료 | ✅ `guide/controller/ShootingGuideController` GET `/api/guides/shooting` · `shooting-guide.json` |
| `-125` 촬영 품질 안내 | 완료 | 🔶 아래 참조 |
| `-126` 판정 임계값 외부화 | 완료 | ✅ `app.image-quality.*` 3개 |

**`-125` 는 코드가 있으나 기본값이 꺼짐이다.**

```properties
app.image-quality.enabled=false
app.image-quality.min-short-edge-px=720
app.image-quality.blur-variance-threshold=100.0
```

`accident/image/ImageQualityAssessor.java` · `ImageBlurVariancePort.java` ·
`entity/ImageQualityStatus.java`(`PASS`/`WARN`) 는 있지만 **`ImageBlurVariancePort` 의 구현체가 없다.**
그 자리를 채우는 것이 `S15P21A307-120` [AI] 이미지 품질 검사 모듈(담당: 김희동)이다.
**BE 는 경계를 만들어 두고 대기 중이며, 임계값은 코드 수정 없이 조정 가능하다(`-126` 요구사항).**

### `-34` [BE] 사전 정보 입력 기능 구현 — 4/4

| 이슈 | Jira | 코드 |
|---|---|---|
| `-149` 사고 차량 선택·입력 (스토리) | 진행 중 | ✅ |
| `-150` 사고 접수 생성 API | 진행 중 | ✅ 3-1 |
| `-151` 차량 정보 스냅샷 | 진행 중 | ✅ 3-2 |
| `-152` 유사 사례 검색 조건 연동 | 진행 중 | ✅ 3-3 |

**코드는 네 건 모두 develop 에 있다.**

### `-44` [BE] 사고 이력 데이터 보관 기능 구현 — 10/17

| 이슈 | Jira | 코드 | 담당 |
|---|---|---|---|
| `-216` 사고 건 단위 데이터 저장 | 해야 할 일 | ❌ | 김경연 |
| `-217` S3 버킷·IAM 정책 구성 | 해야 할 일 | 🔶 버킷은 존재, 어댑터 없음 | 김경연 |
| `-218` 분석 결과 JSON 적재 | 해야 할 일 | ❌ | 김경연 |
| `-219` 공개 데이터셋 일괄 적재 (스토리) | 해야 할 일 | ✅ 하위 3개 완료 | 김경연 |
| `-220`·`-221`·`-222` | 완료 | ✅ `pipeline/jobs/*.py` | 김경연 |
| `-223` 증분 적재 | 해야 할 일 | ❌ | 김경연 |
| `-224`·`-225` 사고 이력 조회 | 진행 중 | ✅ `GET /api/accidents/me` · `AccidentPageResponse` | 김재원 |
| **`-226`·`-227`·`-228` 번호판·얼굴 블러** | 해야 할 일 | **❌ 6장 참조** | 김재원 |
| `-413`·`-414`·`-421`·`-424` | 완료 | ✅ `pipeline/sql/002·003·004.sql` 등 | 김경연 |

### `-269` [BE] 견적서 입력 기능 구현 — 15/19

| 이슈 | Jira | 코드 |
|---|---|---|
| `-305` 견적서 이미지·PDF 업로드 (스토리) | 진행 중 | 🔶 미머지 브랜치 |
| `-306` 파일 업로드·항목 직접 입력 API | 해야 할 일 | 🔶 미머지 브랜치 |
| `-307` 사고 건 연결·다중 견적서 | 해야 할 일 | ✅ 3-4 |
| `-308` OCR 항목 추출·확인 (스토리) | 해야 할 일 | 🔶 미머지 브랜치 |
| `-309` 동의어 사전 | 해야 할 일 | ✅ 3-5 |
| `-310`~`-313` 과다·불필요 항목 | 완료 | ✅ |
| `-314`~`-316` 요약·등급 | 완료 | ✅ |
| `-317`·`-318` 질문 생성 | 완료 | ✅ |
| `-319` 검증 이력·실제 수리비 (스토리) | 해야 할 일 | ✅ 3-6 |
| `-320` 실제 수리비 입력 API | 해야 할 일 | ✅ 3-7 |
| `-321` 비교 조회 | 해야 할 일 | ✅ 3-8 |
| `-322` 통계·모델 개선 적재 | 진행 중 | 🔶 3-9 |
| `-423` GMS 경유 LLM 공통 계층 | 완료 | ✅ `common/llm/` |

---

## 6. 아직 구현되지 않은 것 — 근거와 함께

문서의 신뢰를 위해 **안 된 것을 안 됐다고 적는다.**

### 6-1. 번호판·얼굴 블러 (`-226` · `-227` · `-228`) — 코드 없음

`accident/entity/ImageVariant.java` 에 열거값만 예약되어 있다.

```java
public enum ImageVariant { ORIGINAL, RESIZED, THUMBNAIL, BLURRED; }
```

`BLURRED` 를 **생성하는 코드가 없다.** 저장소 전체에서 이 값이 등장하는 곳은 위 선언 한 줄이
전부다. 얼굴·번호판 검출 코드도 없다.

`accident/image/AccidentImagePreprocessor.java` 는 `RESIZED`·`THUMBNAIL` 만 만든다.

**이는 기능 미비가 아니라 개인정보 노출 위험 항목이다** — 유사 사례 화면에 원본이 노출되면
제3자의 번호판·얼굴이 함께 나간다(`-228` 이 막으려는 것).

### 6-2. 사고 이미지 저장소 어댑터 (`-139` · `-142`, `-217` 과 연결) — 구현체 없음

`accident/image/AccidentImageStoragePort.java` 는 있으나 **운영 구현체가 없다.**
`implements AccidentImageStoragePort` 를 하는 클래스는 테스트 대역
(`accident/service/AccidentImageServiceTest.java`) 뿐이다.

그래서 사고 이미지 업로드·조회는 어댑터 부재로 **503** 을 낸다
(`Optional<Port>` 주입 + 없으면 503 이라는 이 저장소의 관례).

**전례는 있다** — `member/image/S3ProfileImageStorage.java` 가 같은 문제를 이미 풀었고,
AWS SDK(`software.amazon.awssdk:s3`)도 이미 의존성에 있다.

### 6-3. 견적서 파일 경로 (`-305` · `-306` · `-308`) — 미머지

구현은 존재하나 원격에 없다. 별도 브랜치에서 정리 중이다.

---

## 7. 확인 방법 — 재현 가능하게

이 문서의 모든 판정은 아래 명령으로 재현할 수 있다.

**어떤 이슈 키가 develop 히스토리에 있는지**

```bash
git log --oneline origin/develop --grep="S15P21A307-320\b" -E
```

**어떤 커밋이 파일을 처음 만들었는지**

```bash
git log --oneline --diff-filter=A --follow origin/develop \
  -- backend/src/main/java/com/ssafy/a307/accident/dto/ActualRepairCostRequest.java
```

**어떤 커밋이 어느 MR 로 들어왔는지**

```bash
git log --oneline --merges origin/develop --ancestry-path d84c6d5..origin/develop | tail -1
```

**구현체가 실제로 있는지**

```bash
git grep -ln "implements AccidentImageStoragePort" origin/develop -- backend/src
```

---

## 8. 후속 조치

| 대상 | 조치 |
|---|---|
| `-150`·`-151`·`-152`·`-307`·`-309`·`-319`·`-320`·`-321` | 이 커밋으로 Jira 연결 복구. **완료로 전환 가능** |
| `-322` | **진행 중 유지.** 엔드포인트가 없다 (3-9) |
| `-125` | 완료 유지하되 `-120`(AI) 의존을 이슈에 기록 |
| `-219` | 하위 3개가 완료이므로 스토리 상태 확인 필요 (담당: 김경연) |
| `-226`·`-227`·`-228` | 미착수. 착수 시 `-139` → `-142` → `-226` → `-227` → `-228` 순서 |
| `-139`·`-142` | 미착수. `S3ProfileImageStorage` 를 본보기로 |

**앞으로 이런 누락을 막으려면** — 한 브랜치에서 여러 하위 작업을 구현할 때 커밋 메시지에
관련 키를 모두 적는다. 예: `(S15P21A307-310, S15P21A307-320, S15P21A307-321)`
