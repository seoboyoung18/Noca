# 검색 corpus · YOLO · 견적 연결 검증 정리

작성일: 2026-09-18  
범위: AI-Hub 사례 검색 corpus, DINOv2 embedding, YOLO 부품 후보, 유사 사례 기반 견적 연결

## 1. 결론 요약

- 검색 corpus는 `DAMAGE_PART` 기반 v1과 `DAMAGE` 기반 v2를 **동시에 유지**한다. 운영 활성화는 아직 하지 않았다.
- v2는 손상 ROI 자체를 검색 대상으로 하므로 검색 Recall 계열 수치는 좋게 나왔지만, 초기 `repair hint` 기반 평가는 이미지별 정답이 아니어서 검색 품질 지표로 사용할 수 없다.
- YOLO는 v2 `DAMAGE` ROI에 보이는 부품 후보를 별도 테이블에 저장하고, v2 검색 결과를 **soft rerank**하는 용도다. v2의 원래 `part_code`는 계속 `NULL`이다.
- 로컬 구버전의 최소 표본 3 case 기준에서는 YOLO rerank가 유효 WORK 비용 사례를 Top-10으로 끌어오는 효과가 관측됐다. 최신 `develop`은 현재 기능 제공을 위해 최소 2 case를 임시 적용한다.
- 최소 2 case는 표본 부족을 허용한 **LOW 신뢰도 임시 정책**이다. 전수 적재가 완료되면 운영 최소 표본을 3 case 이상으로 올리는 것을 목표로 한다. 두 임계값의 평가는 섞어 비교하지 않는다.
- 현재 비용 산출용 `repair_case` corpus는 1,000 case이며, 유효 WORK 비용은 FRONT/REAR_BUMPER에 크게 집중된다. 새 case 적재가 불가능한 현재 제약에서는 **Top-100 견적 참조 → 동일 부품 통계 fallback** 순으로 보완한다.

## 2. 데이터 및 pipeline 상태

### Pipeline

| 구분 | pipeline_version_id | image source | 상태 | 부품 계약 |
| --- | ---: | --- | --- | --- |
| v1 | 1 | `DAMAGE_PART` | active | annotation part code 사용 |
| v2 | 2 | `DAMAGE` | inactive | `part_code=NULL`, `pair_status=UNPAIRED`, repair hint는 별도 저장 |

### Feature / embedding

| 항목 | 수량 |
| --- | ---: |
| v1 damage feature | 13,112 |
| v1 searchable embedding | 9,595 |
| v2 damage feature | 15,688 |
| v2 searchable embedding | 13,375 |
| embedding dimension | 768 |
| embedding model | DINOv2 base (`facebook/dinov2-base`) |

`embedding_model_version`의 기존 row `model_version_id=1`을 재사용했다. 전처리 계약에는 DINOv2 revision `f9e44c814b77203eaa57a6bdbbd535f21ede1415`와 `pad20-lb224gray`가 등록돼 있다.

## 3. v1 / v2 검색 비교: 현재 결론 보류

초기 2,712 query ROI A/B에서 만든 Recall/MRR/비용 연결률 표는 **기록 목적 외에는 사용하지 않는다.** repair hint가 이미지 또는 DAMAGE ROI별 부품 정답이 아니라 같은 사고 견적서의 repair 부품 목록 전체이기 때문이다. 같은 오염된 기준으로 측정했다면 v1과 v2 모두 검색 품질 결론으로 쓸 수 없다.

예를 들어 이미지에 프런트 범퍼가 보이지 않아도 해당 사고 견적서에 프런트 범퍼 작업이 있으면 `FRONT_BUMPER` hint가 DAMAGE annotation에 함께 들어간다. 따라서 repair hint는 다음에 사용하지 않는다.

- 검색 hard filter
- 검색 rerank
- 정답/오답 또는 Recall/MRR 판정
- ROI별 비용 연결

v2 활성화 판단에는 아래 세 경로를 동일한 query·동일 최소 표본·동일 case pool 기준으로 다시 비교해야 한다.

```text
v1 DAMAGE_PART + STRICT part filter
v2 DAMAGE + pure vector
v2 DAMAGE + YOLO rerank
```

## 4. YOLO 부품 후보 설계

### 왜 YOLO가 필요한가

