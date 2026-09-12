# BE 구현 증거 대조표

> **목적** — 구현은 끝났으나 커밋 메시지에 Jira 이슈 키가 누락되어 Jira 에서 연결이 보이지
> 않는 작업들의 **실제 구현 위치를 커밋·파일 단위로 남긴다.**
>
> 기준 커밋: `e2a63a6` (`origin/develop`, `Merge branch 'feature/S15P21A307-219-be-search-data-load'`)
> 대상: BE 에픽 6개 — `S15P21A307-27` · `-30` · `-32` · `-34` · `-44` · `-269`
> 작성 2026-09-09 · **갱신 2026-09-10** (`82d9a56` → `e2a63a6`, 105커밋 진행)
>
> 저장소: `https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A307`

---

## 0. 이번 갱신에서 바뀐 것

**초판(`82d9a56`)이 "미구현" 이라 적은 것 중 넷이 그 뒤로 해소됐다.** 낡은 판정을 그대로 두면
다음 사람이 이미 있는 코드를 다시 만든다. 정정 내역을 먼저 적는다.

| 초판 기술 | 현재 | 근거 |
|---|---|---|
| §6-2 사고 이미지 저장소 어댑터 **구현체 없음 · 503** | **해소** | `accident/image/S3AccidentImageStorage.java` · 머지 `14bb731` |
| §6-3 견적서 파일 경로(`-305`·`-306`·`-308`) **미머지** | **해소** | `EstimateFileProcessor` · `S3DocumentStorage` · `LlmEstimateOcrAdapter` 모두 develop |
| §3-4 `-307` 이력 정렬 **간헐 실패** | **해소** | `f503cb2` 동점 방지 키 추가 · 머지 `f7572ee` |
| §3-7 `-320` 후속 수정 **원격 미푸시** | **해소** | `eef1101` 이 `9ecff0f` 로 들어가 `b884360` 에 머지 |

**그리고 초판의 연결 복구 범위 판단이 실제와 달랐다.** 아래 2장 참조 — 초판 커밋이 이미
9개를 연결했고, 진짜 누락은 `-137`·`-143` 둘뿐이다.

### ⚠️ `S15P21A307-138` 이 삭제됐다 — `-425` 로 재생성

2026-09-10, **`S15P21A307-138`(이미지 전처리)이 실수로 삭제됐다.** Jira 는 삭제한 이슈를
복원하지 못하고 **키도 재사용하지 않는다.** 그래서 같은 내용을 **`S15P21A307-425`** 로 새로 만들었다.

| | |
|---|---|
| 삭제된 키 | `S15P21A307-138` — **영구 결번** |
| 재생성 키 | `S15P21A307-425` — 내용 동일, 상태 완료 |
| 제목 변경 | "HEIC 변환·" 을 뺐다. 서버 변환은 구현하지 않고 **FE 가 JPEG 로 변환**하기로 확정(2026-09-10) |

**커밋 `7c642ca` 의 메시지에는 `S15P21A307-138` 이 그대로 남아 있다.** 히스토리라 고칠 수 없다.
그 키를 Jira 에서 찾으면 나오지 않는 이유가 이것이다. **코드는 develop 에 그대로 있다.**

이 문서에서 그 작업을 가리킬 때는 **`-425`** 를 쓴다. 커밋 메시지 인용에서만 `-138` 이 보인다.

### ⚠️ `S15P21A307-309` 도 삭제됐다 — `-432` 로 재생성

2026-09-10, **`S15P21A307-309`(항목명 → 표준 작업 코드 동의어 사전)도 사라졌다.** `-138` 과
같은 경위이고 같은 결과다 — 복구 불가, 키 영구 결번.

| | |
|---|---|
| 삭제된 키 | `S15P21A307-309` — **영구 결번** |
| 재생성 키 | `S15P21A307-432` — 스토리 · 에픽 `-269` · 상태 완료 |
| 구현 위치 | 그대로다. 패키지는 `estimate` 가 아니라 **`estimatevalidation`** 이다 (§3-5) |

`d84c6d5` 의 메시지에는 `S15P21A307-310` 만 적혀 있어 `-432` 의 개발 패널도 비어 있다.
이 문서에서 그 작업을 가리킬 때는 **`-432`** 를 쓴다.

