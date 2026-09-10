# damage 중심 유사 사례 검색 스키마

기준일: 2026-09-11

## 목적

`damage` 이미지의 손상 ROI를 유사도 검색의 주 데이터로 사용하고,
`damage_part` 이미지는 차량·부품 맥락을 보조하는 자료로 함께 보존한다.
이 문서는 부품명과 손상 유형을 사용해 검색 후보를 줄이되, 사례 부품 정보를
ROI 부품 정답으로 오인하지 않기 위한 데이터 계약이다.

## 정보의 단위와 확정성

| 정보 | 저장 위치 | 단위 | 검색에서의 의미 |
|---|---|---|---|
| 손상 유형·polygon | `repair_case_roi_embedding` | damage ROI | 확정 메타데이터. 하드 필터 가능 |
| ROI 임베딩 | `repair_case_roi_embedding` | damage ROI | 최종 유사도 순위 |
| 견적 부품·수리 항목 | `repair_case_item` | 사고 사례 | 부품 후보. ROI 부품 확정값 아님 |
| 직접 부품 영역 | `repair_case_image_part_annotation` | damage_part 이미지 | 맥락 근거. 다른 이미지 ROI로 자동 전파 금지 |
| 이미지 유형 | `repair_case_image.image_type` | 이미지 | `DAMAGE` 주 검색 / `DAMAGE_PART` 보조 |

`repair` annotation의 부품 문자열은 이 계약의 부품 정본이 아니다. 견적서 원천
수리 항목을 `part_name_mapping`으로 표준화한 `repair_case_item.part_code`를
사례 부품 후보로 사용한다.

견적 항목은 `repair_case_item.source_item_key`(원천 배열 순번)로 사례 안에서
멱등 upsert한다. 이 키는 수리항목을 재적재하기 위한 식별자이며 ROI와 연결하지 않는다.

## 검색 계약

### 필터 순서

```text
1. query damage_type으로 ROI를 하드 필터한다.
2. 부품 조건이 있으면, 같은 case에 해당 part_code의 견적 수리항목이 있는지를
   후보 조건 또는 점수 신호로 사용한다.
3. 남은 DAMAGE ROI에서 동일 embedding model_version으로 벡터 유사도 검색한다.
4. 결과에는 ROI 손상 유형과 사례 부품 후보의 근거 단위를 함께 표시한다.
```

부품 후보 조건은 기본적으로 **soft**다. 부품 조건을 하드 필터로 제공할 수는 있지만,
그 의미는 "이 ROI가 해당 부품"이 아니라 "이 ROI가 속한 사고에 해당 부품 견적이 있음"이다.
필터를 강하게 적용하면 실제 손상 부품이 견적에서 누락된 사례를 검색에서 잃을 수 있다.

### 결과 표현 예

```text
ROI: damage_type=SCRATCHED, part_code=NULL
사례 견적 부품 후보: FRONT_BUMPER, FRONT_DOOR_R

표시 문구:
- 확정 손상 유형: 긁힘
- 사례 견적 부품 후보: 앞 범퍼, 우측 앞문
- ROI 부품: 미확정
```

`repair_case_roi_embedding.part_code`가 `NULL`인 행도 geometry와 embedding이 유효하면
`is_searchable=TRUE`가 될 수 있다. `is_searchable`은 벡터 검색 가능 여부이며,
부품 연결 완전성의 대리값이 아니다.

## ERD 변경

### `repair_case_image`

```text
image_type VARCHAR(20) NOT NULL
  CHECK (image_type IN ('DAMAGE', 'DAMAGE_PART'))
source_dataset_split VARCHAR(20) NULL
  CHECK (source_dataset_split IN ('TRAIN', 'VALIDATION'))
```

- `DAMAGE`: 손상 ROI를 만들고 주 검색 대상으로 사용한다.
- `DAMAGE_PART`: 보조 이미지다. 주 ROI 검색 색인 대상이 아니다.
- `source_dataset_split`은 원천 TRAIN/VALIDATION 추적용이다. 서비스 목적 분할인
  DEV/DEMO/EVAL과 혼용하지 않는다.

