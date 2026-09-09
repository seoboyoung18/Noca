# 검색 기본 데이터 적재 범위

기준일: 2026-09-09

## 결정

`repair_case`는 `case_search_readiness.csv`의 `is_final_searchable_case=True`인
사례만 대상으로 한다. 이 판정은 이미지·라벨·견적 조인, 유효 damage geometry,
표준 부품 코드 확보를 모두 통과한 상태다.

| 구분 | 건수 | 처리 |
|---|---:|---|
| 원천 견적 전체 | 125,006 | `aihub_estimate_raw`에 별도 전수 보존 |
| readiness 최종 검색 가능 | 113,184 | `repair_case` 적재 후보 |
| 데이터셋 파일이 없는 견적 | 7,240 | `repair_case`에 넣지 않음. Raw만 보존 |
| 최종 검색 불가 (`125,006 - 113,184`) | 11,822 | `repair_case`에 넣지 않음. 판정 산출물로 보존 |

위의 7,240건은 최종 검색 불가 11,822건에 포함될 수 있으므로 두 행을 합산하지
않는다. readiness 단계의 중간 수치는 이미지·라벨 조인 후보 116,809건이며, 그중
geometry/part-code 검증을 통과한 113,184건을 최종 검색 범위로 사용한다.

실제 적재 job은 후보 중 다음 조건을 추가로 확인한다.

- 라벨에 대응하는 이미지 파일이 실제로 존재한다.
- 모든 라벨의 `car_class`가 `CityCar`, `Compact`, `Mid-size`, `Full-size` 중 하나로
  해석된다.
- 동일 사례의 모든 라벨에서 해석한 `car_class`가 서로 일치한다.

추가 조건에서 탈락한 사례는 `repair_case`에 부분 적재하지 않고
`data_validation_error`에 `invalid_car_class`, `missing_image` 등의 유형으로 남긴다.

## 이미지 key와 로컬 파일

AI-Hub 검색 사례 이미지는 `RepairCaseImageKeys.key()`와 같은 규칙으로
`repair-cases/{caseId}/images/{caseImageId}/{variant}.{ext}`를 사용한다.
여기서 `caseId`는 `repair_case.case_id`, `caseImageId`는
`repair_case_image.case_image_id`이며, 현재 전수 적재에서는 실제 원본인
`original.jpg`만 만든다. `thumbnail`, `resized`, `blurred`는 해당 파생 파일을
생성할 때 같은 규칙으로 추가한다.

`source_image_ref`에는 AI-Hub 원본을 추적할 수 있는 데이터셋 상대 경로를 보존하고,
`storage_key`에는 S3에서 사용할 논리 key를 저장한다. 원본 파일명이나 데이터셋
폴더 구조를 S3 key로 사용하지 않는다.

개발 환경에서는 S3에 업로드하지 않고 `source_image_ref`로 로컬 원본을 읽는다.
추후 S3를 사용할 때는 같은 `storage_key`를 object key로 사용한다. 실사용자 사고
이미지가 이후 검색 사례로 색인되는 경우에는 `repair-cases`로 복사하지 않고 기존
`accidents/{accidentId}/images/{imageId}/...` key와 이미지 레코드를 참조한다.

## 품질 상태의 의미

이번 적재의 `is_searchable=TRUE`는 readiness 검증을 통과했다는 뜻이며, 이미지
품질 판정 결과를 의미하지 않는다. 별도 이미지 품질 판정 job이 아직 없으므로
`quality_status`는 미판정(`NULL`)으로 저장한다.

`car_class`는 모든 동일 사례 라벨에서 읽은 값이 허용된 네 종류 중 하나이고 서로
일치할 때만 통과한다. 대표 라벨 하나만 읽어 사례 전체를 판정하지 않는다.

## 실행 재현 정보

전수 적재 실행 시 아래 네 경로와 옵션을 실행 기록에 실제 값으로 남긴다.

```text
--subset-root
--dataset-root
--readiness-csv
--mapping-workbook 또는 --mapping-seed-sql
```

`--dataset-root`는 `source_image_ref`의 기준 경로를 결정한다. 다만 새 S3
`storage_key`는 이 옵션의 값에 의존하지 않으며, DB의 `case_id`와 `case_image_id`로
결정된다.

## 스키마를 바꾸지 않은 이유

현재 canonical DDL의 `repair_case.car_class`는 `NOT NULL`이며 4개 값만 허용한다.
이번 검색 기본 적재에서 `NULL` 또는 `UNKNOWN`을 임의로 추가하면 차량 조건 필터의
의미가 달라지고 서비스·검색 쿼리의 계약도 바뀐다. 차급 원천값이 없거나 새 분류가
필요한 사례는 Raw/오류 계층에 남기고, 차급 정책 변경은 별도 migration과 재검증으로
진행한다.

## 적재 책임 분리

1. `pipeline/jobs/load_estimate_raw.py`가 견적 JSON 원문을 먼저 보존한다.
2. `pipeline/jobs/load_search_data.py`가 기준 코드·확정 매핑·검색 사례·이미지만
   적재한다.
3. `pipeline/jobs/load_aihub_damage_dataset.py`는 AI-Hub annotation 원천 테이블용
   구조다. annotation 전수 적재는 이번 범위에 포함하지 않는다.

따라서 `aihub_estimate_raw`의 125,006건과 `repair_case`의 검색 사례 수는 서로 다른
정상적인 수치이며, 한 테이블의 count로 다른 테이블의 누락을 판단하지 않는다.
