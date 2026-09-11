# damage_part 중심 검색 데이터 적재 범위

기준일: 2026-09-11 · 코드 대조: `develop` `4a0836d`

아래 건수·S3 실측은 기존 실행 기록이다. 이번 문서 갱신에서 DB·S3를 재조회하지 않았다. 구현 상태·확인된 오류는 [파이프라인 진행 현황](../Pipeline/STATUS.md)을 본다.

## 결정

주 검색 벡터는 `damage_part` 이미지에서 만든 손상 ROI다. `damage`는 같은 사고의
선택적 시각 참고 이미지로만 적재한다. 두 폴더는 category_id 단위로만 연결되므로
서로의 annotation을 전파하지 않는다. 검색 필터와 스키마의 상세 계약은
[damage_part 중심 유사 사례 검색 스키마](A307_DAMAGE_SEARCH_SCHEMA.md)를 본다.

`damage_type`은 같은 `damage_part` 이미지의 ROI에 직접 라벨된 값이므로 하드 필터로
쓴다. 같은 이미지의 part annotation과 geometry가 명확히 매칭된 경우에만
`part_code`를 strict 필터에 사용한다. 견적 수리항목의 부품은 사례 부품 후보이므로
기본 검색에서는 후보 축소 또는 재정렬 신호로만 쓴다.

```text
damage_part ROI의 damage_type 하드 필터
→ 사례 견적 part_code 후보 조건(선택)
→ damage_part ROI 벡터 유사도 검색
→ 사례 부품 후보 근거와 함께 결과 표시
```

## 기존 damage_part 전용 실행 기록

아래 55,363건·98,617이미지 및 readiness 설명은 2026-09-10의 기존 실행 기록이다.
당시에는 damage_part 전용으로 적재했지만 동일 이미지의 damage-part pairing을
집계하지 않았다. 기존 수치를 새 적재 범위나 완료 건수로 사용하지 않고, 새
readiness에서 damage_part 기준 geometry·pairing을 다시 계산한다.

`repair_case`는 `case_search_readiness.csv`의 `is_final_searchable_case=True`인
사례만 대상으로 한다. 이 판정은 이미지·라벨·견적 조인과 유효한
`damage_part` damage geometry를 확인한 상태다. 표준 부품 코드 확보는 사례 전체의
필수 조건이 아니며, ROI별 strict/vector-only 상태로 별도 집계한다.

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

55,363건은 readiness 후보 수이며 실제 적재 성공 수를 보장하지 않는다. 추가 검증을 통과한 건수와 제외 사유는 `load_search_data.py`의 실행 summary 및 오류 테이블로 확인한다.

## 2026-09-11 damage_part 정책 전수 readiness

현재 브랜치의 damage_part 중심 pairing 코드를 적용해 TRAIN·VALIDATION의 네 label
그룹을 전수 재생성했다. DB·S3에는 접근하지 않았으며, 산출물은 저장소 밖에 보관했다.

| 항목 | 결과 |
|---|---:|
| linkage 기준 prior searchable case 후보 | 116,809 |
| 최종 searchable case | 55,363 |
| 전체 label 파일 처리 | 457,670 |
| parse error | 0 |
| DAMAGE_PART label/image 수 | 99,910 |
| DAMAGE_PART damage ROI 수 | 374,138 |
| PAIRED / STRICT ROI | 265,408 (70.94%) |
| UNPAIRED / VECTOR_ONLY ROI | 54,720 (14.63%) |
| AMBIGUOUS / VECTOR_ONLY ROI | 54,010 (14.44%) |
| 전체 VECTOR_ONLY ROI | 108,730 (29.06%) |

표본에서 관찰한 68~71% 범위와 비교하면 전수 PAIRED 비율은 70.94%로 유사하다.
다만 이 결과는 readiness 판정 결과이며, 아직 DB 적재·임베딩 생성·S3 업로드가
완료됐다는 뜻은 아니다.

실행 산출물:

```text
outputs/data_validation/search_readiness_damage_part_2026-09-11/
├─ case_search_readiness.csv
└─ search_readiness_summary.json
```

SHA-256:

- `case_search_readiness.csv`: `4D68FD73308FB12D9802B59A81C68C3F2B56C5FE8FD743851E3C10166169A170`
- `search_readiness_summary.json`: `2437CF530D12D41FC02B9A04EB5FFE821309C48C282C0057CB349EA81BB57C61`

## 검색 이미지 원천 범위

두 이미지 유형을 읽되 검색 역할을 분리한다.

