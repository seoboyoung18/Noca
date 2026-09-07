# 데이터 파이프라인

유사 수리 사례 검색용 데이터 파이프라인. YOLO 출력과 AI-Hub 차량파손 데이터셋을 표준 코드로 정규화하고, 검색 대상 사례를 검증·적재한다.

## 구조

```
pipeline/
├── standardization/   표준 코드 정의와 정규화 모듈 (런타임 공용)
├── jobs/              배치 스크립트
├── sql/               스키마 DDL·migration (후속 이슈에서 추가)
└── requirements.txt
```

## standardization

파이프라인과 서비스 추론 경로가 함께 쓰는 유일한 표준 코드 모듈이다.

| 파일 | 역할 |
|---|---|
| `catalog.py` | 표준 부품 32종, 손상 4종, 작업 4종 정의 |
| `normalizer.py` | 정규화 및 `NormalizationError` 격리 |
| `raw_yolo_schema.json` | YOLO raw 출력 입력 계약 |
| `common_schema.json` | 정규화 이후 downstream 출력 계약 |
| `test_normalizer.py` | 회귀 테스트 |
| `README.md` | 코드값·좌표계 등 계약 결정사항 |

사용 예:

```python
from standardization import PARTS, normalize_inference, normalize_repair_label, NormalizationError
```

`PARTS`, `DAMAGES`, `WORKS`, `DEFAULT_WORK_BY_DAMAGE`는 패키지 최상위에서 바로 import한다. `standardization.catalog`를 직접 참조하지 않는다.

## jobs

두 갈래로 나뉜다. 각 갈래 안에서는 앞 단계 산출물을 뒤 단계가 입력으로 받으므로 순서대로 실행한다.

**A. 검색 대상 확정** — 이미지·라벨 기준

| 순서 | 스크립트 | 역할 |
|---|---|---|
| 1 | `validate_category_id_integrity.py` | `category_id` 기준 이미지·라벨·견적 조인 무결성 검증. orphan 라벨을 격리하고 검색 가능 사고 **후보**를 산출 |
| 2 | `validate_search_readiness.py` | 1의 후보에 polygon 유효성과 표준 부품 코드 확보 여부를 추가 판정해 **최종 검색 가능 사고 수**를 확정 |

**B. 견적 부품명 표준화** — 견적서 텍스트 기준

| 순서 | 스크립트 | 역할 |
|---|---|---|
| 3 | `extract_estimate_labels.py` | 견적 JSON에서 원본 부품명·작업명을 집계 |
| 4 | `map_estimate_labels.py` | 원본명을 표준 부위 코드로 매핑. 판단이 필요한 항목은 `REVIEW_CONFLICT` / `OUT_OF_SCOPE_PART`로 분류해 남김 |

A는 사진 라벨(YOLO 32종), B는 견적서 한글 텍스트를 다룬다. 두 결과를 교차 검증해 합치는 작업은 `part_code` 마스터 확정 후로 남아 있다.

### 1. category_id 무결성 검증

```bash
python pipeline/jobs/validate_category_id_integrity.py \
  --subset-root "<AI-Hub 견적서 보유 subset 경로>" \
  --output-dir "<결과를 쓸 경로>"
```

산출물: `validation_summary.json`, `batch_job_execution.json`, `data_validation_error.jsonl`, `quarantine_manifest.csv`, `category_id_integrity.csv`, `case_id_linkage.csv`

### 2. 검색 준비도 검증

1단계가 만든 `case_id_linkage.csv`를 입력으로 받는다.

```bash
python pipeline/jobs/validate_search_readiness.py \
  --subset-root "<AI-Hub 견적서 보유 subset 경로>" \
  --linkage-csv "<1단계 output-dir>/case_id_linkage.csv" \
  --output-dir "<결과를 쓸 경로>"
```

산출물: `search_readiness_summary.json`, `search_readiness_report.md`, `case_search_readiness.csv`, `group_partial_*.json`

라벨 JSON 45만여 개를 전수 파싱하므로 오래 걸린다. 그룹·샤드 단위로 나눠 돌린 뒤 병합할 수 있다.

```bash
# 그룹 하나만, 20개 샤드 중 0번
python pipeline/jobs/validate_search_readiness.py ... --only-group TRAIN:DAMAGE --shard-index 0 --shard-count 20

# 샤드 산출물을 병합해 최종 리포트 생성 (스캔 생략)
python pipeline/jobs/validate_search_readiness.py ... --merge-only
```

`--catalog-path`는 생략하면 `pipeline/`으로 잡힌다. 저장소 밖에서 실행할 때만 지정한다.

### 3. 견적 원본 부품명 집계

```bash
python pipeline/jobs/extract_estimate_labels.py \
  --estimate-dir "<subset>/1.Training/1.원천데이터_230126_add/TS_99. 붙임_견적서" \
  --output "<결과를 쓸 경로>/estimate_label_audit.json"
```

파일명 접두사 `as-`/`sc-`로 견적 포맷을 구분해 원본명별 등장 횟수를 포맷별로 나눠 센다.

### 4. 표준 부위 코드 매핑

```bash
python pipeline/jobs/map_estimate_labels.py \
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

확장 코드는 현재 이 스크립트가 자체 정의한다. `standardization/catalog.py`의 32종과는 별도 어휘이며, `part_code` 마스터로 단일화하는 작업이 남아 있다.

### 공통

원천 데이터와 실행 산출물은 저장소 밖에 둔다. `--output-dir`은 저장소 바깥 경로를 지정한다.

## 실행 환경

Python 3.10 이상.

```bash
pip install -r pipeline/requirements.txt
```

`psycopg` 외 의존성은 없다. 나머지는 모두 표준 라이브러리다.

## 테스트

저장소 루트에서 실행한다.

```bash
python -m unittest discover -s pipeline/standardization -t pipeline -p "test_*.py"
```

## 로컬 DB

로컬 PostgreSQL(pgvector) 구성 절차는 별도 이슈에서 `sql/`과 함께 추가한다.
