# 비용DB 인계 — 견적 원천과 행 검증

기준일: 2026-09-10 · 작성: 김경연(데이터 파이프라인)

원천 견적 데이터는 전수 보존이 끝났다. 이 문서는 **그 위에서 `repair_case_item`을
만드는 사람이 알아야 할 것**만 모았다. 검색 쪽(`repair_case`·`repair_case_image`)은
`A307_SEARCH_LOAD_SCOPE.md`를 본다.

## 1. 지금 쓸 수 있는 것

### 원천 견적 원문 — `aihub_estimate_raw`

```sql
CREATE TABLE aihub_estimate_raw (
    source        varchar(20)  NOT NULL,   -- AIHUB_AS | AIHUB_SC
    external_ref  varchar(50)  NOT NULL,   -- 파일명 stem (as-0000130 등)
    payload       jsonb        NOT NULL,   -- 견적 JSON 원문
    source_file   varchar(500) NOT NULL,
    loaded_at     timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (source, external_ref)
);
```

125,006건 전수(`AIHUB_AS` 57,004 / `AIHUB_SC` 68,002). DDL은
`pipeline/sql/003_aihub_staging.sql`, 적재기는 `pipeline/jobs/load_estimate_raw.py`.

**원문 무손실이다.** 표본 1,001건과 전수 표본 3,049건에서 파싱 객체 `==` 비교로
확인했다. 즉 **원천 JSON을 다시 읽을 이유가 없다.** payload에서 바로 변환하면 된다.

```sql
SELECT payload->'수리내역' FROM aihub_estimate_raw
 WHERE source = 'AIHUB_SC' AND external_ref = 'sc-1000008';
```

payload 최상위 키는 `차량정보` / `수리비 정산정보` / `수리내역` 셋이다.

`loaded_at`은 "원문을 **처음** 확보한 시점"이다. 적재기가 `ON CONFLICT DO NOTHING`이라
재실행해도 갱신되지 않는다. 원천이 실제로 바뀌면 새 배포본이므로 조용히 덮지 않는다.

### 행 검증 CLI — 브랜치에만 있음

`feature/S15P21A307-416-be-estimate-validation-rules` (Draft MR). develop에는 없다.

```bash
python pipeline/jobs/validate_estimate_rules.py \
  --estimate-dir "<TS_99. 붙임_견적서 경로>" \
  --output-dir   "<저장소 밖 경로>" \
  --source all \
  --mapping-json "<부품명 매핑 JSON>"
```

산출물 3종 — `estimate_validation_rows.jsonl` / `estimate_validation_errors.jsonl` /
`estimate_validation_summary.json`. 오류가 나도 행을 버리지 않고 격리해 남긴다.

규칙 5종은 `pipeline/validation/estimate_rules.py`에 있다.

| 규칙 | 내용 |
|---|---|
| `row_classification` | 행 종류 판정 |
| `not_approved_status` | 불인정 상태 |
| `cost_column_reconciliation` | 비용 컬럼 정합 |
| `ancillary_part_code` | 부대비용 행의 부품 코드 |
| `contract_work_code` | 계약 작업 코드 6종 검사 |

**주의 — `--mapping-json`을 넘기지 않으면 `part_code`가 전부 `NULL`로 나온다.**
넘긴 경우에만 매핑을 적용하도록 되어 있다. 매핑 원본은 `part_name_mapping`
seed(15,308건, DB 적재 완료)와 `pipeline/jobs/generate_part_name_mapping_seed.py`다.

## 2. 확정된 것 (414 머지)

- **`line_type` 4종** — `WORK` / `PART_PRICE` / `REFERENCE_PRICE` / `ANCILLARY`.
  AS의 `PART_PRICE`는 정산에 포함되지 않는 참고 정가였고 SC의 것은 정산에 포함되는
  부품 명세였다. 그래서 출처별로 뜻이 달라지는 문제를 없앴다
- **불인정은 `line_type`이 아니라 `assessment_status`** — 원천이 `작업` 필드를 덮어써
  **원래 작업 유형을 복구할 수 없다.** DDL·migration·README에 기록돼 있다
- **도장 재료비를 `paint_material_cost`로 분리** — 출처가 아니라 `작업`이 가른다.
  무작위 3,000건에서 `SUM(도장 행 부품가격)` = 정산.공임.재료대가 AS 100.00%,
  SC(손해사정후) 99.90% 일치
- **`ANCILLARY`를 위해 `part_code` NOT NULL 해제** — `견인비`·`구난료`는 부품이 아니라
  매핑되지 않는다(표본 매핑률 0.0%). 검색 대상 행 종류의 NOT NULL은
  `ck_rci_part_code`가 유지한다