### 이번 2차 갱신(2026-09-10)에서 정정한 것

아래 넷은 `25dccdd` 기준 실측으로 확인했다. 나머지 판정은 초판 기준 그대로다.

| 이전 기술 | 현재 | 근거 |
|---|---|---|
| §6-1 블러 — **검출 모델이 선행. 데이터셋에는 블러본이 있다** | **둘 다 틀렸다** | S3 실측 `blurred.jpg` 0개 · `blur_key` 적재·조회 코드 0건 |
| §6-2 조회용 presigned GET URL **없음** | **해소** | `S15P21A307-427` 머지 |
| §6-3 HEIC 서버 변환 **별도 스토리 필요** | **하지 않기로 확정** | FE 가 JPEG 로 보낸다 (2026-09-10) |
| §6-4 CORS 하드코딩 | **해소** | `S15P21A307-431` 머지 (`CorsProperties`) |

---

## 1. 왜 이 문서가 필요한가

작업 단위(작업/스토리)로 브랜치를 나누지 않고 **한 브랜치에서 여러 하위 작업을 함께 구현한
구간이 세 곳** 있다. 그 브랜치의 커밋 메시지에는 대표 키만 적혀 있어서, GitLab-for-Jira
연동이 나머지 하위 작업을 인식하지 못한다.

| 뭉친 커밋 | 커밋 메시지의 키 | 실제로 함께 구현된 작업 |
|---|---|---|
| `d84c6d5` (2026-09-06) | `S15P21A307-310` 하나 | `-307` · `-309`(현재 결번 → `-432`) · `-319` · `-320` · `-321` · `-322` |
| `d606124` (2026-09-08) | `S15P21A307-149` 하나 | `-150` · `-151` · `-152` |
| **`7c642ca` (2026-09-08)** | `S15P21A307-138`(현재 결번) · `-140` 둘 | **`-137`(스토리) · `-143`** |

**연동은 커밋 메시지·브랜치명·MR 제목·MR 설명만 스캔한다.** 소스 주석은 보지 않는다 —
`ImageUploadState` · `AccidentImageListResponse` · `AccidentImageController.list` 세 곳 Javadoc 에
"Task 143" 이라 적혀 있어도 잡히지 않는다.

**코드는 develop 에 있다.** 없는 작업을 했다고 주장하는 문서가 아니라, **이미 있는 코드가
어디에 있는지 지목하는 문서**다. 각 항목은 파일 경로와 클래스·메서드까지 적어 검증할 수 있게 했다.

---

## 2. 연결 상태 — 초판이 복구한 것과 이번에 복구하는 것

### 2-1. 초판 커밋 `135c133` 이 이미 연결한 9개

초판을 담은 커밋 메시지에 아래 키가 모두 적혀 있어 **Jira 개발 패널에 이미 붙어 있다.**

```
(S15P21A307-319, S15P21A307-150, S15P21A307-151, S15P21A307-152,
 S15P21A307-307, S15P21A307-309, S15P21A307-320, S15P21A307-321,
 S15P21A307-322)
```

| 이슈 | 종류 | 구현 커밋 | 머지 커밋 |
|---|---|---|---|
| `-150` | 작업 | `d606124` | `7eabcfb` |
| `-151` | 작업 | `d606124` | `7eabcfb` |
| `-152` | 작업 | `d606124` | `7eabcfb` |
| `-307` | 작업 | `d84c6d5` · `f503cb2` | `707cd7a` · `f7572ee` |
| `-309` → `-432` | 스토리 | `d84c6d5` | `707cd7a` |
| `-319` | 스토리 | `d84c6d5` | `707cd7a` |
| `-320` | 작업 | `d84c6d5` · `9ecff0f` | `707cd7a` · `b884360` |
| `-321` | 작업 | `d84c6d5` | `707cd7a` |
| `-322` | 작업 | `d84c6d5` | `707cd7a` |

> `-322` 는 **완료가 아니다.** 3-9 참조 — 서비스 메서드는 있으나 엔드포인트가 없다.

### 2-2. 이번 커밋이 연결하는 2개 — **진짜 누락은 이 둘뿐이다**

```bash
$ git log --oneline origin/develop --grep="S15P21A307-137\b"   # → 0건
$ git log --oneline origin/develop --grep="S15P21A307-143\b"   # → 0건
```

