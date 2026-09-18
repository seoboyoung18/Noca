# 데이터 파이프라인

유사 수리 사례 검색용 데이터 파이프라인. YOLO 출력과 AI-Hub 차량파손 데이터셋을 표준 코드로 정규화하고, 검색 대상 사례를 검증·적재한다.

## 현재 진행 상황

2026-09-10 `develop` (`4a0836d`) 기준으로 원천 보존·검색 후보 검증·사례/이미지 적재와 ROI 공용 모듈이 구현돼 있다. 임베딩 배치·Top-K 검색·검색 평가·수리비 통계 생성은 후속 작업이다.

구현 상태와 기존 실행 건수, 확인된 오류는 아래 각 job 절에 함께 적는다. DB·S3의 현재 건수는 이번 점검에서 재조회하지 않았다.

## 구조

```
pipeline/
├── standardization/   견적 항목·storage key 전용 정규화
├── jobs/              도메인별 배치 스크립트
│   ├── corpus/        검색 corpus 검증·manifest·검수
│   ├── ingestion/     AI-Hub·견적·검색 DB 적재
│   ├── estimates/     견적 라벨 추출·매핑·seed 생성
│   └── quality/       견적 데이터 검증
├── validation/        견적 행 분류·비용 검증 규칙
├── sql/               스테이징 DDL·기존 DB migration
└── requirements.txt
```

## standardization

AI 서버와 batch가 함께 쓰는 vision 정본은 [shared/vision](../shared/vision/README.md)이다.
이 폴더는 견적서 항목·storage key 처리만 담당한다. 부품·손상·ROI·검색 메타데이터는
여기에 복제하지 않고 `shared.vision`을 직접 import한다.

| 파일 | 역할 |
|---|---|
| `estimate_items.py` | 견적서 항목의 금액·작업 정규화 |
| `storage_keys.py` | AI-Hub 사례 이미지 storage key 생성 |
| `fixtures/` | 견적서 부품명 정규화 fixture |
| `README.md` | 견적 항목·storage key 계약과 shared vision 경계 |

사용 예:

```python
from shared.vision import PARTS, normalize_inference, normalize_repair_label, NormalizationError
```

새 batch와 AI vision 코드는 모두 `shared.vision`을 직접 import한다.

### 검색 메타데이터

`build_search_metadata`는 정규화된 추론 결과를 ROI 단위 검색 레코드로 바꾼다. 손상 검출
하나가 레코드 하나다. 품질이 낮아도 레코드는 남기고 `search.is_searchable`만 false로 둔다
— 제외된 검출도 원본과 대조할 수 있어야 한다.

2026-09-11 정책에서 정식 검색 이미지는 `damage_part`다. 같은 이미지의 damage
annotation은 `damage_type`과 ROI를 만들고, geometry가 명확히 겹치는 part annotation만
ROI `part_code`로 연결한다. 견적서에서 얻은 `part_code`는 사례 부품 후보이며 ROI 부품
정답이 아니다. 따라서 주 경로는 `damage_type` 필터 → strict `part_code` 조건(선택)
→ `damage_part` ROI 벡터 검색이다. 상세 계약은 [damage_part 중심 유사 사례 검색 스키마](../Docs/Erd/A307_DAMAGE_SEARCH_SCHEMA.md)를 본다.

`search_metadata.py`는 strict 검색과 vector-only를 구분한다. 유효 damage_type이
있지만 part_code가 없거나 모호한 ROI는 vector-only로 남기고, geometry 또는 damage_type이
없는 ROI만 제외한다.

`pipeline_version_id`는 필수 인자다. 검색은 같은 값을 가진 레코드끼리만 비교한다(설계 2절
전제 3번). 기본값을 두지 않은 이유는, 버전 없는 레코드가 한 번 적재되면 무엇으로 만든
값인지 되찾을 수 없어 재적재해야 하기 때문이다. `roi_id`에도 붙어 두 버전의 같은 검출이
충돌하지 않는다.

`roi.quality_status`는 `GOOD` / `LOW_CONFIDENCE` / `PARTIAL_PART` / `INVALID` 4종이고
사유는 `roi.quality_reasons` 목록에 남긴다. `PARTIAL_PART`는 부품 bbox가 이미지 경계에
닿은 경우다 — 부품 일부가 화면 밖이라 면적 비율은 못 믿지만 부품 코드·손상 유형은
정확하므로 후보에는 남긴다. 판정은 좌표 비교이며 화면 밖 면적을 추정하지 않는다. 가려진
부품은 경계에 닿지 않으므로 잡지 못한다.

부품 영역은 정규화된 추론 출력에 없다(detection의 `part`는 코드만 담는다). 호출부가
`part_boxes_by_detection_id`로 넘기며, 넘기지 않으면 추측하지 않고 `part_clipped=null` +
사유 `PART_BOX_UNKNOWN`으로 남긴다. `roi_box`가 돌려주는 clip 여부와는 다른 값이다 —
그쪽은 손상 ROI가 잘렸는지고 이쪽은 부품이 잘렸는지다.

표본 실측은 `roi.py` 상단 주석에 있다. TL_damage_part 라벨 1,200개에서 부품 경계 접촉
3.1%, 손상의 부품 미연결 15.0%다. 부품 잘림보다 부품 연결 실패가 더 큰 변수다.

## jobs

A~D로 나뉜다. 산출물을 전달하는 단계는 순서대로 실행한다. Raw 보존과 라벨 경로 인덱스 생성은 서로 독립적이다. 견적 행 검증 CLI는 현재 적재 배치와 분리돼 있다.

**A. 검색 대상 확정** — 이미지·라벨 기준

