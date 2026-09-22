# 견적 산출 입출력 형식 — MVP 실시간 참조 사례 기반

기준일: 2026-09-16  
상태: MVP 기준안

기존 `견적 산출 입출력 형식.md`는 운영 안정화 단계의 `repair_cost_stat` 사전 집계
방식을 설명한다. MVP에서는 검색된 참조 사례를 이용해 실시간으로 비용을 산출한다.

## 1. MVP 결정사항

MVP에서는 `repair_cost_stat`을 미리 만들지 않는다. 검색 모듈이 반환한 참조 사례를
비용 산출 모듈이 일괄 조회하고, 각 사례에서 분석 대상 부품의 비용만 계산한 뒤
사례별 결과를 통계 처리한다.

```text
이미지 분석
  → 부품·손상 유형 표준화
  → 유사 사례 최대 10건 검색
  → 사례별 해당 부품 비용 계산
  → 사례별 비용 P25·중앙값·P75 계산
  → 견적 결과 반환
```

`referencedCaseIds`는 MVP에서 화면 표시뿐 아니라 비용 계산에 사용하는 참조 사례다.
10건의 전체 견적 금액을 합산하지 않고, 각 사례의 해당 부품 비용을 먼저 계산한다.

## 2. 책임 분리

| 모듈 | 책임 |
|---|---|
| 추론·표준화 | 이미지에서 부품·손상 유형·신뢰도 산출 |
| 검색 | 조건에 맞는 참조 사례 ID 최대 10건 반환 |
| 비용 산출 | 참조 사례의 `repair_case_item` 조회, 부품 비용 계산, 통계 처리 |
| 백엔드·리포트 | 결과 저장 및 화면 표시 |

MVP 비용 산출 모듈은 `repair_cost_stat`을 조회하지 않는다. 운영 단계에서 동일한
계산 규칙을 배치로 옮겨 `repair_cost_stat`을 만들 수 있다.

## 3. 입력

검색 모듈이 반환한 `STRICT` 결과와 차량 조건을 받는다. `VECTOR_ONLY` 결과는
부품이 확정되지 않았으므로 비용 항목으로 만들지 않는다.

```json
{
  "vehicle": {
    "modelId": 41,
    "carClass": "Compact",
    "modelYear": 2021
  },
  "parts": [
    {
      "partCode": "REAR_BUMPER",
      "damageType": "Scratched",
      "confidence": 0.9321,
      "detectionIds": ["501:damage:damage-001"],
      "pairStatus": "PAIRED",
      "searchability": "STRICT",
      "fallbackStage": "PRICE_TIER",
      "referencedCaseIds": [121381, 121414]
    }
  ]
}
```

### 입력 필드

| 필드 | 설명 |
|---|---|
| `vehicle.modelId` | 차종 식별자. 검색 결과의 필터·검증에 사용 |
| `vehicle.carClass` | 차급. 차종 사례가 부족할 때 완화 기준으로 사용 |
| `partCode` | 검색 모듈이 표준화한 부품 코드 |
| `damageType` | `Scratched` · `Separated` · `Crushed` · `Breakage` |
| `searchability` | `STRICT`만 비용 산출 대상 |
| `fallbackStage` | 검색 조건이 완화된 단계 |
| `referencedCaseIds` | 비용 계산에 사용할 참조 사례 ID. 항목당 최대 10건 |

사례 ID만 있고 이미지와 견적 행의 직접 연결이 없는 경우에는 같은 사례의
`repair_case_item.part_code`를 기준으로 해당 부품 비용을 근사한다.

## 4. MVP 비용 산출 절차

### 4.1 참조 사례 일괄 조회

각 부품의 `referencedCaseIds`를 모아서 DB를 한 번에 조회한다. 사례별로 DB를 반복
조회하지 않는다.

```sql
SELECT
    rc.case_id,
    rc.model_id,
    rc.car_class,
    rc.model_year,
    rc.source,
    rci.part_code,
    rci.line_type,
    rci.work_code,
    rci.assessment_status,
    rci.part_cost,
    rci.paint_material_cost,
    rci.labor_cost,
    rci.item_total
FROM repair_case rc
JOIN repair_case_item rci ON rci.case_id = rc.case_id
WHERE rc.case_id IN (...)
  AND rci.part_code IN (...);
```

