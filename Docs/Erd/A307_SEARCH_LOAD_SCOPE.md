# 검색 기본 데이터 적재 범위

기준일: 2026-09-09

## 결정

`repair_case`는 `case_search_readiness.csv`의 `is_final_searchable_case=True`인
사례만 대상으로 한다. 이 판정은 이미지·라벨·견적 조인, 유효 damage geometry,
표준 부품 코드 확보를 모두 통과한 상태다.

| 구분 | 건수 | 처리 |
|---|---:|---|
| 원천 견적 전체 | 125,006 | `aihub_estimate_raw`에 별도 전수 보존 |
| damage_part 기준 readiness 최종 검색 가능 | 55,363 | `repair_case` 적재 후보 |
| 최종 검색 불가 (`125,006 - 55,363`) | 69,643 | `repair_case`에 넣지 않음. 판정 산출물로 보존 |

readiness 단계의 중간 수치는 견적·사례 조인 후보 116,809건이며, 그중
`damage_part` 계열에서 geometry/part-code 검증을 통과한 55,363건을 최종 검색
범위로 사용한다. 이 기준의 이미지 라벨 파일은 98,617건이다.

실제 적재 job은 후보 중 다음 조건을 추가로 확인한다.

- 라벨에 대응하는 이미지 파일이 실제로 존재한다.
- 모든 라벨의 `car_class`가 `CityCar`, `Compact`, `Mid-size`, `Full-size` 중 하나로
  해석된다.
- 동일 사례의 모든 라벨에서 해석한 `car_class`가 서로 일치한다.

추가 조건에서 탈락한 사례는 `repair_case`에 부분 적재하지 않고
`data_validation_error`에 `invalid_car_class`, `missing_image` 등의 유형으로 남긴다.

## 검색 이미지 원천 범위

검색 사례 이미지는 **`damage_part` 계열만** 사용한다.

```text
1.Training/2.라벨링데이터/TL_damage_part/damage_part
2.Validation/2.라벨링데이터/VL_damage_part/damage_part
```

`damage`와 `damage_part`는 서로 다른 YOLO 목적에 맞춰 화각과 포함 영역이
다르므로 같은 검색 코퍼스에 섞지 않는다. `damage` 계열은 이번
`repair_case_image` 적재 범위에서 제외한다. readiness 검증과 검색 적재 loader도
동일하게 `damage_part` 계열만 스캔해야 하며, 이 기준으로 생성한 manifest를 사용한다.

readiness와 사례별 manifest는 원천 데이터에서 재생성 가능한 실행 산출물이므로
`pipeline/manifests/`에 커밋하지 않고 저장소 밖에 보관한다. 생성 명령은 다음과 같다.

```bash
python pipeline/jobs/validate_search_readiness.py \
  --subset-root "<01.데이터_견적서보유 경로>" \
  --linkage-csv "<category integrity output>/case_id_linkage.csv" \
  --output-dir "<저장소 밖 readiness output>"

python pipeline/jobs/build_case_split_manifests.py \
  --subset-root "<01.데이터_견적서보유 경로>" \
  --readiness-csv "<저장소 밖 readiness output>/case_search_readiness.csv" \
  --linkage-csv "<category integrity output>/case_id_linkage.csv" \
  --output-dir "<저장소 밖 manifest output>" \
  --dev-cases 1000 \
  --demo-cases 20 \
  --seed "a307-search-cases-v1"
```

2026-09-10 전수 readiness 산출물의 SHA-256은
`9412D070E28A2252377D9C9B77718961D9766127848D59398E8FFE91B4233482`이며,
재실행 결과가 다르면 원천 데이터·코드·실행 옵션을 먼저 비교한다.

## 이미지 key와 로컬 파일

AI-Hub 검색 사례 이미지는 `RepairCaseImageKeys.key()`와 같은 규칙으로
`repair-cases/{caseId}/images/{caseImageId}/{variant}.{ext}`를 사용한다.
여기서 `caseId`는 `repair_case.case_id`, `caseImageId`는
`repair_case_image.case_image_id`이며, 현재 전수 적재에서는 실제 원본인
`original.jpg`만 만든다. `thumbnail`, `resized`는 해당 파생 파일을 생성할 때 같은
규칙으로 추가한다.

`blurred`는 만들지 않는다. `S15P21A307-226`~`-228`이 2026-09-10에 MVP 범위 밖으로
정리됐고, AI-Hub 데이터셋 이미지는 이미 비식별된 상태로 제공되어 가릴 대상이 없다.
S3 실측에서도 `original.jpg` 2,216개에 `blurred.jpg`는 0개다. 같은 이유로
`repair_case_image.blur_key`는 컬럼만 있고 적재 코드가 채우지 않으며, 읽는 코드도 없다.

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