| 순서 | 스크립트 | 역할 |
|---|---|---|
| 1 | `validate_category_id_integrity.py` | `category_id` 기준 이미지·라벨·견적 조인 무결성 검증. orphan 라벨을 격리하고 검색 가능 사고 **후보**를 산출 |
| 2 | `validate_search_readiness.py` | 1의 후보에 damage_part geometry와 same-image part pairing을 추가 판정해 **최종 검색 가능 사고 수**를 확정 |

**B. 견적 부품명 표준화** — 견적서 텍스트 기준

| 순서 | 스크립트 | 역할 |
|---|---|---|
| 3 | `extract_estimate_labels.py` | 견적 JSON에서 원본 부품명·작업명을 집계 |
| 4 | `map_estimate_labels.py` | 원본명을 표준 부위 코드로 매핑. 판단이 필요한 항목은 `REVIEW_CONFLICT` / `OUT_OF_SCOPE_PART`로 분류해 남김 |

**C. 견적 데이터 품질**

| 순서 | 스크립트 | 역할 |
|---|---|---|
| 5 | `flag_estimate_outliers.py` | 수리 항목 수·최종금액의 사분위수 기반 이상치를 검수 대상으로 플래그 (삭제하지 않음) |

**D. 검색 테이블 적재** — 앞 결과를 DB로

| 순서 | 스크립트 | 역할 |
|---|---|---|
| 6 | `generate_part_name_mapping_seed.py` | 4의 매핑 워크북에서 `part_name_mapping` 기준 데이터 SQL 생성 |
| 7 | `build_search_sample_sql.py` | 2가 확정한 검색 가능 사고를 `repair_case`·`repair_case_image`·`repair_case_item` 적재 SQL로 생성 |
| 8 | `load_aihub_damage_dataset.py` | 원천 라벨 JSON을 `aihub_*` 스테이징 테이블에 직접 적재 (검색 테이블과 별개 계층) |
| 9 | `load_estimate_raw.py` | 원천 견적 JSON 125,006건을 `aihub_estimate_raw`에 원문 그대로 전수 적재 |
| 10 | `build_label_path_index.py` | 라벨 파일의 사례·이미지 경로 인덱스 CSV 생성. 10은 9에 의존하지 않는다 |
| 11 | `load_search_data.py` | 표본 또는 최종 검색 가능 범위를 `part_code`·`part_name_mapping`·`repair_case`·`repair_case_image`에 upsert |
| 12 | `verify_search_sample.py` | 1,000건 표본의 연결·중복·차급·이미지·원천 건수·재실행 멱등성 검증 |

**E. 서비스 데이터 적재** — 원천이 AI-Hub 파일이 아니라 운영 DB다

| 순서 | 스크립트 | 역할 |
|---|---|---|
| — | `load_service_accidents.py` | 실제 수리비가 기록된 서비스 사고를 `repair_case`에 `source='SERVICE'`로 증분 적재 |

순서를 비워 둔 것은 **A~D의 연속 단계가 아니기 때문이다.** 앞 단계 산출물에 기대지 않고
서비스에 수리비가 쌓이는 대로 따로 돌린다.

⚠️ **이 job은 파이프라인이 서비스 테이블(`accident`)을 읽는 첫 사례다.** 1~12는 전부
AI-Hub 원본 파일 → DB 방향이지만 13은 **DB → DB**다. 같은 DB 안의 이동이라 `--dsn`
하나로 되지만, 읽는 쪽이 사용자 데이터라는 점이 다르다.

적재한 `SERVICE` 행은 **사례 검색에 쓰지 않는다.** 사용자 사고의 금액은 AI 추정에서
온 것이라 사례로 쓸 만큼 검증되지 않았고, 다른 사람의 데이터이기도 하다. 배제는 조회
쪽에서 한다 — `RepairCaseDetailRepository.findPublicCase`와
`SimilarCaseRepository.findCases`가 둘 다 `rc.source <> 'SERVICE'`를 건다.
검색 코퍼스(`build_case_*`·`validate_search_readiness`)는 AI-Hub **파일**에서 만들어지므로
DB에 `SERVICE` 행이 생겨도 자동으로 섞이지 않는다.

A는 사진 라벨(YOLO 32종), B는 견적서 한글 텍스트를 다룬다. DB 마스터 seed에는 핵심 32종과 견적 전용 24종, 총 56종이 있고 확정 매핑 seed는 15,308행이다. 모호한 매핑 검수, 사진 손상과 견적 항목의 교차 연결 및 매핑 버전 관리는 남아 있다.

### 1. category_id 무결성 검증

```bash
python pipeline/jobs/corpus/validate_category_id_integrity.py \
  --subset-root "<AI-Hub 견적서 보유 subset 경로>" \
  --output-dir "<결과를 쓸 경로>"
```

산출물: `validation_summary.json`, `batch_job_execution.json`, `data_validation_error.jsonl`, `quarantine_manifest.csv`, `category_id_integrity.csv`, `case_id_linkage.csv`

### 2. 검색 준비도 검증

1단계가 만든 `case_id_linkage.csv`를 입력으로 받는다.

```bash
python pipeline/jobs/corpus/validate_search_readiness.py \
  --subset-root "<AI-Hub 견적서 보유 subset 경로>" \
  --linkage-csv "<1단계 output-dir>/case_id_linkage.csv" \
  --output-dir "<결과를 쓸 경로>"
```

전체 실행 산출물: `search_readiness_summary.json`, `case_search_readiness.csv`. `--only-group` 실행은 `group_partial_*.json`을 만든다. 현재 코드는 `search_readiness_report.md`를 생성하지 않는다.

현재는 `TRAIN:DAMAGE_PART`와 `VALIDATION:DAMAGE_PART` 라벨만 파싱한다. 그룹·샤드 단위로 나눠 돌린 뒤 병합할 수 있다.