차종·차급 조건은 검색 단계에서 우선 적용한다. 사례 수가 부족하면 검색 모듈의
fallback 정책에 따라 차급 범위를 완화하고, 완화된 단계는 `fallbackStage`에 남긴다.

### 4.2 비용 행 필터링

| 행 종류 | 처리 |
|---|---|
| `WORK` | 해당 부품의 수리 작업 비용으로 포함 |
| `PART_PRICE` | 해당 부품의 부품 비용으로 포함 |
| `REFERENCE_PRICE` | 참고 정가이므로 제외 |
| `ANCILLARY` | 부품별 견적에서 제외하거나 별도 부대비용으로 처리 |
| `NOT_APPROVED` | 비용 성분이 확정되지 않았으므로 기본 제외하고 상태만 보존 |

비용 성분은 다음처럼 계산한다.

```text
일반 수리 = part_cost + labor_cost
도장      = paint_material_cost + labor_cost
```

`repair_case_item.work_code`와 `repairMethod`의 연결 규칙은 비용 담당자가 확정한다.

```text
COATING      → coating
SHEET_METAL  → sheet_metal
EXCHANGE     → exchange
REPAIR       → repair
```

### 4.3 사례별 비용 및 통계

각 사례에서 같은 부품에 해당하는 비용 행을 먼저 합산한다.

```text
사례 A의 REAR_BUMPER 비용 = 300,000원
사례 B의 REAR_BUMPER 비용 = 335,500원
사례 C의 REAR_BUMPER 비용 = 380,000원
```

검색된 10건의 비용을 서로 더해 최종 금액으로 사용하지 않는다. 사례별 합계 목록을
만든 뒤 P25·중앙값·P75를 계산한다.

같은 사례에 동일 부품의 작업 행이 여러 개 있으면 `case_id + part_code` 단위로
수리 방식과 무관하게 합친다. 판금 후 도장처럼 한 부품에 여러 작업 방식이 함께 있는
경우가 정상적으로 존재하므로 방식별로 사례를 쪼개지 않는다. 대표 `repairMethod`는
`exchange > sheet_metal > repair > coating` 우선순위로 고르고, 후보 전체는
`repairMethodReason.candidates`에 남긴다. 한 사례가 여러 번 표본에 들어가면 안 된다.

## 5. 출력

출력 구조는 기존 콜백의 `items[]` 형식을 유지하되, 비용 값의 의미를 MVP 기준으로
해석한다.

```json
{
  "estimable": true,
  "nonEstimableReason": null,
  "confidenceGrade": "MEDIUM",
  "totals": {
    "min": 300000,
    "median": 335500,
    "max": 380000
  },
  "refCaseTotal": 2,
  "items": [
    {
      "partCode": "REAR_BUMPER",
      "damageType": "Scratched",
      "confidence": 0.9321,
      "repairMethod": "coating",
      "partCost": null,
      "laborCost": 250000,
      "paintMaterialCost": 85500,
      "itemTotal": 335500,
      "detectionIds": ["501:damage:damage-001"],
      "refCaseCount": 2,
      "referencedCaseIds": [121381, 121414],
      "costDistribution": {
        "p25": 300000,
        "median": 335500,
        "p75": 380000
      },
      "fallbackStage": "PRICE_TIER"
    }
  ],
  "unresolvedParts": []
}
```

| 필드 | MVP 의미 |
|---|---|
| `refCaseCount` | 해당 부품 비용 계산에 성공한 고유 사례 수 |
| `refCaseTotal` | 전체 항목에서 사용한 고유 참조 사례 수 |
| `costDistribution` | 참조 사례별 부품 비용 합계의 P25·중앙값·P75 |
| `itemTotal` | 사례별 부품 비용 합계 분포의 중앙값 |
| `partCost` | 사례별 부품비의 중앙값. 없으면 `null` |
| `laborCost` | 사례별 공임의 중앙값 |
| `paintMaterialCost` | 사례별 도장 재료비의 중앙값. 없으면 `null` |
| `mergedDamageTypes` | 이 항목 하나로 합쳐진 손상 유형 목록. **합쳤을 때만 보낸다** — 이 필드가 있으면 합친 항목이고, 합치지 않은 항목에는 아예 없다 |
| `unresolvedParts` | 비용 산정에 실패한 부품 목록. 부분 견적이면 `items[]`와 함께 반환 |