- **`탁송`은 작업 어휘가 아니다** — 원천 1,716,713행에 `작업=탁송`이 0건이고,
  `탁송비`는 부품명으로 나타나며 그 행의 `작업`은 견인 또는 구난이다
- **작업 어휘의 단일 기준은 `standardization.ESTIMATE_WORKS`** — DDL에 6쌍을 열거하지
  않고 구조 검사 + 코드 집합 검사로 대체했다. 어휘를 두 벌로 만들지 않는다

## 3. 미확정 — 이 인계의 본체

### `NOT_APPROVED` 정산 등식

416의 핵심이자 아직 규칙으로 승격하지 않은 것. 표본에서 이 식이 성립했다.

```
항목 합계 + 격리 행 비용 합 = 헤더 합계
```

| 사례 | 헤더 | 항목합 | 차액 | 격리 행 |
|---|---:|---:|---:|---|
| sc-1000008 | 825,969 | 656,450 | 169,519 | 18행 비용합과 일치 |
| sc-1000012 | 1,214,789 | 1,181,420 | 33,369 | 5행 비용합과 일치 |

AS는 별도 불변식이다 — as-0000011에서 작업 행 합계 3,945,120원이 헤더 `공임소계`와
정확히 일치하고 `부품소계`는 0이다. 신품가는 정산에 포함되지 않는다.

**작은 표본에서 성립한 식을 불변식으로 굳히면 되돌리기 어렵다**는 판단으로 규칙 모듈에
넣지 않았다. 확대 표본으로 확인한 뒤 결정한다.

### 나머지

- **`NOT_A_PART` 분류** — 탁송비뿐 아니라 **도장 공통비·가열건조비**가 섞여 있다.
  전체를 `ANCILLARY`로 넘기면 안 된다
- **`item_total` 계산 기준**
- **`work_code`·`part_code` 최종 매핑** — 한글 견적 부품명 ↔ 32종 표준 부위 코드
  매핑 테이블의 소유권과 `mapping_rule_version` 관리는 검색·비용 양쪽이 공유한다
- **`repair_cost_stat`** (P25/P50/P75) 생성

## 4. 이어받을 때 먼저 볼 것

**입력을 파일에서 DB로 바꿀 수 있다.** CLI가 쓰는 것은 `payload.get("수리내역")`뿐이고
그 원문이 `aihub_estimate_raw`에 전수로 있다. 파일 읽기가 **281건/초**로 병목이라
125,006건이면 읽기만 7분이 넘는다. 규칙 모듈은 그대로 재사용된다.

**"DB 비의존"의 근거는 이미 사라졌다.** 모듈 독스트링이 "414 계약 머지 전에 규칙을
테스트하기 위해"라고 적고 있는데 414는 2026-09-09에 머지됐다. 제약을 유지할지 판단이
필요하다.

**완료 조건 두 개가 남아 있다.**

- 표본 10건(AS)·2건(SC)에서 불변식이 전부 성립함을 **배치가 스스로** 확인
- 100건 확대 시 불일치 건이 **오류 테이블로 자동 수집**

지금은 독립 CLI라 적재 배치에 붙어 있지 않다. `data_validation_error` 적재와
`batch_job_execution.summary` 집계가 연결 대상이다.

**단위 테스트 10건은 develop 기준으로 통과한다.** 다만 `CONTRACT_WORK_CODES` 6종과
현재 `ESTIMATE_WORKS`의 교집합을 테스트가 전부 훑는지는 확인하지 않았다.

## 5. 확인해 주세요

**어느 DB에 무엇이 들어 있는지 한 번 맞춰야 한다.** 2026-09-09 작업에서 전수 적재
결과를 별도 DB `a307_raw_full`에 두었고, 2026-09-10에 검색 기본 데이터를 적재했다.
지금 `aihub_estimate_raw` 125,006건이 어느 DB에 있는지 실행 전에 확인한다.

```sql
SELECT current_database(), count(*) FROM aihub_estimate_raw;
```

## 6. 관련 파일

| 파일 | 내용 |
|---|---|
| `pipeline/sql/003_aihub_staging.sql` | Raw·라벨 스테이징 DDL |
| `pipeline/sql/004_repair_case_item_line_type.sql` | 견적 행 종류 재설계 migration |
| `pipeline/jobs/load_estimate_raw.py` | Raw 전수 적재 |
| `pipeline/validation/estimate_rules.py` | 행 검증 규칙 |
| `pipeline/jobs/validate_estimate_rules.py` | 검증 CLI |
| `pipeline/standardization/` | 표준 코드·작업 어휘 단일 기준 |
| `pipeline/README.md` | 로컬 DB 구축 순서와 job 실행법 |
| `Docs/Erd/A307_SEARCH_LOAD_SCOPE.md` | 검색 쪽 적재 범위 |