```bash
# 그룹 하나만, 20개 샤드 중 0번
python pipeline/jobs/corpus/validate_search_readiness.py ... --only-group TRAIN:DAMAGE_PART --shard-index 0 --shard-count 20

# 샤드 산출물을 병합해 최종 리포트 생성 (스캔 생략)
python pipeline/jobs/corpus/validate_search_readiness.py ... --merge-only
```

현재 샤드 산출물에는 사례별 라벨·orphan 건수가 빠져 있어 `--merge-only` 결과의 해당 건수 열이 0으로 출력된다. geometry/part-code 집합은 병합되지만 건수 검증은 전체 실행 결과와 대조해야 한다.

`--catalog-path`는 생략하면 `pipeline/`으로 잡힌다. 저장소 밖에서 실행할 때만 지정한다.

### 3. 견적 원본 부품명 집계

```bash
python pipeline/jobs/estimates/extract_estimate_labels.py \
  --estimate-dir "<subset>/1.Training/1.원천데이터_230126_add/TS_99. 붙임_견적서" \
  --output "<결과를 쓸 경로>/estimate_label_audit.json"
```

파일명 접두사 `as-`/`sc-`로 견적 포맷을 구분해 원본명별 등장 횟수를 포맷별로 나눠 센다.

### 4. 표준 부위 코드 매핑

```bash
python pipeline/jobs/estimates/map_estimate_labels.py \
  --audit-json "<3단계 산출물>/estimate_label_audit.json" \
  --output "<결과를 쓸 경로>/estimate_label_mapped.json"
```

결과 요약이 stdout으로 출력된다. 상태별 원본명 수와 수리 항목 수를 함께 낸다.

| 상태 | 의미 |
|---|---|
| `MAPPED` | YOLO 32종 표준 부품으로 자동 연결 |
| `MAPPED_EXTENDED` | 32종 밖, 견적 전용 확장 코드로 연결 |
| `MAPPED_SIDE_UNKNOWN` | 표준 코드는 확정, 좌·우만 미상 |
| `MAPPED_SPLIT` | 한 항목에 좌·우가 함께 기재되어 두 코드로 분리 |
| `NOT_A_PART` | 부품이 아닌 비용·서비스 항목 |
| `REVIEW_CONFLICT` | 둘 이상의 부품 계열이 겹쳐 사람 확인 필요 |
| `OUT_OF_SCOPE_PART` | 실제 부품이나 현재 코드 범위 밖 |

확장 매핑 규칙은 현재 이 스크립트가 정의하며 DB 마스터 seed에는 확장 24종이 반영돼 있다. 사진 정규화용 `shared/vision/catalog.py`의 32종과 DB 56종은 용도가 다르다. 코드 집합 일치 검증과 `mapping_rule_version` 관리는 후속 작업이다.

### 5. 견적 이상치 플래그

```bash
python pipeline/jobs/estimates/flag_estimate_outliers.py \
  --estimate-dir "<subset>/1.Training/1.원천데이터_230126_add/TS_99. 붙임_견적서" \
  --output-dir "<결과를 쓸 경로>"
```

산출물: `outlier_flags.csv`, `outlier_summary.json`

사고당 수리 항목 수와 최종금액에 대해 `Q3 + multiplier x IQR` 초과 건을 기록한다. EDA 방침에 따라 **상한을 삭제하지 않고 검수 대상 목록만 만든다.**

기본 대상은 `as-` 포맷이다. `as-`의 최종금액은 `총계`, `sc-`는 `청구액`으로 금액 정의가 달라 한 분포로 섞으면 사분위수가 왜곡된다. `--source sc`로 따로 돌린다.

`--iqr-multiplier`로 배수를 바꾼다(기본 1.5). as- 57,004건 기준으로 1.5는 6,007건, 2.0은 4,242건, 3.0은 2,484건이 걸린다.

파일 수가 많아 한 번에 돌기 어려우면 나눠 스캔한 뒤 합친다.

```bash
python pipeline/jobs/estimates/flag_estimate_outliers.py ... --shard-index 0 --shard-count 2
python pipeline/jobs/estimates/flag_estimate_outliers.py ... --shard-index 1 --shard-count 2
python pipeline/jobs/estimates/flag_estimate_outliers.py ... --merge-only
```

배수는 스캔이 아니라 병합 단계에서 적용된다. `--output-dir`의 `case_metrics__shard*.json`을 남겨두면 배수를 바꿀 때 재스캔 없이 `--merge-only`만 다시 돌리면 된다. 사용한 배수와 그때의 사분위수·상한은 `outlier_summary.json`에 기록된다.

### 6. part_name_mapping seed 생성

```bash
python pipeline/jobs/estimates/generate_part_name_mapping_seed.py \
  --workbook "<표준화 매핑 워크북>.xlsx" \
  --output "<결과를 쓸 경로>/A307_PART_NAME_MAPPING_SEED.sql"
```

확정 매핑(`MAPPED`, `MAPPED_EXTENDED`)만 포함한다. 사람 확인이 필요하거나 범위 밖인 항목은 억지로 확정하지 않고 제외한다. `--workbook` 대신 `--workbook-dir`을 주면 그 폴더에서 가장 최근 수정된 `.xlsx`를 쓴다.

### 7. 검색 테이블 적재 SQL 생성

```bash
python pipeline/jobs/corpus/build_search_sample_sql.py \
  --subset-root "<AI-Hub 견적서 보유 subset 경로>" \
  --readiness-csv "<2단계 output-dir>/case_search_readiness.csv" \
  --mapping-workbook "<표준화 매핑 워크북>.xlsx" \
  --output-dir "<결과를 쓸 경로>" \
  --sample-cases 10 \
  --source as
```