| 이슈 | 종류 | 제목 | 구현 커밋 | 머지 커밋 |
|---|---|---|---|---|
| `-137` | 스토리 | 다각도 이미지 업로드 기능 구현 | `7c642ca` · `584bcb4` | `c1200c2` · `14bb731` |
| `-143` | 작업 | 파일별 업로드 상태(대기·진행·완료·실패) 관리 | `7c642ca` | `c1200c2` |

`-137` 은 `f773e46`(문서 커밋)에 한 번 등장하지만 **그 커밋은 develop 밖 브랜치**에 있어
연동이 잡지 못한다. `-143` 은 전체 히스토리 어디에도 없다.

---

## 3. 항목별 구현 위치

경로는 `backend/src/main/java/com/ssafy/a307/` 기준. 테스트는 `backend/src/test/java/com/ssafy/a307/`.

### 3-0-A. `-137` 다각도 이미지 업로드 기능 구현 (스토리)

하위 3개 작업의 합이다.

| 하위 | 상태 | 위치 |
|---|---|---|
| `-425` EXIF 방향 보정·리사이즈본 생성 (구 `-138`) | ✅ | `accident/image/AccidentImagePreprocessor.java` · `ExifOrientation.java` |
| `-139` 사고 이미지 S3 업로드 연동 | ✅ | `accident/image/S3AccidentImageStorage.java` |
| `-140` 업로드 제약 검증 (20장·형식·20MB) | ✅ | `accident/image/AccidentImageValidator.java` · `ImageFormat.java` · `ImageSignatures.java` |

**업로드는 2단계이고 바이트는 서버를 거치지 않는다.**

```
1. POST /api/accidents/{id}/images/upload-urls   제약 검증 후 presigned PUT URL 발급
2. PUT  {presigned url}                          브라우저 → S3 직접. BE 무관
3. POST /api/accidents/{id}/images               완료 통보. 서버가 재검증하고 전처리
```

**⚠️ HEIC 서버 변환은 이 스토리에 포함되지 않았다.** Story 본문의
`[확인·결정 사항]` 이 *"미지원 시 업로드 전 클라이언트 변환 안내"* 를 대안으로 승인해 뒀고,
`ImageIoImageDecoder` 가 JPEG·PNG 만 지원한다. HEIC 는 발급 단계에서 거절하고 안내한다.

```java
// ImageIoImageDecoder — JDK 에 HEIF 플러그인이 없고 순수 자바 라이브러리도 없다
public boolean supports(ImageFormat f) { return f == ImageFormat.JPEG || f == ImageFormat.PNG; }
```

**서버 변환은 별도 스토리로 분리한다.** 6-3 참조.

### 3-0-B. `-143` 파일별 업로드 상태 관리

| 파일 | 역할 |
|---|---|
| `accident/dto/ImageUploadState.java` | 상태 enum |
| `accident/dto/AccidentImageListResponse.java` | 집계 — `total`·`completed`·`pending`·`remainingSlots` |
| `accident/dto/AccidentImageResponse.java` | 파일별 `uploadState` |
| `accident/entity/AccidentImage.java` | `isUploadCompleted()` — asset 존재로 상태 유도 |
| `accident/controller/AccidentImageController.java` | `GET /api/accidents/{accidentId}/images` |

**API 명세서에 없던 엔드포인트를 새로 추가했다.** 목록·상태 조회 행이 없었다.
화면을 새로 열거나 세션이 끊긴 뒤에도 "무엇이 아직 안 올라갔는지" 를 알 수 있어야
개별 재시도가 성립한다.

**⚠️ 요구사항 22행의 4상태를 2상태로 축소했다.**

```java
public enum ImageUploadState {
    PENDING,     // URL 은 발급됐지만 완료 통보가 아직 없다
    COMPLETED    // 완료 통보까지 끝나 asset 이 저장되어 있다
}
```

이유 둘. **① 저장 위치가 없다** — 정본 스키마에 `upload_status` 류 컬럼이 없고, 상위 스토리
`-141` 이 "추가 기능·중요도 중" 이라 스키마를 건드리지 않았다. **② 구조적으로 서버가 볼 수 없다** —
presigned 직접 업로드에서 바이트는 브라우저 → S3 로 흐른다. 진행률과 진행·실패 구분은
FE 가 `XMLHttpRequest.upload.onprogress` 로 계산해야 한다.