`DAMAGE` annotation에는 손상 geometry가 있으나 신뢰할 수 있는 이미지별 부품 정답이 없다. 따라서 같은 원본 이미지에서 part YOLO를 실행하고, DAMAGE ROI와 YOLO part bbox의 overlap으로 연결한다.

```text
DAMAGE ROI (AI-Hub annotation)
  + same-image part YOLO bbox
  → PAIRED / UNPAIRED / AMBIGUOUS candidate
```

v1 `DAMAGE_PART`와 v2 `DAMAGE`를 직접 매칭하는 방식은 사용할 수 없다. 두 corpus는 같은 `case_image_id`를 공유하지 않고, 같은 사고여도 서로 다른 사진을 사용할 수 있기 때문이다.

### DB 구조

사용자에 의해 migration 파일명은 `013`에서 `015`로 변경됐다.

- `repair_case_image_part_inference`: 이미지별 YOLO inference 상태/원시 결과
- `repair_case_damage_feature_part_mapping`: DAMAGE feature별 `PAIRED`, `UNPAIRED`, `AMBIGUOUS` 매핑 상태
- `repair_case_damage_feature_part_candidate`: 부품 코드, confidence, bbox, overlap, primary 여부

v2 `repair_case_damage_feature.part_code`는 이 테이블 도입 후에도 `NULL`로 유지한다. YOLO 결과는 후보 정보이며 원 annotation을 덮어쓰지 않는다.

### 전수 backfill 결과

| 항목 | 결과 |
| --- | ---: |
| v2 원본 이미지 | 4,254 |
| YOLO `SUCCEEDED` | 2,909 |
| `PART_NOT_DETECTED` | 1,345 |
| `PAIRED` feature | 5,308 |
| `UNPAIRED` feature | 10,096 |
| `AMBIGUOUS` feature | 284 |
| part candidate | 5,928 |

`PART_NOT_DETECTED`, `UNPAIRED`, `AMBIGUOUS`는 corpus에서 제거하지 않는다. 이 경우에는 부품 boost를 적용하지 않는 vector 결과를 사용한다. 응답 reason 이름은 query의 `searchability=VECTOR_ONLY`와 혼동되지 않도록 향후 `NO_PART_MATCH` 등으로 정리한다.

검토용 산출물:

- `AI/artifacts/yolo_part_candidates_actual_50.html`
- `AI/artifacts/yolo_part_candidates_actual_50.json`

샘플 검토에서 mapping은 대체로 납득 가능한 수준으로 확인됐다.

## 5. v2 YOLO soft rerank

v2 query가 STRICT partCode를 가진 경우:

```text
vector candidate pool max(top_k × 10, 100)
→ query 부품 = corpus primary PAIRED YOLO 부품인 case만
→ distance에서 flat boost 차감
→ Top-10 반환
```

- 기본 코드 설정: `YOLO_CORPUS_PART_BOOST=0.03`
- 실험/육안 검토: flat boost `0.01 / 0.03 / 0.05`
- flat `0.03`/`0.05`는 근접 이웃 순서를 크게 바꿀 수 있다. 운영값을 정하기 전 v2 Top-N 거리 분포·응답 latency를 재고, 필요하면 rank blend 또는 분포 기반 boost로 바꾼다.
- query 부품이 없으면 baseline과 rerank 결과가 동일해야 한다.
- v1의 기존 strict filtering은 변경하지 않는다.

> **pool 주의:** 현재 SQL은 embedding ROI 행에 `LIMIT pool_limit`을 적용한 뒤 case 단위로 dedup한다. 따라서 “Top-100”은 100 case가 아닐 수 있다. 견적 참조/성능 평가는 case dedup을 먼저 수행한 실제 100 case pool 기준으로 재측정한다.

> **모델 식별 주의:** corpus 후보 조회에 쓰는 YOLO model name/version은 settings에서 주입하고, 적재된 inference의 모델 식별자와 불일치하면 명시적으로 실패/경고해야 한다. 가중치를 교체한 뒤 stale 후보가 조용히 vector-only 처리되면 안 된다.

현재 응답에 추가된 정보:

- `corpusPartMatched`
- corpus candidate code / confidence / overlap
- vector similarity / reranked similarity
- `YOLO_PART_BOOSTED` 또는 `VECTOR_ONLY_FALLBACK`