산출물: `A307_SEARCH_SAMPLE_<N>_LOAD.sql`

`as-` 표본은 `총계`와 각 항목의 부품비·공임을 적재한다. 작업 유형이 없는 `신품가` 행은 공임 작업에 합치지 않고 `line_type=REFERENCE_PRICE`인 별도 행으로 보존한다. 신품가는 청구 부품비와 다를 수 있어 `reference_part_price`에만 보관하며 `item_total`에는 더하지 않는다. 작업 어휘는 `standardization.ESTIMATE_WORKS`를 사용한다. `WORK`·`PART_PRICE`·`REFERENCE_PRICE`·`ANCILLARY` 4종을 구분하며 불인정 상태와 도장 재료비는 별도 컬럼으로 보존한다.

위 정산 제외는 계약이다. 현재 생성기의 `estimate_item_costs()`는 참고가 행에 원천 비용 성분이 있으면 `item_total`을 만들 수 있어 계약을 완전히 강제하지 않는다. 또한 항목 INSERT의 재실행 중복 방지가 없고, 라벨 전체 탐색으로 `damage` 이미지가 섞일 수 있다. 일회성 비용 표본 도구로 취급하며 검색 코퍼스 적재에는 11번 loader를 사용한다.

`sc-`는 `--source sc`로 별도 생성한다. 손해사정 전 부품비·공임을 공통 비용값으로 두고, 손해사정 후 값은 별도 열에 보존한다. 청구액·지급액도 사례 헤더에 저장하되, `as-총계`와는 검증 전까지 한 수리비 분포로 합치지 않는다. ROI 임베딩은 두 표본 모두 적재하지 않는다.

표준 `part_code`가 확인된 항목만 넣고, 확정할 수 없는 항목은 버리지 않고 `data_validation_error`에 남긴다. 오류가 하나라도 있으면 `batch_job_execution.status`가 `PARTIAL`로 기록된다.

`--sample-cases`로 건수를 조절한다. 표본 검증을 끝낸 뒤 늘린다.

기존 로컬 DB에는 `pipeline/sql/002_repair_case_item_contract.sql` 다음 `004_repair_case_item_line_type.sql`을 적용한다. 현재 생성기는 004의 행 종류 4종·손해사정 상태·도장 재료비 컬럼을 사용한다. 신규 DB는 최신 기준 DDL에 반영돼 있다.

### 8. 원천 라벨 스테이징 적재

```bash
python pipeline/jobs/ingestion/load_aihub_damage_dataset.py \
  --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
  --dsn "$DATABASE_URL"
```

`load_search_data.py`에는 `search_dev_cases.csv`만 전달한다. `search_demo_cases.csv`,
`search_eval_cases.csv`, 통합 `search_case_manifest.csv`, `search_mixed_cases.csv`는
검색 DB 적재 대상이 아니다. manifest에 `purpose` 열이 있으면 적재기는 `DEV` 이외의
값을 감지해 실패한다. DEMO/EVAL은 `build_case_query_manifest.py`의 입력으로 사용한다.

`aihub_vehicle_case` / `aihub_vehicle_image` / `aihub_damage_annotation` / `aihub_annotation_repair_method`에 원천 라벨을 그대로 넣는다. 검색 테이블(`repair_case` 계열)과는 별개 계층이다.

`--dry-run`으로 DB 없이 JSON·경로·날짜 포맷만 검증할 수 있다. 날짜는 `MM/DD/YYYY`, `YYYY-MM-DD`, `YYYYMMDD` 세 포맷을 받는다. 파싱하지 못한 값은 조용히 `NULL`로 넘기지 않고, 실행 끝에 원문과 건수를 출력한 뒤 비정상 종료(exit 1)한다.

### 9. 원천 견적 JSON Raw 전수 적재

```bash
python pipeline/jobs/ingestion/load_estimate_raw.py \
  --estimate-root "<TS_99. 붙임_견적서 경로>" \
  --dsn "$DATABASE_URL" \
  --output-dir "<저장소 밖 결과 경로>"
```

원천 견적 JSON을 `aihub_estimate_raw`에 원문 그대로 넣는다. 파일명 stem이 `external_ref`이고 `as-`는 `AIHUB_AS`, `sc-`는 `AIHUB_SC`다. 파일 단위로 읽어 `--commit-every` 배치마다 커밋하므로 payload를 전부 메모리에 쌓지 않는다.

`ON CONFLICT (source, external_ref) DO NOTHING`이다. `loaded_at`을 "원문을 처음 확보한 시점"으로 두기 위한 선택이며, `DO UPDATE`면 재실행마다 덮여 최초 확보 시점과 재개·재적재 구분을 잃는다. 원천이 실제로 갱신되면 새 배포본이므로 조용한 덮어쓰기로 처리하지 않는다.

**중단 후 재개는 같은 명령을 다시 실행하면 된다.** 시작할 때 적재된 `(source, external_ref)`를 읽어 해당 파일은 읽기 자체를 건너뛰고, PK와 `ON CONFLICT`가 중복을 막는다. 실측 — 1,001건 적재 후 재실행하면 `inserted=0 skipped=1001`로 건수가 변하지 않고, 500건만 남긴 상태에서 재실행하면 `inserted=501 skipped=500`으로 채워지며 중복은 0건이다.

깨진 JSON·인코딩 오류·읽기 실패·`as-`/`sc-` 아닌 파일명은 건너뛰지 않고 `data_validation_error`와 격리 manifest CSV에 `source_file`과 함께 남긴다. 원천 파일은 지우지 않는다. 격리 건이 있으면 `batch_job_execution.status`가 `PARTIAL`이 되고 종료코드 1로 끝난다.

