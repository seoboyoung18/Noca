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

| 스크립트 | 역할 |
|---|---|
| `validate_category_id_integrity.py` | `category_id` 기준 이미지·라벨·견적 조인 무결성 검증. orphan 라벨을 격리하고 검색 가능 사고 후보를 산출한다 |

```bash
python pipeline/jobs/validate_category_id_integrity.py \
  --subset-root "<AI-Hub 견적서 보유 subset 경로>" \
  --output-dir "<결과를 쓸 경로>"
```

산출물: `validation_summary.json`, `batch_job_execution.json`, `data_validation_error.jsonl`, `quarantine_manifest.csv`, `category_id_integrity.csv`, `case_id_linkage.csv`

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