소유자 검사는 Repository 쿼리 조건에 있다 — 남의 사고·없는 사고 모두 404 다.

검증: `accident/service/AccidentImageServiceTest.java` — "완료 통보를 받지 못한 파일을 PENDING 으로
알려준다" · "남의 사고 상태는 404 다" / `accident/controller/AccidentImageControllerTest.java` —
"GET .../images — 완료·대기 개수와 파일 목록을 준다"

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

연동 테스트: `accident/service/AccidentServiceTest.java` 의 "사고 스냅샷 기반 유사 사례 검색 조건" 3건 —
"등록 차량과 즉시 입력은 같은 검색 조건 객체로 수렴한다" · "차량 수정·삭제와 모델 마스터 변경
후에도 두 분기의 응답·검색 조건은 불변이다" · "검색 조건 조회도 남의 사고와 없는 사고를 404로 숨긴다"

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

> **초판의 "알려진 결함" 은 해소됐다.** 초판은 이 테스트가 간헐적으로 실패한다고 적었다 —
> `findByMemberIdOrderByCreatedAtDesc` 에 동점 방지 키가 없어 같은 시각 두 건의 순서가
> 비결정적이었다. `f503cb2` 가 `createdAt desc, validationId desc` 를 넣어 고쳤고
> `a7290cb` 가 그것을 테스트로 고정했다. 머지 `f7572ee`.

### 3-5. `-432`(구 `-309`) 항목명 → 표준 작업 코드 동의어 사전 구축

| 파일 | 역할 |
|---|---|
| `estimatevalidation/entity/PartNameMapping.java` | 매핑 엔티티 |
| `estimatevalidation/repository/PartNameMappingRepository.java` | 조회 |
| `estimatevalidation/service/PartNameMappingService.java` | `loadDictionary()` · `map(rawItemName, dictionary)` |
| `estimatevalidation/service/PartNameNormalizer.java` | 항목명 정규화 |
| `estimatevalidation/domain/WorkTypeMapper.java` | 작업 유형 매핑 |
| `pipeline/jobs/generate_part_name_mapping_seed.py` | 시드 SQL 생성 |

**실사용 지점** — `estimatevalidation/service/EstimateValidationService.java`

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

**후속 수정 — 초판 이후 머지됨.** 수리 완료일이 사고 접수일보다 이전이면 400 으로 거절한다.
시간대는 `Asia/Seoul` 로 고정한다 (UTC 로 계산하면 09:00 KST 이전에 접수된 사고의 접수일이 하루 밀린다).

```
9ecff0f  Fix: 수리 완료일이 사고 접수일보다 이전이면 거절 (S15P21A307-320)
브랜치   fix/S15P21A307-320-be-repair-date-lower-bound
머지     b884360
```

> 초판은 이 수정을 `eef1101` 로 적고 "아직 푸시되지 않았다" 고 했다. **푸시 과정에서 `9ecff0f` 로
> 다시 만들어졌고 develop 에 들어갔다.** `-320` 에는 브랜치 증거가 별도로 있다.

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

**남은 것** — `export()` 를 노출하는 엔드포인트가 없다. 현재 호출되는 곳은 테스트뿐이다.

**그래서 이 작업은 완료가 아니라 진행 중이다.** 적재 대상·주기·인증 방식이 정해지지 않았다.

---

## 4. 이미 Jira 에 브랜치 증거가 있는 스토리

아래는 브랜치명에 키가 포함되어 머지되었으므로 **이 문서의 대상이 아니다.**

