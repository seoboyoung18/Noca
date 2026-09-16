# 이미지 입력 및 검색 corpus 결정사항

## 결론

데모 입력은 `damage` 스타일(손상 부위 중심의 가까운 사진)로 사용한다. 다만 임의의 모든 부품을 지원한다고 설명하지 않고, 현재 모델 결과가 안정적인 부품(우선 범퍼)과 `STRICT` 결과가 나오는 사례를 중심으로 시연한다.

견적 항목은 `searchability: STRICT`인 결과만 사용한다. `VECTOR_ONLY`는 부품 코드가 확정되지 않은 결과이므로 견적 항목으로 만들지 않는다.

## 검증 결과

동일한 20장이 아닌 별도 validation 샘플이므로 절대 정확도 비교가 아니라 입력 스타일 판단용으로 사용한다.

### `damage` 스타일 원본 직접 검출

- 데이터: `VS_damage/damage` validation 20장
- part 검출 이미지: 17/20
- damage 검출 이미지: 12/20
- damage box: 17개
- pairing: 기존 비교 결과 STRICT 11개, VECTOR_ONLY 6개
- 이 데이터의 원본 annotation에는 image-level repair 후보가 있으나 image part bbox 정답은 없다.

### `damage_part` 스타일 원본 직접 검출

- 데이터: `VS_damage_part/damage_part` validation 20장
- part 검출 이미지: 20/20
- damage 검출 이미지: 13/20
- damage box: 24개
- 모델 pairing: PAIRED 16개, UNPAIRED 5개, AMBIGUOUS 3개
- 원본 annotation에 part bbox와 damage bbox가 함께 있어 평가용으로 적합하다.

따라서 part 식별만 보면 `damage_part`가 유리하지만, 데모 입력은 손상 표현이 명확한 `damage` 스타일로 제한하고 안정적인 부품만 보여주는 전략을 사용한다.

## `repair` 라벨 확인 결과

`VL_damage`의 `repair` 값은 실제로 부품과 수리 방식 후보를 포함한다.

```text
Front bumper:repair,coating
Rear bumper:coating,exchange
Rear fender(R):coating,sheet_metal
Head lights:exchange
```

validation 전체 기준:

- damage 파일: 50,445개
- damage annotation: 155,375개
- `repair`가 있는 annotation: 143,219개(약 92.2%)
- repair 부품 종류: 42종

단, 한 annotation의 `repair` 배열에 여러 부품이 함께 들어가는 경우가 많다. 따라서 `repair`를 해당 damage bbox의 부품 정답으로 그대로 복사하면 안 된다. `repair`는 image/사고 단위의 부품 후보 또는 수리 후보로 취급한다.

## damage corpus를 사용할 경우

`damage` 이미지를 검색 corpus로 사용할 수는 있지만, 다음 조건으로 part code를 보강해야 한다.

```text
damage annotation
 → damage ROI vector 생성
 → repair에서 부품 후보 추출
 → part YOLO 실행
 → YOLO 부품이 repair 후보에 포함되는지 확인
 → damage bbox와 part bbox의 geometry overlap 확인
 → 후보가 하나일 때만 partCode 저장
```

검증되지 않은 행은 `partCode = null`로 남기고 `VECTOR_ONLY`로 처리한다. 부품을 틀리게 부여하는 것보다 null이 견적 안전성 측면에서 낫다.

damage corpus로 전환할 때는 `repair_case_roi_embedding`에 대해 새 embedding version으로 재적재·재임베딩하고, 기존 corpus와 섞지 않는다. `damage_part`의 확정 part bbox를 그대로 damage 이미지에 전파하는 방식은 사용하지 않는다.

## 임베딩 및 검색

현재 온라인 query 임베딩은 이미 damage detection의 bbox/polygon으로 ROI를 만든다. 즉 query vector의 대상은 damage다.

검색은 다음 메타데이터를 사용한다.

```text
STRICT: partCode + damageType + carClass
VECTOR_ONLY: damageType + carClass
```

FE/BE 응답은 `partCode`, `damageType`, `searchability` 등 표준 계약을 유지한다. 데모 화면에서 특정 부품명을 하드코딩하지 않는다.

## 차량 유효성 판정

별도 차량 분류 모델은 사용하지 않는다. `inference_service`가 이미 실행하는 part 모델의 원시 결과를 차량 유효성 gate로 사용한다.

```text
part_predictions 비어 있음
 → excluded: true
 → exclusionReason: NOT_VEHICLE

part_predictions 하나 이상
 → excluded: false
 → exclusionReason: null
```

이 필드는 기존 백엔드·FE 계약에 이미 포함되어 있으므로 외부 API 변경은 없다. 일부 이미지가 제외되어도 다른 이미지가 남으면 분석을 계속하고, 모든 이미지가 제외되면 기존 `ALL_IMAGES_EXCLUDED` 흐름으로 재업로드를 안내한다.

검색 서비스의 `searchability` 미정의 변수 오류는 수정했다.

## 수리 방식

`repair` 라벨의 작업 후보는 수리 방식 후보 데이터다. `Scratched`는 `coating`으로 비교적 확정 가능하지만, `Separated`, `Crushed`, `Breakage`는 `repair/exchange` 또는 `sheet_metal/exchange`처럼 후보가 여러 개일 수 있다. 손상 유형만으로 수리 방식을 단정하지 않고, 현재 계약의 후보/미확정 정책을 따른다.

## 로컬 검증 파일

- [damage validation 직접 검출](../../AI/vision-check-validation.html)
- [damage_part validation 직접 검출](../../AI/vision-check-validation-part.html)

두 HTML 모두 crop 결과가 아닌 원본 이미지 직접 검출 결과만 보존한다.