다건 INSERT를 쓴다. 표본 3,000건 실측에서 `executemany` 4,778건/초 · COPY→임시테이블 4,808건/초로 차이가 노이즈 수준이었고, 같은 표본의 파일 읽기가 281건/초여서 병목이 DB가 아니라 파일 I/O였다.

`--shard-index`/`--shard-count`로 나눠 병렬 실행한 뒤 `--merge-only`로 산출물을 합칠 수 있다. 병목이 파일 I/O라 샤드 병렬이 실제로 시간을 줄인다.

### 10. 라벨 경로 인덱스 생성

```bash
python pipeline/jobs/corpus/build_label_path_index.py \
  --subset-root "<01.데이터_견적서보유 경로>" \
  --output-dir "<저장소 밖 결과 경로>"
```

산출물 `label_path_index.csv`의 컬럼은 `external_ref` / `dataset_split` / `label_type` / `source_image_id` / `file_name` / `label_path` / `image_path` / `image_exists`다. 이후 `aihub_vehicle_image` 적재의 입력이라 컬럼 이름을 그 테이블에 맞췄고, `image_path`만 이름이 다르다(테이블은 `file_path`). `image_exists`는 테이블 컬럼이 아니라 적재 전에 orphan 라벨을 걸러내는 판정값이다.

`source_image_id`는 **라벨 파일명 접두**를 쓴다. 라벨 내용의 `images.id`는 표본 3,000건에서 전부 `1`이라 식별자로 쓸 수 없다. `load_aihub_damage_dataset.py`는 `images.id`를 그 컬럼에 넣으므로 `aihub_vehicle_image.source_image_id`에는 현재 1만 들어간다.

라벨 파일명은 `<image_id>_<external_ref>.json`이다. **파일명만 믿지 않는다** — 파일명에서 뽑은 `external_ref`가 라벨 내용의 `categories.id`·`annotation.category_id`와 다르거나, 내용에 참조가 없거나 2종 이상이면 인덱스에 넣지 않고 격리한다.

내용 추출은 `validate_category_id_integrity.py`와 같은 정규식 fast scan이다. 라벨 JSON에 segmentation 폴리곤이 들어 있어 전부 파싱하면 필요 없는 좌표 배열까지 객체로 만든다. 4개 그룹 1,600건에서 정규식과 `json.loads` 결과가 `images.file_name`·사례 참조 집합 모두 일치하는 것을 확인했다.

인덱스 행을 메모리에 모아 마지막에 CSV로 쓴다. 라벨 457,670건 전수 실행 중 관측한 프로세스 메모리는 약 730MB다(실행 중간 시점 관측이며 최종 피크는 재지 않았다). 메모리가 빠듯한 환경에서는 `--shard-count`로 나눠 돌린 뒤 `--merge-only`로 합친다.

`validate_category_id_integrity.py`가 이미 같은 트리를 걷지만 새 스크립트로 뒀다. 그 스크립트는 검색 가능 사고 후보 수를 확정하는 검증 게이트이고, 사례 참조를 **이미지** 파일명에서 유도해 이 인덱스가 요구하는 라벨 파일명 대조를 하지 않으며, `case_id_linkage.csv`는 사례 단위 집계라 파일 단위 경로가 없다. 트리를 두 번 걷는 비용은 일회성이고 `--shard-count`로 나눌 수 있다.

### 11. 검색 기본 데이터 적재

현재 `load_search_data.py`는 `DAMAGE_PART`를 공식 검색 이미지로 적재하고,
`DAMAGE`는 선택적 참고 이미지로 적재한다. 기존 55,363건 readiness는 이전 정책
기록이며 새 readiness를 재생성해야 한다. 같은 이미지의 damage-part geometry 매칭이
명확한 ROI는 strict, 매칭 없음·모호한 ROI는 vector-only로 summary에 남긴다.

`load_search_data.py`는 견적 수리항목 → `part_name_mapping` →
`repair_case_item.part_code`를 함께 적재한다. 원천 배열 순번을 `source_item_key`로
사용해 재실행 시 같은 항목을 upsert한다. 부품 코드 결측·작업 유형 오류는 항목을 임의로
채우지 않고 `data_validation_error`에 남긴다.

따라서 아래 명령은 **새 damage_part 중심 loader의 실행 예**다. readiness, 사례 split
manifest, ROI metadata adapter를 같은 정책으로 생성한 뒤 사용한다. 설계와 migration은
[damage_part 중심 유사 사례 검색 스키마](../Docs/Erd/A307_DAMAGE_SEARCH_SCHEMA.md)를 본다.

구현 후에는 readiness에 견적 경로와 매핑 워크북을 함께 넘겨 사례 부품 후보 상태를
만들고, 006 migration 적용 뒤 정식 loader를 실행한다.

```bash
python pipeline/jobs/corpus/validate_search_readiness.py \
  --subset-root "<01.데이터_견적서보유 경로>" \
  --linkage-csv "<category integrity output>/case_id_linkage.csv" \
  --estimate-root "<견적 JSON 경로>" \
  --mapping-workbook "<표준화 매핑 워크북>.xlsx" \
  --output-dir "<저장소 밖 readiness output>"

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f pipeline/sql/006_damage_search_corpus.sql

python pipeline/jobs/ingestion/load_search_data.py \
  --subset-root "<01.데이터_견적서보유 경로>" \
  --readiness-csv "<저장소 밖 readiness output>/case_search_readiness.csv" \
  --mapping-workbook "<표준화 매핑 워크북>.xlsx" \
  --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
  --dsn "$DATABASE_URL" \
  --limit 1000
```