| 스토리·작업 | 머지 브랜치 | 에픽 |
|---|---|---|
| `-112` 제조사·차량명·연식 등록 | `feature/S15P21A307-112-vehicle-registration` | `-27` |
| `-121` 사고 현장 체크리스트 제공 | `feature/S15P21A307-121-accident-checklist` | `-30` |
| `-123` 각도별 촬영 가이드 | `feature/S15P21A307-123-shooting-guide` | `-30` |
| `-125` 촬영 품질 안내 | `feature/S15P21A307-125-image-quality-threshold` | `-30` |
| `-139`·`-142` 사고 이미지 S3·presigned | `feature/S15P21A307-139-be-accident-image-s3` | `-32` |
| `-140` 업로드 제약 검증 | `feature/S15P21A307-140-be-20-jpg-png-heic-20mb` | `-32` |
| `-149` 사고 차량 선택·입력 | `feature/S15P21A307-149-accident-vehicle-selection` | `-34` |
| `-219` 공개 데이터셋 일괄 적재 | `feature/S15P21A307-219-be-search-data-load` | `-44` |
| `-224` 회원 사고 이력 조회 | `feature/S15P21A307-224-member-accident-history` | `-44` |
| `-307` 이력 정렬 동점 키 | `fix/S15P21A307-307-be-history-sort-tiebreak` | `-269` |
| `-310` 과다·불필요 항목 표시 | `feature/S15P21A307-310-quote-anomaly-detection` | `-269` |
| `-314` 검증 요약·등급 | `feature/S15P21A307-314-validation-summary-grade` | `-269` |
| `-317` 정비소 확인 질문 생성 | `feature/S15P21A307-317-repair-shop-questions` | `-269` |
| `-318` 질문 UNIQUE 제약 | `fix/S15P21A307-318-be-question-unique-constraint` | `-269` |
| `-320` 수리 완료일 하한 검증 | `fix/S15P21A307-320-be-repair-date-lower-bound` | `-269` |
| `-388` 원본 key 비노출 검증 | `fix/S15P21A307-388-be-image-original-key-hidden` | `-44` |
| `-398`·`-399` 검증 PDF·큐 인덱스 | `feature/…-398-be-validation-pdf` · `chore/…-399-…` | `-269` |
| `-417` 스키마 정합성 테스트 | `test/S15P21A307-417-be-schema-conformity` | — |

---

## 5. 에픽별 현황 — 실제 코드 기준 (`e2a63a6`)

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
| `-121`·`-122` 체크리스트 | 완료 | ✅ `guide/controller/ChecklistController` GET `/api/guides/checklist` |
| `-123`·`-124` 촬영 가이드 | 완료 | ✅ `guide/controller/ShootingGuideController` GET `/api/guides/shooting` |
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
그 자리를 채우는 것이 `S15P21A307-120` [AI] 이미지 품질 검사 모듈이다.
**BE 는 경계를 만들어 두고 대기 중이며, 임계값은 코드 수정 없이 조정 가능하다(`-126` 요구사항).**

> 결과적으로 화면의 "다시 찍기" 안내가 뜨지 않는다. 활성화하려면 임계값 실측 보정이 선행이다.

### `-32` [BE] 이미지·영상 업로드 기능 구현 — 7/7

| 이슈 | Jira | 코드 |
|---|---|---|
| `-137` 다각도 이미지 업로드 (스토리) | 진행 중 | ✅ 3-0-A |
| `-425` 이미지 전처리 (구 `-138`, 삭제 후 재생성) | 완료 | ✅ `7c642ca` |
| `-139` 사고 이미지 S3 업로드 연동 | 완료 | ✅ `584bcb4` · 머지 `14bb731` |
| `-140` 업로드 제약 검증 | 완료 | ✅ `7c642ca` |
| `-141` 진행률 표시·재시도 (스토리) | 완료 | ✅ |
| `-142` Presigned URL 발급 API | 완료 | ✅ `8032673` |
| `-143` 파일별 업로드 상태 관리 | 완료 | ✅ 3-0-B |

**구 `-138` 의 제목에 있던 "HEIC 변환" 은 구현되지 않았다.** Story `-137` 의 `[확인·결정 사항]` 이
승인한 대안(클라이언트 변환 안내)으로 처리했고, **2026-09-10 에 FE 변환으로 확정**했다(6-3).
재생성한 `-425` 의 제목에서는 "HEIC 변환" 을 뺐다.

### `-34` [BE] 사전 정보 입력 기능 구현 — 4/4

| 이슈 | Jira | 코드 |
|---|---|---|
| `-149` 사고 차량 선택·입력 (스토리) | 진행 중 | ✅ |
| `-150` 사고 접수 생성 API | 완료 | ✅ 3-1 |
| `-151` 차량 정보 스냅샷 | 진행 중 | ✅ 3-2 |
| `-152` 유사 사례 검색 조건 연동 | 진행 중 | ✅ 3-3 |