multi-query 검토에서는 PAIRED query 8개 중 32/80 Top-10 슬롯에 boost가 적용됐고, 순위 변경 44건과 신규 Top-10 진입 22건이 있었다. 다만 YOLO는 vector pool 안의 후보를 재정렬할 뿐, 이미지 표현 자체를 바꾸지는 않는다.

## 6. 견적 산출의 현재 규칙

현재 `EstimateService`와 `PostgresCostCaseRepository`는 다음 방식으로 동작한다.

```text
query의 STRICT partCode
 + 검색이 전달한 referencedCaseIds
 → repair_case_item에서 같은 partCode 행 조회
 → 유효 WORK 비용을 case·part 단위로 집계
→ 서로 다른 유효 case가 현재 임시 기준 2개 이상이면 p25 / median / p75 산출
```

### 유효 WORK

`WORK` 행 중 다음 작업은 수리비 산출에 사용된다.

- `COATING`
- `SHEET_METAL`
- `EXCHANGE`
- `REPAIR`
- `OVERHAUL` 계열

다음은 총 수리비에서 제외된다.

- `REMOVE_INSTALL`(탈착)
- `ADJUSTMENT`, `TOWING`, `RESCUE`
- `NOT_APPROVED`
- 0원 또는 유효 작업비가 없는 행
- `EXCHANGE`가 포함됐는데 부품비 합계가 0 이하인 case

같은 case·같은 partCode에 유효 WORK가 있으면 작업비/도장비가 총 수리비에 포함된다. 같은 부품의 `PART_PRICE`도 있으면 부품비를 함께 더한다. `PART_PRICE`만 있고 유효 WORK가 없으면 작업비를 알 수 없으므로 현재 FULL_REPAIR 총 수리비에서는 제외한다. `EXCHANGE`의 부품비가 0이면 AS 원천의 부품비 결측이 총액을 왜곡하므로 case 전체를 제외한다.

> **표본 정책:** 현재 로컬 작업트리는 `MIN_CASE_COUNT=3`이고, 최신 `origin/develop`의 `30671b4`는 기능 제공을 위한 임시값 `2`다. 2 case 결과는 반드시 LOW 신뢰도로 표시한다. 전수 적재 후 운영 목표는 3 case 이상이다. 아래 표에는 어느 임계값으로 측정했는지 항상 함께 기록한다.

## 7. 견적 데이터 현황

로컬 DB read-only 확인 결과:

| 항목 | 수량 |
| --- | ---: |
| `repair_case` | 1,000 case |
| AI-Hub 견적 case | 1,000 case |
| `repair_case_item` | 15,684 행 |
| 견적 item을 가진 case | 991 case |
| WORK 행 | 14,458 |
| PART_PRICE 행 | 1,038 |

원천 견적 JSON은 `aihub_estimate_raw`에 125,006건 단위로 별도 보존할 수 있으나, **현재 검색·비용 산출이 직접 참조하는 정규화 corpus는 위 1,000 case**다. 원천 보존 건수와 `repair_case` 적재 건수를 혼동하지 않는다.

PART_PRICE가 있는 case·part 묶음은 461개이고, 그중 유효 WORK가 없는 묶음은 83개(약 18%)다. 따라서 전체 견적서가 작업비 없이 비어 있는 문제는 아니다.

문제는 **검색된 후보와 동일 부품의 유효 WORK 사례가 충분히 교집합을 이루지 않는 것**이다.

특히:

- `ROCKER_PANEL_L/R`는 각각 6/12개의 PART_PRICE 행이 있으나 유효 WORK는 0개다.
- 로커패널 18 case의 same-case sibling WORK association audit은 `DIRECT_MATCH=0`, `POSSIBLE_MATCH=0`, `CONFLICT=18`이었다. 다른 부품의 작업비를 로커패널에 합산하면 안 된다.
- `FRONT_BUMPER`, `REAR_BUMPER`는 유효 비용 사례가 비교적 풍부하다.

## 8. YOLO와 견적 연결 전수 audit

산출물:

- `outputs/ab_eval_2026-09-18/v2_yolo_full_repair_coverage_audit.json`
- `outputs/ab_eval_2026-09-18/v2_yolo_full_repair_coverage_by_query.csv`
- `outputs/ab_eval_2026-09-18/v2_yolo_full_repair_coverage_audit.html`