```bash
python pipeline/jobs/ingestion/load_search_data.py \
  --subset-root "<01.데이터_견적서보유 경로>" \
  --readiness-csv "<검색 준비도 output-dir>/case_search_readiness.csv" \
  --mapping-workbook "<표준화 매핑 워크북>.xlsx" \
  --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
  --dsn "$DATABASE_URL" \
  --limit 1000
```

표본 검증을 통과한 뒤 `--limit`을 제거해 전수 적재한다. `part_code`와
`part_name_mapping`도 같은 실행에서 seed/upsert하며, `(source, external_ref)`와
`source_image_ref`를 conflict key로 사용해 재실행해도 중복이 생기지 않는다.
`source_image_ref`는 AI-Hub 원본 상대 경로로 보존하고, `storage_key`는
`repair-cases/{source}/{external_ref}/{source_image_id}/{variant}.{ext}` 규칙으로
생성한다. `source_image_id`는 원본 파일명 `<image_id>_<external_ref>.<ext>`의
숫자 접두를 문자열 그대로 보존한다. 예를 들어
`repair-cases/AIHUB_AS/as-0000160/0406472/original.jpg`와 같다.
`--dataset-root`는 원본 상대 경로의 기준만 결정하며 S3 key 자체에는 포함되지 않는다.

### DAMAGE corpus feature 실험

현재 공식 feature pipeline v1은 DAMAGE_PART의 same-image geometry pairing을 사용한다.
DAMAGE 이미지를 별도 비교군으로 만들 때는 007/008의 사전 조건과 상태를 먼저 확인한 뒤
`pipeline/sql/010_damage_repair_hint.sql`과 `pipeline/sql/011_damage_pipeline_v2.sql`을 적용하고,
`load_search_data.py`에
`--include-damage-reference`를 지정해 DAMAGE 이미지를 적재한다. 그 다음 아래 job으로
DAMAGE bbox/polygon feature를 v2에 만든다.

```bash
python pipeline/jobs/ingestion/load_damage_features.py \
  --subset-root "<견적서 보유 subset>" \
  --dataset-root "<전체 데이터셋>/01.데이터" \
  --readiness-csv "<readiness output>/case_search_readiness.csv" \
  --case-manifest "<manifest output>/search_dev_cases.csv" \
  --pipeline-version-id 2 \
  --dry-run
```

이 job은 DAMAGE feature를 `part_code=NULL`, `pair_status=UNPAIRED`로 저장한다.
annotation의 `repair` 부품 후보는 `repair_case_damage_feature_part_hint`에만 저장하며,
검색 STRICT 필터나 견적 산출에는 사용하지 않는다. v2는 자동 활성화하지 않고, 동일한
embedding model로 v1/v2를 각각 색인한 뒤 validation query의 Recall@K·MRR·VECTOR_ONLY
비율·비용 연결 성공률을 비교한다. 두 pipeline version의 embedding을 한 활성 검색 결과에
섞지 않는다.
검색 이미지 원천은 `TL_damage_part`/`VL_damage_part`의 `damage_part` 디렉터리로
고정한다. `TL_damage`/`VL_damage`의 `damage` 이미지는 동일 사진 짝이 아니므로
라벨을 전파하지 않으며, 필요할 때만 참고 이미지로 함께 적재한다.

### DAMAGE_PART/DAMAGE corpus 임베딩

feature 적재 후에는 한 번에 하나의 `pipeline-version-id`만 지정해 임베딩한다.
v1과 v2는 같은 `EmbeddingSpec`의 DINOv2 모델을 사용하지만 서로 다른 pipeline으로
조회하며, 하나의 활성 검색 corpus에 섞지 않는다. `--dry-run`은 DB를 읽기만 하고
이미지·ROI 재현 가능 여부와 예상 대상 수만 확인하며 모델 추론과 upsert를 하지 않는다.
DB 접속 문자열은 `DATABASE_URL`만 사용한다.

```bash
# v1 DAMAGE_PART 대상 확인 (모델 추론/DB 쓰기 없음)
python pipeline/jobs/ingestion/embed_search_corpus.py \
  --pipeline-version-id 1 \
  --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
  --limit 100 \
  --dry-run

# v2 DAMAGE 대상 확인
python pipeline/jobs/ingestion/embed_search_corpus.py \
  --pipeline-version-id 2 \
  --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
  --limit 100 \
  --dry-run

# 검증된 소량 샘플 임베딩
python pipeline/jobs/ingestion/embed_search_corpus.py \
  --pipeline-version-id 1 \
  --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
  --limit 100 --batch-size 16 --resume

# 전수 실행: v1/v2를 별도 실행
python pipeline/jobs/ingestion/embed_search_corpus.py \
  --pipeline-version-id 1 \
  --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
  --batch-size 32 --resume
python pipeline/jobs/ingestion/embed_search_corpus.py \
  --pipeline-version-id 2 \
  --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
  --batch-size 32 --resume
```

실행 결과는 DSN을 포함하지 않는 JSON으로 출력되며 pipeline/model version, 대상·성공·실패·
skip·기존 upsert 수를 포함한다. 이미지 누락·ROI 오류·개별 추론 오류는 feature 단위로
격리하지만, DB 계약 오류나 `EmbeddingSpec`과 DB 모델 버전 불일치는 즉시 실패한다.

개발·시연·평가용 subset을 적재할 때는 전체 readiness와 사례 manifest를 함께 넘긴다.
manifest도 `case_id` 단위라 한 사례의 이미지는 모두 같은 subset에 남는다.
readiness와 사례 manifest는 재생성 가능한 실행 산출물이므로 저장소 밖에 둔다.
아래 `<manifest output>`은 `build_case_split_manifests.py`의 `--output-dir` 값이다.