### `repair_case_roi_embedding`

```text
part_code VARCHAR(50) NULL REFERENCES part_code(part_code)
```

`part_code`에는 해당 ROI와 직접 연결할 수 있는 근거가 있을 때만 값을 넣는다.
사례 견적의 part code, 다른 이미지의 part polygon, annotation `repair` 결과를 이
컬럼에 복사하지 않는다.

`damage_type`은 ROI의 직접 annotation이므로 유지한다. 검색용 인덱스에는 기존
`(part_code, damage_type)` 인덱스와 별도로 `(damage_type, model_version_id)`
부분 인덱스를 둔다.

### `repair_case_image_part_annotation`

```text
case_image_part_annotation_id BIGSERIAL PRIMARY KEY
case_image_id                 BIGINT NOT NULL FK repair_case_image
part_code                     VARCHAR(50) NOT NULL FK part_code
source_annotation_ref         VARCHAR(255) NOT NULL
part_polygon                  JSONB NULL
created_at                    TIMESTAMPTZ NOT NULL
UNIQUE (case_image_id, source_annotation_ref)
```

이 테이블은 `DAMAGE_PART` 이미지에 직접 라벨된 부품 영역을 감사·표시·후속 연구에
보존한다. `case_image_id`가 `DAMAGE_PART`를 가리키는지는 적재기가 검증한다.
교차 테이블 CHECK로 다른 테이블의 `image_type`까지 검사할 수 없으므로, 적재 전
검증과 테스트에서 불변식으로 보장한다.

## 변경하지 않는 테이블

- `repair_case_item`: 견적 기반 사례 부품 후보와 비용 항목을 계속 보존한다.
- `repair_cost_stat`: 부품·손상 유형별 비용 통계다. ROI 부품 확정성 문제와 분리한다.
- `damaged_part`: 서비스 사용자의 사고 이미지에 대한 모델 추론 결과다. 서비스 모델이
  부품을 예측한다는 현재 계약을 유지하므로 `part_code NOT NULL`을 바꾸지 않는다.

## 적재 불변식

1. 한 `case_id`의 damage·damage_part 이미지는 동일한 서비스 subset에 배정한다.
2. 원천 TRAIN/VALIDATION 판정은 두 image type을 합쳐 case 단위로 한다.
3. `DAMAGE` ROI의 `part_code=NULL`은 정상값이다.
4. `DAMAGE_PART`의 부품 polygon은 해당 이미지의 근거일 뿐, 다른 이미지의 ROI에
   좌표 또는 부품 코드를 전파하지 않는다.
5. S3 업로드는 새 DB 이미지 레코드의 최신 `storage_key`를 기준으로 한다.

## 적용 순서

1. 기존 DB에 `pipeline/sql/006_damage_search_corpus.sql`을 적용한다.
2. 기존 `repair_case_image` 행은 `DAMAGE_PART`로 backfill한다.
3. loader와 readiness·split manifest가 두 image type을 읽고, DAMAGE ROI의
   geometry-only 적재를 허용하도록 수정한다.
4. 견적 수리항목을 `repair_case_item`에 적재하고, 매핑 성공·결측·모호 항목 수를 실행
   summary에 남긴다. `load_search_data.py`는 원천 배열 순번을 `source_item_key`로 사용해
   멱등 upsert한다.
5. 새 manifest와 DB 이미지 레코드를 확정한 뒤 S3에 최신 `storage_key`로 업로드한다.
6. damage type만 적용한 검색과 부품 후보 조건을 적용한 검색을 같은 평가 집합으로
   비교한 뒤, 부품 조건의 기본값을 결정한다.

## 검증 상태

Validation 원천의 `sc-104422`에서 DAMAGE의 damage type과 DAMAGE_PART의 직접 part는
각각 확인됐다. 사용한 원천 폴더에는 견적 JSON이 없어 견적 부품 후보의 실제 매핑률과
필터 후보 수는 아직 측정하지 못했다. loader 구현은 완료됐지만, 견적 원천으로 실행해
위 6단계의 필터 품질 비교를 수행해야 한다.