전수 audit의 canonical detection query는 994개였고, 그중 YOLO PAIRED query는 410개였다. 아래의 2 case 값은 최신 develop의 **임시 기능 기준**으로 CSV를 재집계한 값이며 LOW 신뢰도 결과다.

```text
994 전체 query
→ 410 query: YOLO 부품 확정
→ 250 query: Top-100 ROI pool 안에 같은 부품의 유효 WORK case 1개 이상
→ 209 query: 유효 WORK case 2개 이상
→ 122 query: 그중 YOLO rerank Top-10에서도 견적 가능
```

| 지표 | 최소 3 case: 로컬 과거 실험 | 최소 2 case: 운영 기준 재집계 |
| --- | ---: | ---: |
| vector Top-10 FULL_REPAIR estimable | 10 | 36 |
| YOLO rerank Top-10 FULL_REPAIR estimable | 71 | 122 |
| 증가 | +61 | +86 |
| 전체 994 query 대비 YOLO rerank 가능 비율 | 7.1% | 12.3% |
| Top-100에 2개 이상 있으나 Top-10 밖인 query | - | 87 |

`122 → 209`의 87개는 데이터가 이미 후보 pool 안에 있으나 검색 표시 Top-10 밖에 있어 탈락한 경우다. 별도 `estimateReferencedCaseIds` 경로가 회수할 수 있는 구간이다. 전수 적재 후 최소 표본을 3 case로 올리면 이 수치는 다시 측정한다.

부품별 편중이 매우 크다.

| 부품 | query | pool 유효 WORK 있음 | pool 3건 이상 | Top-10 estimable (최소 2) |
| --- | ---: | ---: | ---: | ---: |
| FRONT_BUMPER | 122 | 119 | 81 | 69 |
| REAR_BUMPER | 114 | 111 | 88 | 51 |
| ROCKER_PANEL_R | 52 | 0 | 0 | 0 |
| HEAD_LIGHT_L | 22 | 1 | 0 | 0 |
| FRONT_WHEEL_R | 13 | 3 | 0 | 0 |
| FRONT_FENDER_L | 10 | 4 | 0 | 0 |
| SIDE_MIRROR_R | 12 | 0 | 0 | 0 |

YOLO 부품 확정 실패는 584/994 query이며, 이는 검색·견적 후보 수와 별개인 **부품 검출 문제**다. v1은 annotation part code가 있으므로 이 손실이 없다. 따라서 v1/v2 최종 비교에는 부품 확정률도 포함한다.

YOLO는 비용 연결에 실질적으로 도움이 되지만, 현재 1,000 case 견적 corpus만으로 모든 부품의 FULL_REPAIR 견적을 제공할 수는 없다. 3개 이상 후보가 확보되는 173 query 중 169개가 FRONT/REAR_BUMPER다.

## 9. PART_PRICE_ONLY fallback 검토

`ENABLE_PART_PRICE_REFERENCE=false`를 기본값으로 둔 PART_PRICE_ONLY 참고값 경로를 구현·검토했다.

계약:

- FULL_REPAIR의 `estimable`, `totals`, `itemTotal`, `repairMethod`에는 포함하지 않는다.
- PART_PRICE_ONLY는 작업비·도장비가 제외된 **부품비 참고값**으로만 제공한다.
- 로컬 구현의 runtime `MIN_CASE_COUNT` 정책을 그대로 따른다. 운영 기준은 최소 2 case다.

10-query sample 평가에서는:

| 항목 | query 수 |
| --- | ---: |
| FULL_REPAIR 가능 | 1 |
| PART_PRICE_ONLY 참고 가능 | 1 |
| 둘 중 하나라도 비용 제공 가능 | 1 |

이번 표본에서는 제공률 개선 근거가 없었으므로 flag는 비활성으로 유지한다.

## 10. 현재 권장 견적 산출: 2단계

전수 적재 이전인 현재 조건에서, 견적 산출을 다음처럼 계층화한다. 단계 A와 B는 총 수리비(`FULL_REPAIR`)만 대상으로 하며, 동일한 유효 WORK 정책을 공유한다. 최소 표본은 현재 임시 2 case(LOW)이며 전수 적재 후 3 case 이상으로 올린다.

### A. 유사 사례 기반 견적 — 우선