```bash
python pipeline/jobs/ingestion/load_search_data.py \
  --subset-root "<01.데이터_견적서보유 경로>" \
  --readiness-csv "<manifest output>/case_search_readiness.csv" \
  --case-manifest "<manifest output>/search_dev_cases.csv" \
  --mapping-seed-sql "Docs/Erd/A307_part_name_mapping_seed.sql" \
  --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
  --dsn "$DATABASE_URL"
```

`search_demo_cases.csv`와 `search_eval_cases.csv`도 같은 방식으로 지정한다.
`search_mixed_cases.csv`는 TRAIN·VALIDATION 양쪽에 걸친 사례의 감사용 목록이며,
정량 평가 subset에는 사용하지 않는다.

시연·검색 검증에서 AI-Hub 이미지를 사용자 입력처럼 넣고 싶을 때는
`search_demo_cases.csv`를 DB에 적재하지 않는다. 다음 query manifest를 사용해
원본 JPG를 읽어 사고 이미지 업로드 경로(`accidents/...`)로 전달한다. query 사례는
DEV 검색 DB의 사례와 다른 `case_id`여야 자기 자신이 검색되는 것을 막을 수 있다.

```bash
python pipeline/jobs/corpus/build_case_query_manifest.py \
  --subset-root "<01.데이터_견적서보유 경로>" \
  --case-manifest "<manifest output>/search_demo_cases.csv" \
  --dataset-root "<AI-Hub 데이터셋 기준 경로>" \
  --output "<저장소 밖 query output>/search_demo_query_images.csv"
```

manifest의 `source_image_ref`를 `<AI-Hub 데이터셋 기준 경로>`와 결합해 파일을
읽는다. 이 파일은 `repair_case`·`repair_case_image`에 넣지 않고 사용자 업로드
입력으로만 사용한다.

### 사례 단위 subset manifest 생성

개발·시연·평가용 사례 목록은 이미지가 아니라 `case_id` 단위로 만든다. 한 사례가
TRAIN과 VALIDATION 양쪽에 있으면 `MIXED`로 표시하고 EVAL에서 제외한다.

```bash
python pipeline/jobs/corpus/build_case_split_manifests.py \
  --subset-root "<01.데이터_견적서보유 경로>" \
  --readiness-csv "<readiness output>/case_search_readiness.csv" \
  --linkage-csv "<category integrity output>/case_id_linkage.csv" \
  --output-dir "<저장소 밖 manifest output>" \
  --dev-cases 1000 \
  --demo-cases 20 \
  --seed "a307-search-cases-v1"
```

출력되는 `search_case_manifest.csv`에는 사례별 원본 split, DEV/DEMO/EVAL 목적,
차급·차종·부품 코드·손상 유형·이미지 수를 기록한다. `search_dev_cases.csv`,
`search_demo_cases.csv`, `search_eval_cases.csv`는 적재 시 `--case-manifest`에
지정하는 subset 목록이다. 원본 이미지와 JSON은 저장소 밖에 둔다.

### 12. 전수 적재 전 표본 검증

표본을 다시 upsert한 뒤 다음 검증기를 실행한다. `--before-*`에는 표본 적재
직전의 현재 건수를 넣어 재실행 후 건수 불변도 확인한다.

```bash
python pipeline/jobs/corpus/verify_search_sample.py \
  --dsn "$DATABASE_URL" \
  --readiness-csv "<검색 준비도 output-dir>/case_search_readiness.csv" \
  --sample-cases 1000 \
  --expected-raw-cases 125006 \
  --before-case-count 0 \
  --before-image-count 0
```

검증 항목은 중복 `(source, external_ref)`, 표본 `case_id` 누락, 허용 차급 위반·결측,
이미지 연결 오류, 전체 경로 기준 `source_image_ref` 중복, `repair-cases/...` key 규칙,
Raw 원천 건수와 검색 적재 범위 차이, Raw–`repair_case` 연결, 재실행 후 건수 변화다.
Raw와 검색 범위가 다르므로
미연결 Raw 건수 자체는 오류로 처리하지 않는다. 현재 검증기의 예상값은 실제 `repair_case` 건수에서 계산하며, `125,006 - 55,363 = 69,643`이라는 정책상 제외분을 독립적으로 강제하지 않는다. 전수 완료 판정에는 readiness 전체 키 집합과 실제 적재 키 집합을 별도로 대조해야 한다.

### 13. 견적 행 검증

`validate_estimate_rules.py`는 `validation/estimate_rules.py`로 행 종류·작업 코드·비용을 검사하고 행 JSONL·오류 JSONL·summary를 만든다. 현재 develop에 있지만 DB 적재 배치와 연결되지 않았다. 입력 명령, 매핑 JSON 형식 오류, 비용 검증 상태 및 최신 작업 코드 계약과의 차이는 [비용 DB 인계](../Docs/Erd/A307_COST_DB_HANDOVER.md)를 본다.

### 공통

원천 데이터와 실행 산출물은 저장소 밖에 둔다. `--output-dir`은 저장소 바깥 경로를 지정한다.

## 실행 환경

Python 3.10 이상.

```bash
pip install -r pipeline/requirements.txt
```

`requirements.txt`에는 `psycopg`(적재), `pandas`·`openpyxl`(워크북·CSV 읽기)이 선언돼 있다. ROI 모듈이 사용하는 Pillow는 현재 누락돼 있다. `standardization` 최상위 import도 ROI를 불러오므로 Pillow가 없으면 정규화·견적 검증 import가 실패한다. 의존성 파일 수정 전에는 `pip install Pillow`가 추가로 필요하다.

## 테스트

저장소 루트에서 실행한다.

```bash
python -m unittest discover -s pipeline/standardization -t pipeline -p "test_*.py"
python -m unittest discover -s pipeline/validation -t pipeline -p "test_*.py"
python -m unittest discover -s pipeline/jobs/corpus/tests -t . -p "test_*.py"
```