`itemTotal`은 사례별 총액의 중앙값이고 각 비용 성분의 중앙값 합과 다를 수 있다.
리포트에서는 `itemTotal`을 대표 금액으로 사용한다.

여러 부품이 있을 때 `totals.min`·`totals.median`·`totals.max`는 각 항목의 P25·중앙값·P75를 각각 합산한다.

### 5.1 `items[]`의 `partCode`는 한 번만 나온다

같은 부위가 손상 두 곳으로 탐지되면 검색은 `(partCode, damageType)` 단위로 결과를
내지만, **견적은 부위 단위로 합쳐 항목 하나만 보낸다.** 부위 하나는 한 번 수리하므로
두 손상의 금액을 더하면 같은 부품을 두 번 교체한 총액이 된다.

합칠 때 남길 항목(대표)은 이 순서로 고른다.

1. 가장 무거운 수리 방식 — `REPAIR_METHOD_PRIORITY` 순서 그대로 (교환 > 판금 > 수리 > 도장).
   교환하면 긁힘도 함께 해결되므로 방식이 먼저다.
2. 방식이 같으면 `costDistribution.median`이 큰 것. `repairMethod`는 참조 사례 방식의
   합집합에서 고른 값이라 동률이 흔한데, 같은 부위 안에서는 금액이 큰 쪽이 곧 무거운
   수리다. 사례 수는 표본 크기일 뿐 심각도가 아니라서 이 자리에 두지 않는다.
3. 그래도 같으면 참조 사례 수가 많은 것.

대표 항목이 나머지를 대신하므로 다음이 따라온다.

- `detectionIds`는 묶음 전체를 입력 순서대로 합친다 — 사진의 박스 두 개가 같은 번호를 받는다.
- `mergedDamageTypes`에 묶음의 손상 유형을 모두 남긴다. `damageType`은 대표의 값이다.
  **합쳤을 때만 보낸다.** 소비자는 이 필드의 존재(비어 있지 않음)로 합친 항목을 가리고,
  원소 수로 가리지 않는다 — 같은 유형 두 곳을 합치면 중복을 걷어 원소가 하나로 남을 수 있다.
- `confidenceGrade`는 묶음의 **최저** 등급이다. 합칠지 말지를 얇은 표본으로 판단했다면
  그 얇음이 등급에 남아야 한다.
- `totals`·`refCaseTotal`은 대표 항목들만으로 계산한다.
- 묶음 중 하나라도 산정되면 그 부위는 `unresolvedParts[]`에 넣지 않는다.
- `repairMethodReason`에 `mergedCandidates`(묶음 전체 방식)와 `uncoveredMethods`
  (묶음에는 있으나 대표의 참조 사례엔 없는 방식)를 함께 남긴다. 아래 "왜 더하지 않나" 참고.
- `partPriceReferences[]`의 `partCode`도 한 번만 나온다. 부품비는 손상 유형과 무관하므로
  이쪽은 대표를 고르지 않고 후보 사례를 **합집합**으로 묶어 한 번만 계산한다.

#### 왜 대표 하나만 남기고 더하지 않나

판금과 도장은 한 부위에 함께 하는 작업이니 두 손상의 금액을 더해야 하는 것처럼 보인다.
그러나 사례 단위 총액이 이미 그 조합을 담고 있다. `_aggregate_case`는 `(case_id, part_code)`의
WORK·PART_PRICE 행을 방식 구분 없이 전부 합산하고, 부품명 매핑 테이블이 `후드`·`후드판금`·
`후드교환`·`후드 표면판금보수`를 모두 같은 `BONNET`으로 접는다(작업어가 붙은 매핑 2,623행).
그래서 찌그러짐으로 검색한 사례의 총액은 "판금만"이 아니라 **판금하고 도장까지 한 부위의
실제 총액**이다. 여기에 긁힘 항목을 더하면 도장을 두 번 청구하게 된다.