```text
검색 Top-100
→ 같은 query/corpus YOLO partCode
→ 같은 partCode의 유효 WORK 비용 case 현재 최소 2개(LOW), 전수 적재 후 최소 3개
→ 유사 사례 기반 FULL_REPAIR p25 / median / p75
```

검색 화면 Top-10과 견적 참조 사례는 다를 수 있다. `estimateReferencedCaseIds`는 표시 Top-10과 섞지 않으며, "검색 유사 사례"와 "견적 참조 사례"를 화면에서 명확히 구분한다.

이 단계는 임시 2 case 기준 `122 → 209`의 87 query를 회수할 수 있는 가장 낮은 비용의 보완책이다. 기존 로컬 3 case 평가에서는 71 → 173으로 검증됐으며, 최신 develop 병합 후 두 임계값을 분리해 재측정한다.

### B. 동일 부품 통계 기반 견적 — A가 부족할 때의 후보

단계 A에서 현재 임시 최소 2 case를 못 찾으면, 현재 DB 전체 1,000 case에서 같은 `part_code`의 유효 WORK 사례를 찾는 fallback을 검토한다.

권장 완화 순서:

```text
동일 모델
→ 동일 가격대
→ 전체 차량
```

이는 “유사 이미지 사례”가 아니라 “동일 부품 통계 기반”임을 응답/UI에서 명시해야 한다. 현재는 최소 2 case(LOW)를 적용하고, 전수 적재 후 3 case 이상으로 올린다. 로커패널처럼 DB 전체에 유효 WORK가 0인 부품에는 적용할 수 없다.

### C. 부품비 참고 — 선택적

유효 WORK 없이 PART_PRICE만 있으면 총 수리비 대신 부품비 참고 범위만 별도로 표시할 수 있다. 현재는 flag off 상태다.

## 11. 확정/보류 사항

### 확정

- v1/v2 병렬 유지
- v2 원 `part_code`는 `NULL` 유지
- repair hint는 검색/정답/비용 근거로 사용 금지
- YOLO 후보는 별도 테이블에 보존
- YOLO 부품 후보는 원 annotation과 분리해 보존
- FULL_REPAIR 최소 사례 수는 현재 2 case(LOW)이며, 전수 적재 후 3 case 이상으로 상향
- 로커패널에 다른 부품 작업비를 합산하지 않음

### 보류

- v2 pipeline 활성화
- 운영 boost 값 (`0.03` 또는 `0.05`) 확정
- `ENABLE_PART_PRICE_REFERENCE=true` 전환
- `estimateReferencedCaseIds` 로컬 구현은 완료됐고 flag는 false다. flag=true 실제 API E2E 및 프론트 구분 표시 확인
- 동일 부품 통계 기반 fallback 구현 및 활성화
- v1 STRICT / v2 vector / v2 YOLO의 공정한 재평가
- Top-N 거리 분포·pool case dedup·검색 latency 검증

## 12. 관련 검토 산출물

- `outputs/ab_eval_2026-09-18/guide_good_v2_yolo_boost_sweep.html`
- `outputs/ab_eval_2026-09-18/guide_good_v2_yolo_boost_strategy_ab.html`
- `outputs/ab_eval_2026-09-18/v2_yolo_flat005_multi_query_review.html`
- `outputs/ab_eval_2026-09-18/v2_yolo_flat005_rank_diff_review.html`
- `outputs/ab_eval_2026-09-18/v2_yolo_estimate_candidate_pool_audit.html`
- `outputs/ab_eval_2026-09-18/pool_only_estimate_reference_review.html`
- `outputs/ab_eval_2026-09-18/repair_case_item_part_cost_coverage_audit.html`
- `outputs/ab_eval_2026-09-18/rocker_panel_case_work_association_audit.html`

## 13. 별도: S3 Key 결정

AI-Hub 사례 이미지는 기존 서비스 버킷의 실제 Key 규칙을 유지한다. 문서상 신규 builder 규칙으로 기존 객체를 임의 이전하지 않는다.

확인된 기존 예시는 다음과 같다.

```text
repair-cases/AIHUB_AS/as-0000160/0406472/original.jpg
```

S3 전수 업로드/중복 방지 작업은 corpus/견적 로직과 독립적으로 수행한다. DB의 source image reference로 원본 파일을 찾고, 이미 존재하는 object는 `head-object` 등으로 확인 후 건너뛴다.