2026-09-10 Python 3.12/Pillow 12.3.0 환경에서 80개 중 79개 통과, 1개 실패했다. 실패 1건은 사례 분할 테스트 입력의 ID 중복 문제다.

## 로컬 DB

`docker-compose.yml`의 `a307-db`(pgvector/pg16)에 빈 DB를 만들 때 아래 순서를 지킨다.

```
1) Docs/Erd/A307_ddl_final.sql                       기준 DDL
2) pipeline/sql/002_repair_case_item_contract.sql    기존 DB만. 신규는 1)에 이미 반영됨
3) pipeline/sql/003_aihub_staging.sql                원천 스테이징 계층
4) pipeline/sql/004_repair_case_item_line_type.sql   기존 DB만. 신규는 1)에 이미 반영됨
5) pipeline/sql/006_damage_search_corpus.sql         기존 DB만. 신규는 1)에 이미 반영됨
6) pipeline/sql/007_damage_feature_layer.sql          feature layer 전환 (사전 점검 필수)
7) pipeline/sql/008_damage_type_standard_code.sql    damage code 표준화 (사전 점검 필수)
8) pipeline/sql/010_damage_repair_hint.sql            DAMAGE repair hint 테이블
9) pipeline/sql/011_damage_pipeline_v2.sql            비활성 DAMAGE pipeline v2
10) Docs/Erd/A307_part_code_seed.sql                  part_code 56종
11) Docs/Erd/A307_part_name_mapping_seed.sql          part_name_mapping 15,308행
```

`007`과 `008`은 기존 feature/embedding 메타데이터를 재구성하는 migration이다.
따라서 기존 `repair_case_damage_feature` 또는 `repair_case_roi_embedding`에 데이터가
있으면 임의로 삭제하지 말고 먼저 중단한다. 아래 조회는 읽기 전용이며 DSN은
`DATABASE_URL` 환경변수에서만 읽는다.

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 <<'SQL'
SELECT table_name
  FROM information_schema.tables
 WHERE table_schema = 'public'
   AND table_name IN (
     'feature_pipeline_version', 'repair_case_damage_feature',
     'repair_case_roi_embedding', 'repair_case_damage_feature_part_hint'
   )
 ORDER BY table_name;

SELECT 'feature_pipeline_version' AS table_name, COUNT(*) AS row_count
  FROM feature_pipeline_version
UNION ALL
SELECT 'repair_case_damage_feature', COUNT(*)
  FROM repair_case_damage_feature
UNION ALL
SELECT 'repair_case_roi_embedding', COUNT(*)
  FROM repair_case_roi_embedding;

SELECT indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename IN (
     'feature_pipeline_version', 'repair_case_damage_feature',
     'repair_case_roi_embedding'
   )
 ORDER BY tablename, indexname;
SQL
```

전제조건을 확인해 007/008의 초기화 조건을 만족할 때만 아래 순서로 적용한다.
활성 pipeline은 별도 SQL로 변경하지 않으며, 011의 v2도 `is_active=false`로 남는다.

```bash
for migration in \
  pipeline/sql/007_damage_feature_layer.sql \
  pipeline/sql/008_damage_type_standard_code.sql \
  pipeline/sql/010_damage_repair_hint.sql \
  pipeline/sql/011_damage_pipeline_v2.sql; do
  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f "$migration"
done
```

적용 후에는 v1/v2의 pipeline id와 v2 계약을 읽기 전용으로 확인한다.

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "
SELECT pipeline_version_id, pipeline_name, version, is_active, params
  FROM feature_pipeline_version
 WHERE pipeline_name = 'a307-damage-search'
 ORDER BY version;
"
```

**8)이 9)보다 반드시 먼저다.** `part_name_mapping.part_code`가 `part_code`를 `ON DELETE RESTRICT`로 참조한다. 순서를 뒤집으면 첫 FK 위반에서 seed 트랜잭션이 중단돼 15,308행이 한 건도 적재되지 않는다.

이미 검색 사례를 적재한 DB에서 기존 PK 기반 `storage_key`를 새 원천 식별자 기반
규칙으로 변경할 때는 `pipeline/sql/009_repair_case_image_stable_storage_key.sql`을
적재 후 한 번 실행한다. 이 migration은 로컬 이미지 파일을 이동하지 않고 DB key만
정리한다. 같은 `source_image_ref`는 DB를 비우고 재적재해도 같은 key를 만든다.

3)은 원천 견적 JSON 원문을 담는 `aihub_estimate_raw`와 AI-Hub 라벨 원천 4종(`aihub_vehicle_case`, `aihub_vehicle_image`, `aihub_damage_annotation`, `aihub_annotation_repair_method`)을 만든다. 전부 `CREATE TABLE IF NOT EXISTS`라 재적용이 안전하다. `aihub_estimate_raw`는 `(source, external_ref)`를 PK로 두어 `ON CONFLICT`로 재실행이 멱등하며, `repair_case`를 FK로 참조하지 않아 Raw 적재가 검색 테이블 적재를 기다리지 않는다.

4)는 2)가 둔 `line_type` 2종(WORK / PART_PRICE)을 4종으로 올리고 손해사정 상태·도장 재료비 컬럼을 추가한다. 2) 다음에 적용한다. 신규 DB는 1)에 이미 반영돼 있다.

기준 ERD는 신규 DB의 정본으로 함께 갱신하고, 검색·적재 DB의 기존 환경에는
`pipeline/sql/` migration을 번호순으로 적용한다. `Docs/Erd/A307_ddl_final.sql`은
백엔드와 공유하는 파일이므로 변경 사항을 백엔드 담당자에게 전달한다.