**코드는 네 건 모두 develop 에 있다.** 머지 `7eabcfb`.

### `-44` [BE] 사고 이력 데이터 보관 기능 구현 — 9/17

| 이슈 | Jira | 코드 | 담당 |
|---|---|---|---|
| `-216` 사고 건 단위 데이터 저장 (스토리) | 해야 할 일 | 🔶 `-217` 완료, `-218` 남음 | 김경연 |
| `-217` S3 버킷·IAM 정책 구성 | **완료** | ✅ 머지 `97a6989` | 김경연 |
| `-218` 분석 결과 JSON 적재 | 해야 할 일 | ❌ `analysis_job` 엔티티 없음 | 김경연 |
| `-219` 공개 데이터셋 일괄 적재 (스토리) | **완료** | ✅ 머지 `e2a63a6` | 김경연 |
| `-220`·`-221`·`-222` | 완료 | ✅ `pipeline/jobs/*.py` | 김경연 |
| `-223` 증분 적재 | 해야 할 일 | ❌ | 김경연 |
| `-224` 회원 사고 이력 조회 (스토리) | 진행 중 | 🔶 `-225` 남음 | 김재원 |
| `-225` 사고 이력 목록 조회 API | 진행 중 | 🔶 페이지네이션·권한 ✅ / 썸네일·상태·예상비용 ❌ | 김재원 |
| **`-226`·`-227`·`-228` 번호판·얼굴 블러** | 진행 중 | **❌ 6-1 참조** | 김재원 |
| `-413`·`-414`·`-421`·`-424` | 완료 | ✅ `pipeline/sql/*` | 김경연 |

### `-269` [BE] 견적서 입력 기능 구현 — 14/19

| 이슈 | Jira | 코드 |
|---|---|---|
| `-305` 견적서 이미지·PDF 업로드 (스토리) | 완료 | ✅ |
| `-306` 파일 업로드·항목 직접 입력 API | 완료 | ✅ `EstimateFileProcessor` · `S3DocumentStorage` |
| `-307` 사고 건 연결·다중 견적서 | 완료 | ✅ 3-4 |
| `-308` OCR 항목 추출·확인 (스토리) | 해야 할 일 | 🔶 `LlmEstimateOcrAdapter` 있음 · 스토리 미완 |
| `-432`(구 `-309`) 동의어 사전 | 완료 | ✅ 3-5 |
| `-310`~`-313` 과다·불필요 항목 | 완료 | ✅ |
| `-314`~`-316` 요약·등급 | 완료 | ✅ |
| `-317`·`-318` 질문 생성 | 완료 | ✅ |
| `-319` 검증 이력·실제 수리비 (스토리) | 해야 할 일 | ✅ 3-6 |
| `-320` 실제 수리비 입력 API | 완료 | ✅ 3-7 |
| `-321` 비교 조회 | 진행 중 | ✅ 3-8 |
| `-322` 통계·모델 개선 적재 | 진행 중 | 🔶 3-9 |
| `-423` GMS 경유 LLM 공통 계층 | 완료 | ✅ `common/llm/` 14파일 |

---

## 6. 아직 구현되지 않은 것 — 근거와 함께

문서의 신뢰를 위해 **안 된 것을 안 됐다고 적는다.**

### 6-1. 번호판·얼굴 블러 (`-226` · `-227` · `-228`) — MVP 범위 밖으로 확정

`accident/entity/ImageVariant.java` 에 열거값만 예약되어 있다.

```java
public enum ImageVariant { ORIGINAL, RESIZED, THUMBNAIL, BLURRED; }
```

**`BLURRED` 를 생성하는 코드가 없다.** 얼굴·번호판 검출 코드도 없다.
`AccidentImagePreprocessor` 는 `RESIZED`·`THUMBNAIL` 만 만든다.

> **이름이 비슷한 것과 혼동하지 말 것.** `ImageBlurVariancePort` · `ImageQualityAssessor` 는
> **라플라시안 분산(흔들림·초점 불량)** 판정이고 `S15P21A307-120` [AI] 소관이다.
> 개인정보 블러와 무관하다.