```text
1.Training/2.라벨링데이터/TL_damage/damage             → DAMAGE
1.Training/2.라벨링데이터/TL_damage_part/damage_part   → DAMAGE_PART
2.Validation/2.라벨링데이터/VL_damage/damage            → DAMAGE
2.Validation/2.라벨링데이터/VL_damage_part/damage_part → DAMAGE_PART
```

`damage_part`는 ROI 임베딩의 주 원천이며 `damage`는 주 벡터 검색에서 제외한다.
두 유형은 같은 `repair_case`에 연결하되 `repair_case_image.image_type`으로 반드시
구분한다. `damage_part`의 직접 부품 영역은 같은 이미지의 damage ROI와 geometry가
명확히 매칭될 때만 part_code를 연결하며, `damage` 이미지나 다른 사례 이미지에는
전파하지 않는다.

readiness·loader·split manifest는 두 유형을 함께 읽되 다음 상태를 별도로 남긴다.

- `has_damage_geometry`: DAMAGE_PART에서 유효 damage polygon과 damage type이 있는가
- `has_estimate_part_candidate`: 견적 수리항목이 표준 part code로 매핑되는가
- `has_damage_part_context`: DAMAGE_PART의 검색 이미지 또는 직접 part annotation이 있는가

`has_damage_geometry=True`이면 부품 후보가 없어도 ROI 적재 후보가 될 수 있다.
부품 후보 결측은 검색 제외 사유가 아니라 결과에서 표시할 근거 부족 상태다.

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
`repair-cases/{source}/{external_ref}/{source_image_id}/{variant}.{ext}`를 사용한다.
`source`와 `external_ref`는 `repair_case`의 원천 식별자이고,
`source_image_id`는 `source_image_ref` 파일명의 숫자 접두를 문자열 그대로 보존한다.
현재 전수 적재에서는 실제 원본인 `original.jpg`만 만든다. `thumbnail`, `resized`는
해당 파생 파일을 생성할 때 같은 규칙으로 추가한다.

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

`repair_case_image.is_searchable=TRUE`는 이미지가 적재·참조 가능한 상태라는
뜻이다. `repair_case_roi_embedding.is_searchable=TRUE`는 geometry와 embedding이
유효해 벡터 검색에 사용할 수 있다는 뜻이다. 둘 다 `part_code`가 있다는 뜻은 아니다.
별도 이미지 품질 판정 job이 아직 없으므로 `quality_status`는 미판정(`NULL`)으로
저장한다.

`car_class`는 모든 동일 사례 라벨에서 읽은 값이 허용된 네 종류 중 하나이고 서로
일치할 때만 통과한다. 대표 라벨 하나만 읽어 사례 전체를 판정하지 않는다.

## 현재 구현상 주의점

- readiness의 `--merge-only`는 사례별 라벨·orphan 건수를 복원하지 않아 CSV의 해당 건수 열이 0으로 출력된다. geometry 집합은 병합되지만 건수 검증은 전체 실행 결과와 대조해야 한다.
- `build_search_sample_sql.py`는 라벨 전체를 탐색하는 일회성 표본 도구다. 정식 loader와 공통 견적 비용 해석을 사용하지만 검색 코퍼스 재현에 대체 사용하지 않는다.
- `verify_search_sample.py`의 Raw 미연결 예상값은 실제 `repair_case` 건수에서 계산한다. 69,643건이라는 정책 수치를 독립적으로 강제하지 않으며 전체 readiness 누락·초과를 검증하는 도구도 아니다. 전수 검증에는 readiness 키 집합과 실제 적재 키 집합의 별도 대조가 필요하다.
- ROI 메타데이터는 `pipeline_version_id`를 요구하지만 현재 임베딩 DDL은 `model_version_id`를 참조한다. 손상 코드도 메타데이터는 대문자 표준 코드, DDL은 `Scratched` 등 원천 표기를 허용한다. 임베딩 적재 어댑터·버전 연결 또는 migration이 필요하다.

## 실행 재현 정보

전수 적재 실행 시 아래 네 경로와 옵션을 실행 기록에 실제 값으로 남긴다.

```text
--subset-root
--dataset-root
--readiness-csv
--mapping-workbook 또는 --mapping-seed-sql
```

`--dataset-root`는 `source_image_ref`의 기준 경로를 결정한다. 다만 새 S3
`storage_key`는 이 옵션의 값이나 DB의 BIGSERIAL `case_id`·`case_image_id`에
의존하지 않으며, 원천 `source`, `external_ref`, `source_image_id`로 결정된다.

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