맞지 않는 구간은 하나다 — 대표의 참조 사례들이 그 작업을 아예 갖고 있지 않은 경우.
그때는 그 작업이 값에서 통째로 빠진다. `candidates`가 그 항목 사례 방식의 합집합이므로
묶음 합집합에서 대표의 것을 빼면 빠진 작업이 그대로 드러나고, 그 목록이
`uncoveredMethods`다. **금액은 보정하지 않는다** — 얼마를 더해야 하는지에 대한 근거가 아직
없다. 대신 비어 있지 않으면 경고 로그를 남기고, 이것이 실제로 얼마나 자주 일어나는지는
`Docs/CostNotes/WORK_COMBINATION_BY_PART_CODE.sql`로 측정한 뒤 규칙을 다시 본다.

`unresolvedParts[]` 원소는 `{ "partCode": "...", "damageType": "...", "reason": "INSUFFICIENT_CASES" }`
형식이다. `items[]`가 하나 이상이면 산정 가능한 항목만으로 부분 견적을 만들고,
`unresolvedParts[]`가 비어 있지 않다는 사실을 화면에 표시해야 한다.

## 6. 산정 불가 기준

| 상황 | 반환 |
|---|---|
| `STRICT` 부품이 없음 | `PART_NOT_RESOLVED` |
| 차량은 유효하지만 손상이 검출되지 않음 | `NO_DAMAGE_DETECTED` |
| 참조 사례에 해당 부품 비용 행이 없음 | `INSUFFICIENT_CASES` |
| 비용 행의 필수 금액이 없음 | 해당 사례 제외 |
| 사례 수가 최소 기준보다 적음 | `estimable: false` |

MVP 최소 사례 수는 비용 담당자가 정한다. 초기 검증에서는 최소 3건 이상을 권장하며,
사례가 부족하면 임의의 금액을 반환하지 않는다.

`NO_DAMAGE_DETECTED`는 비용 모듈에 들어오기 전 분석 단계의 결과다. part가 검출되어 차량은
유효하지만 damage가 검출되지 않은 경우이며, `imageResults[].detections`는 빈 배열로 보낸다.

## 7. 비용 담당자 인계 범위

비용 담당자는 다음 규칙을 정의·검증한다.

1. 부품별 비용 행 포함 기준
2. 수리 방식 매핑 기준
3. 도장·부품·공임 계산식
4. AS·SC 출처를 합칠지 분리할지 여부
5. 사례별 중복 행 합산 기준
6. 차종→차급 fallback 기준
7. 최소 사례 수와 산정 불가 기준

MVP에서 필요한 DB 읽기 대상은 다음과 같다.

```text
repair_case
repair_case_item
part_code
part_name_mapping
```

원천 JSON(`aihub_estimate_raw`)은 예외 사례를 확인할 때만 사용한다.

## 8. 후속 운영 전환

MVP의 사례별 비용 계산 로직이 안정화되면 동일한 규칙을 배치로 옮긴다.

```text
MVP: referencedCaseIds → 실시간 repair_case_item 조회 → 비용 계산
운영: 전체 적격 사례 → repair_cost_stat 사전 집계 → 통계 조회
```

운영 전환 시 `repair_cost_stat`에는 차급·부품·손상 유형·수리 방식·출처를 기본 축으로
두고, 차종·연식은 표본 수를 확인한 뒤 별도 축으로 추가한다. 필터를 세분화할수록
표본이 부족해질 수 있으므로 모델→차급→전체 순의 fallback을 유지한다.

## 함께 보는 문서

| 문서 | 내용 |
|---|---|
| `Docs/AI/견적 산출 입출력 형식.md` | 운영 단계 통계 테이블 기반 설계 |
| `Docs/AI/AI 서버 API 명세.md` | AI 서버 엔드포인트와 MVP `/estimate` |
| `Docs/Erd/A307_COST_DB_HANDOVER.md` | 비용 원천 데이터와 정규화 행 의미 |
| `Docs/Erd/A307_ddl_final.sql` | `repair_case`·`repair_case_item` 테이블 정의 |