**2026-09-10, 세 이슈 모두 완료(MVP 범위 밖)로 정리했다.** 각 이슈 본문에 근거가 있다.

**초판이 여기 적은 두 문장이 틀렸다.**

| 초판 기술 | 실측 |
|---|---|
| "데이터셋 이미지는 이미 블러본이 있다(`blur_key`)" | **틀렸다.** `blur_key` 는 컬럼만 있다. `load_search_data.py:211` 이 `storage_key` 만 넣고, 읽는 코드도 0건이다 (`git grep blur_key` — backend·pipeline 전역) |
| "검출 모델이 선행이다" | **선행 조건이 하나 더 있었다.** 가릴 대상이 없다 — S3 실측 결과 `repair-cases/.../original.jpg` 2,216개에 `blurred.jpg` 는 **0개**다. AI-Hub 데이터셋이 이미 비식별본으로 제공된다 |

**개인정보 노출 위험은 블러 없이 막혀 있다.** 요구사항 40행이 우려한 "유사 사례 화면에 제3자의
번호판·얼굴이 나가는 것" 이 구조적으로 일어나지 않는다.

| 방어선 | 근거 |
|---|---|
| 사고 이미지는 소유자만 조회 | 소유권을 쿼리 조건에 넣는다. 남의 사고는 404 · 서명 0회 (`-427`) |
| 원본(`ORIGINAL`)은 응답에 안 나간다 | `-388` — 목록 제외 + 어댑터가 원본 키 서명 거부 |
| 파생본에서 EXIF·GPS 제거 | `-425` 전처리. JPEG 재인코딩이 APP1 을 쓰지 않는다 |
| 원본은 7일 뒤 자동 삭제 | staging 버킷 `rule-id=delete-staging` (실측) · SSE AES256 |
| 유사 사례에는 데이터셋 이미지만 | 사용자 촬영 사진이 타인에게 노출되는 경로가 없다 |

**되살릴 때는 새 이슈로 만든다.** 검출을 LLM 으로 하면 LLM 이 바운딩 박스만 주고 서버가
`java.awt` 로 직접 가려야 하므로, 이 세 이슈의 완료 조건·포인트·실패 처리가 모두 달라진다.
저장 경로(`store(variant)`)는 `-427` 로 열려 있어 어댑터는 손댈 필요가 없다.

### 6-2. 사고 이미지 조회용 presigned GET URL — 해소 (`S15P21A307-427`)

**초판의 "어댑터 없음" 은 해소됐다**(`S3AccidentImageStorage`). 그러나 **조회용 URL 발급이 없다.**

```
AccidentImageStoragePort  →  createPresignedUploadUrl (PUT) 만 존재
                             read(String) 는 바이트 반환 — 서버 경유
S3ProfileImageStorage     →  presignGetObject ✅ 이미 함
S3DocumentStorage         →  presignGetObject ✅ 이미 함
```

`AccidentImageAssetResponse` 에 URL 필드가 없어 **업로드한 사진을 다시 볼 수 없다.**
썸네일도, 이력의 사진도 띄우지 못한다.

**해소됐다.** `S15P21A307-427` 이 `createPresignedDownloadUrl` 을 추가하고 머지됐다. 위 표는
그 이전 상태다. 어댑터가 **원본 키 서명을 거부**하므로 조회 경로가 열리면서 원본이 함께
새 나가지는 않는다. `AccidentImageAssetResponse` 는 키가 아니라 URL 을 담는다.

### 6-3. HEIC 서버 변환 — 하지 않기로 확정

`ImageIoImageDecoder` 가 JPEG·PNG 만 지원한다. JDK 에 HEIF 플러그인이 없고 순수 자바
라이브러리도 없어 네이티브 바이너리(libheif/ImageMagick)가 필요하다.

**앱 배포 이미지(Dockerfile)가 정의되어 있지 않아 설치 지점이 없다** —
`docker-compose.yml` 은 DB·Redis 만 띄우고 *"애플리케이션은 여기 없다"* 고 적혀 있다.

`ImageDecoderPort` 구현체만 갈아 끼우면 되도록 설계돼 있다. iPhone 기본 포맷이라
실사용자 상당수가 걸리므로, **FE 변환으로 충분한지 먼저 판단할 것을 권한다.**

**2026-09-10, 하지 않기로 확정했다.** FE 가 업로드 전 JPEG 로 변환해 보낸다 — 원 요구사항의
`[확인·결정 사항]` 이 명시한 대안이다. 그래서 별도 스토리를 만들지 않았다. 발급 단계에서
400 으로 거절하고 안내하며, `ImageFormat` 에서 HEIC 를 지우지는 않았다.

### 6-4. CORS 허용 오리진 하드코딩 — 해소 (`S15P21A307-431`)

```java
// config/SecurityConfig.java:141
c.setAllowedOrigins(List.of("http://localhost:5173"));
```

`app.frontend-base-url` 은 환경변수인데 **CORS 만 고정**이고, `setAllowCredentials(true)` 라
`*` 도 쓸 수 없다. **로컬에서는 드러나지 않고 배포 순간 전 화면이 죽는다.**

**해소됐다.** `S15P21A307-431` 이 `CorsProperties` 로 분리해 머지됐다. 위 코드는 그 이전
상태다. 값은 `app.cors.allowed-origins` 이고 기본값이 `app.frontend-base-url` 이라 로컬
동작은 그대로다. `*` 과 경로 포함 오리진은 시작 시점에 거절한다.

---

## 7. 확인 방법 — 재현 가능하게

이 문서의 모든 판정은 아래 명령으로 재현할 수 있다.

**어떤 이슈 키가 develop 히스토리에 있는지**

```bash
git log --oneline origin/develop --grep="S15P21A307-143\b"
```

**어떤 커밋이 파일을 처음 만들었는지**

```bash
git log --oneline --diff-filter=A origin/develop \
  -- backend/src/main/java/com/ssafy/a307/accident/dto/ImageUploadState.java
```

**어떤 커밋이 어느 MR 로 들어왔는지**

```bash
git log --oneline --merges origin/develop --ancestry-path 7c642ca..origin/develop | tail -1
```

**구현체가 실제로 있는지**

```bash
git grep -ln "implements AccidentImageStoragePort" origin/develop -- backend/src/main
```

---

## 8. 후속 조치

| 대상 | 조치 |
|---|---|
| `-137`·`-143` | **이 커밋으로 Jira 연결 복구. 완료로 전환 가능** |
| `-149`·`-151`·`-152` | `135c133` 으로 이미 연결됨. **완료로 전환 가능** |
| `-319`·`-321` | `135c133` 으로 이미 연결됨. **완료로 전환 가능** |
| `-432`(구 `-309`) | 재생성 시 **완료로 전환했다.** 개발 패널은 비어 있다 (§0) |
| `-322` | **진행 중 유지.** 엔드포인트가 없다 (3-9) |
| `-125` | 완료 유지하되 `-120`(AI) 의존과 `enabled=false` 를 이슈에 기록 |
| `-225` | 진행 중 유지. **썸네일·상태는 `7393780` 으로 완료**(`THUMBNAIL` variant 사용 — 블러와 무관했다). 예상 비용만 `-50` 대기 |
| `-226`·`-227`·`-228` | **완료(MVP 범위 밖)로 전환했다** (§6-1) |
| **사고 이미지 GET presign** | **`-427` 로 등록·머지 완료** (§6-2) |
| **CORS 프로퍼티화** | **`-431` 로 등록·머지 완료** (§6-4) |
| **HEIC 서버 변환** | **하지 않기로 확정.** 이슈 만들지 않음 (§6-3) |

**앞으로 이런 누락을 막으려면** — 한 브랜치에서 여러 하위 작업을 구현할 때 커밋 메시지에
관련 키를 모두 적는다. 예: `(S15P21A307-137, S15P21A307-140, S15P21A307-143)`

> `7c642ca` 가 `-138`(현 `-425`)·`-140` 만 적고 `-137`·`-143` 을 빠뜨린 것이 이번 갱신의 직접 원인이다.

**그리고 이슈를 지우지 않는다.** `-138` 과 `-309` 삭제로 커밋 메시지의 키 둘이 영구 결번이
됐다. 제목이나 범위가 바뀌었을 때는 **수정**하고, 폐기할 때는 본문에 사유를 적고 완료로
전환한다 — 삭제하면 되돌릴 방법이 없다.
