# 검색 corpus · YOLO · 견적 연결 검증 정리

작성일: 2026-09-18
범위: AI-Hub 사례 검색 corpus, DINOv2 embedding, YOLO 부품 후보, 유사 사례 기반 견적 연결

## 1. 결론 요약

- 검색 corpus는 `DAMAGE_PART` 기반 v1과 `DAMAGE` 기반 v2를 **동시에 유지**한다. 운영 활성화는 아직 하지 않았다.
- v2는 손상 ROI 자체를 검색 대상으로 하므로 검색 Recall 계열 수치는 좋게 나왔지만, 초기 `repair hint` 기반 평가는 이미지별 정답이 아니어서 검색 품질 지표로 사용할 수 없다.
- YOLO는 v2 `DAMAGE` ROI에 보이는 부품 후보를 별도 테이블에 저장하고, v2 검색 결과를 **soft rerank**하는 용도다. v2의 원래 `part_code`는 계속 `NULL`이다.
- YOLO rerank는 v2 후보 안에서 같은 부품 case를 앞으로 당기는 보조 신호로 사용한다. YOLO를 v2 `part_code`에 직접 반영하거나 hard filter로 쓰지 않는다.
- 현재 코드의 최소 표본 2 case는 DEV 기능 검증을 위한 **LOW 신뢰도 임시값**이다. 전수 적재 후 운영 견적은 같은 부품·같은 작업 방식의 가까운 유사 사례를 **최소 10 case** 확보할 때만 제공하는 것을 목표로 한다.
- TRAIN_ONLY 39,676 case의 검색·견적 기본 행, v2 DAMAGE feature·YOLO 후보·DINOv2 embedding 전수 처리가 로컬 DB에서 완료됐다. v2는 여전히 inactive이며, 작업 방식 분리·golden demo·API/프론트 E2E 검증 후에만 운영 전환을 판단한다.

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

> **v2 YOLO 견적 표본 정책:** legacy 경로의 `MIN_CASE_COUNT=2`는 유지한다. v2 YOLO 견적 참조 경로는 `YOLO_ESTIMATE_MIN_CASES=5`, `YOLO_ESTIMATE_MAX_CASES=30`, `YOLO_ESTIMATE_CANDIDATE_K=200`을 MVP 기본값으로 사용한다. N은 정확한 사례 수가 아니라 가까운 유효 사례를 쓰는 상한이다.

## 7. 견적 데이터 현황

전수 적재 전 DEV 기준의 로컬 DB read-only 확인 결과:

| 항목 | 수량 |
| --- | ---: |
| `repair_case` | 1,000 case |
| AI-Hub 견적 case | 1,000 case |
| `repair_case_item` | 15,684 행 |
| 견적 item을 가진 case | 991 case |
| WORK 행 | 14,458 |
| PART_PRICE 행 | 1,038 |

원천 견적 JSON은 `aihub_estimate_raw`에 **125,006건**, 원천 견적 행은 **1,716,713건** 전수 보존돼 있다. `aihub_estimate_raw.external_ref = repair_case.external_ref`로 연결할 수 있지만, **현재 검색·비용 산출이 직접 참조하는 정규화 corpus는 위 1,000 case**다. 원천 보존 건수와 `repair_case` 적재 건수를 혼동하지 않는다.

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

`122 → 209`의 87개는 데이터가 이미 후보 pool 안에 있으나 검색 표시 Top-10 밖에 있어 탈락한 경우다. 별도 `estimateReferencedCaseIds` 경로가 회수할 수 있는 구간이다. 전수 적재 후 운영 최소 표본 10 case 기준으로 이 수치를 다시 측정한다.

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

### TRAIN_ONLY 전수 corpus 재측정 (2026-09-19~20)

전수 v2 corpus 구축 후 같은 canonical query 994개를 재실행했다. query YOLO 결과는 994개 중 `PAIRED` 410개였고, corpus 검색은 case-dedup Top-100·YOLO flat boost `0.05`로 수행했다. 아래 수치는 **같은 부품 + 유효 FULL_REPAIR** 기준이다. 아직 `EXCHANGE_INCLUDED`/`REPAIR_FAMILY` 작업 방식 분리를 적용하기 전이므로 운영 최소 10 case 확정값으로 사용하지 않는다.

| 최소 사례 수 | pure vector Top-10 | YOLO Top-10 | Top-100 탐색 pool |
| ---: | ---: | ---: | ---: |
| 3 | 55 | 187 | 226 |
| 5 | 6 | 111 | 192 |
| 10 | 0 | 2 | 122 |

- 10건 기준에서 Top-10에는 2개뿐이지만 Top-100에는 122개가 있다. 검색 표시 Top-10과 견적 참조 사례를 분리하는 `estimateReferencedCaseIds` 경로가 필요한 직접 근거다.
- FRONT_BUMPER 122 query 중 YOLO Top-10 3건 이상은 95개, REAR_BUMPER 114 query 중 89개다. 범퍼 스크래치 golden demo 후보 공급은 충분하다.
- 이 결과는 repair hint를 사용하지 않았고 DB write도 하지 않았다. `PART_PRICE row(s) with no mapped WORK` 메시지는 로커패널 부품비만 있는 행을 FULL_REPAIR에서 제외했다는 알림이다.

K=200으로 확장한 재측정에서는 Top-200 pool의 유효 FULL_REPAIR 5건 이상 query가 227개, 10건 이상 query가 189개로 늘었다. 최대 사례 수 10/15/20/30의 raw 정답 비용 평가에서는 65개 평가 가능 query 기준 MdAPE가 20.24%/22.55%/22.66%/21.75%, WAPE가 28.70%/28.15%/28.37%/28.63%였다. 큰 정확도 저하 없이 더 많은 근거를 보존하는 MVP 판단으로 v2 비용 참조 상한은 30건으로 둔다. 상세 결과는 `outputs/ab_eval_2026-09-20/estimate_reference_count_accuracy_k200_raw_truth.html`에 있다.

### 최대 참조 사례 수 재검증 — TRAIN_ONLY query (2026-09-20)

앞의 raw 정답 평가는 `2.Validation` case 를 query 로 써서 평가 가능 query 가 65개에 그쳤다.
그 case 들은 corpus(TRAIN_ONLY)에 없어 정답 비용을 `aihub_estimate_raw` 에서 읽어야 했기 때문이다.
표본을 늘리려고 **corpus 안의 TRAIN_ONLY case 를 query 로 쓰는 leave-one-case-out** 으로 다시 측정했다.
query 본인 `case_id` 는 `exclude_case_id` 로 후보에서 빼고, 정답은 **같은 case + 같은 `part_code`** 의
유효 FULL_REPAIR 비용을 DB 에서 읽는다. 사고 견적 총액은 다른 부품 비용이 섞이므로 정답으로 쓰지 않는다.

| 최대 사례 수 | Coverage | MdAPE | WAPE | p25~p75 적중률 |
| ---: | ---: | ---: | ---: | ---: |
| 5 | 88.6% | 25.55% | 32.07% | 32.3% |
| 10 | 88.6% | 27.03% | 31.46% | 37.1% |
| 15 | 88.6% | 28.51% | 31.90% | 38.7% |
| 20 | 88.6% | 28.34% | **31.29%** | **40.3%** |
| 30 | 88.6% | 27.90% | 31.74% | 38.7% |
| 50 | 88.6% | 27.93% | 31.48% | 38.7% |

평가 query 162개 · K=200 · flat boost 0.05 기준이다.

- **N 은 정확도에 영향이 없다.** WAPE 폭이 0.78%p(31.29~32.07%)로, 10건과 50건이 사실상 같다.
  세 지표의 최적값이 서로 다른 N 에 흩어져 있어 일관된 승자가 없다. 앞의 65건 실험(WAPE 폭 0.55%p)과
  같은 결론이다.
- **Coverage 는 N 과 무관하게 88.6%** 다. 산정 가능 여부는 상한이 아니라 최소 사례 수(5건)가 정하므로,
  상한을 30건으로 두어도 `유사 견적 사례 부족` 이 늘지 않는다. 이것이 `YOLO_ESTIMATE_MAX_CASES=30`
  의 근거다.
- MdAPE 가 5건에서 가장 낮은 것은 정확해서가 아니라 표본이 적어 분산이 큰 쪽이다. 같은 5건에서
  구간 적중률이 32.3% 로 가장 나쁘다.
- TRAIN query 는 corpus 와 촬영 환경이 같아 유리할 것으로 봤으나 WAPE 는 오히려 31~32% 로
  raw 정답 평가(28%)보다 높게 나왔다. 절대 정확도를 인용할 때는 보수적인 이 수치를 쓴다.

> **구간 표시 문구:** `p25~p75` 의 실제 적중률은 32~40% 로 이론값 50% 를 밑돈다. 참조 사례의 분포가
> 실제 비용 분포보다 좁다는 뜻이며, N 을 바꿔도 해결되지 않는다. 따라서 화면 문구는 "이 범위에 들어온다"
> 가 아니라 **"비슷한 사례의 절반이 이 범위였다"** 로 관측 사실을 서술한다. 계산은 바꾸지 않는다.

재현 명령은 다음 순서다. 2단계만 GPU 를 쓰며, 세 단계 모두 DB·feature·embedding 을 변경하지 않는다.

1. `pipeline/jobs/evaluation/build_eval_query_manifest.py` — corpus 안에서 해당 부품 비용을 가진 case 의
   DAMAGE 이미지를 골라 query manifest 를 만든다
2. `pipeline/jobs/evaluation/audit_yolo_full_repair_coverage.py --candidate-k 200` — YOLO·임베딩·검색
3. `pipeline/jobs/evaluation/audit_estimate_reference_count_accuracy.py` — 정답 비교·지표 산출

산출물: `outputs/ab_eval_2026-09-20/train_reference_count_accuracy.html`

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

## 10. 현재 권장 견적 산출: 유사 사례 우선

이미지 검색의 목적은 같은 부품뿐 아니라 손상 범위·심각도가 비슷한 실제 사례의 견적을 참고하는 것이다. 따라서 Top-100은 비용을 평균내는 대상이 아니라 **견적 참조 사례를 찾는 탐색 pool**이다. 최소 10 case는 전수 적재 후 audit으로 확정할 **목표값**이며, 현재 1,000 case DEV 결과만으로 운영값을 고정하지 않는다.

```text
case 단위로 dedup한 검색 Top-100
→ 같은 query/corpus YOLO partCode
→ EXCHANGE_INCLUDED 또는 REPAIR_FAMILY 중 같은 작업 방식 그룹
→ 유사도 순으로 가까운 사례를 선택
→ 전수 적재 후 최소 10 case가 있을 때만 FULL_REPAIR p25 / median / p75 제공
```

- 화면의 검색 유사 사례는 Top-10만 표시한다.
- 견적 참조 사례는 화면 Top-10 밖에 있을 수 있으며, `estimateReferencedCaseIds`로 별도 전달·표시한다.
- Top-100 pool의 순서는 §5의 v2 YOLO soft rerank를 적용한 순서를 사용한다. query/corpus YOLO part가 없으면 pure vector 순서를 그대로 사용한다.
- 비용 참조는 가까운 사례를 우선 선택한다. Top-100 전체를 하나의 비용 분포로 평균내지 않는다.
- `EXCHANGE_INCLUDED`와 `REPAIR_FAMILY`는 한 분포로 합치지 않는다. 두 그룹 median 차이 audit의 중앙값은 93,430원이었다.
- 부품·작업 방식별로 최소 10 case를 못 채우면 먼 사례나 DB 전체 통계를 억지로 섞지 않고 `유사 견적 사례 부족`으로 처리한다.

작업 방식 그룹은 다음처럼 정의한다.

- `EXCHANGE_INCLUDED`: 같은 case·부품의 유효 WORK에 `EXCHANGE`가 하나라도 포함된 경우
- `REPAIR_FAMILY`: `EXCHANGE`는 없고 `COATING`·`SHEET_METAL`·`REPAIR`·`OVERHAUL` 중 하나 이상이 있는 경우
- `OTHER_VALID_WORK`: 위 두 그룹에 속하지 않는 유효 WORK

### 현재 DEV에서의 탐색 범위 진단

PAIRED query 410개에서 유효 FULL_REPAIR case를 확보한 수는 다음과 같다. 이는 **YOLO boost=0인 v2 pure-vector baseline**의 현재 1,000 case corpus 공급량 진단이며 운영 기준은 아니다. §8의 YOLO rerank Top-10 수치와 직접 섞어 비교하지 않고, 전수 적재 후 동일 조건으로 다시 측정한다.

| candidate K | 유효 비용 2건 이상 | 유효 비용 3건 이상 |
| ---: | ---: | ---: |
| 10 | 36 | 10 |
| 20 | 71 | 39 |
| 30 | 96 | 57 |
| 50 | 151 | 108 |
| 100 | 209 | 173 |

K=100 안에서 유효 비용 case가 5건 이상인 query는 94개(9.5%), 10건 이상인 query는 21개(2.1%)다. 따라서 최소 10 case는 전수 적재 후 5건·10건 확보율과 실제 이미지 유사성 audit을 함께 보고 확정한다.

query마다 입력 이미지에서 부품·손상 범위가 보이는 정도가 달라 고정 rank cutoff만으로 적합성을 보장할 수 없다. 전수 적재 후에도 Top-100 안에서 유사도 순으로 가까운 10 case를 선택하고, 시각 검토와 실제 분포 audit으로 세부 cutoff를 확정한다.

### 동일 부품 통계 및 PART_PRICE_ONLY

DB 전체의 동일 부품 통계 fallback은 유사 이미지 사례 견적과 근거가 다르므로 **현재 구현·활성화하지 않는다**. 제품 요구가 생길 때만 "동일 부품 통계 참고값"으로 명확히 분리해 재검토한다.

유효 WORK 없이 PART_PRICE만 있는 경우의 부품비 참고 경로도 `ENABLE_PART_PRICE_REFERENCE=false` 상태로 유지한다.

## 11. 확정/보류 사항

### 확정

- v1/v2 병렬 유지
- v2 원 `part_code`는 `NULL` 유지
- repair hint는 검색/정답/비용 근거로 사용 금지
- YOLO 후보는 별도 테이블에 보존
- YOLO 부품 후보는 원 annotation과 분리해 보존
- v2 YOLO 비용 참조는 K=200·최소 5 case·최대 30 case를 사용하며, legacy 경로의 2 case 기준과 분리
- 로커패널에 다른 부품 작업비를 합산하지 않음
- 견적 참조는 Top-200 탐색 pool 안의 가까운 동일 부품·동일 작업 방식 사례만 사용
- 최대 참조 사례 수는 정확도에 영향이 없다 — 65건·162건 두 실험에서 WAPE 폭이 각각 0.55%p·0.78%p였다.
  산정 가능 여부는 상한이 아니라 최소 사례 수(5건)가 정한다
- 비용 범위는 "비슷한 사례의 절반이 이 범위였다"로 서술한다. `p25~p75` 실제 적중률이 32~40%라
  "이 범위에 들어온다"는 표현은 쓰지 않는다

### 보류

- v2 pipeline 활성화
- 운영 boost 값 (`0.03` 또는 `0.05`) 확정
- `ENABLE_PART_PRICE_REFERENCE=true` 전환
- `estimateReferencedCaseIds` 로컬 구현은 완료됐고 flag는 false다. flag=true 실제 API E2E 및 프론트 구분 표시 확인
- 동일 부품 통계 기반 fallback 구현 및 활성화
- v1 STRICT / v2 vector / v2 YOLO의 공정한 재평가
- Top-N 거리 분포·pool case dedup·검색 latency 검증
- 같은 작업 방식 분리(`EXCHANGE_INCLUDED` / `REPAIR_FAMILY`)를 적용한 5/10/30 case 재측정
- 범퍼 스크래치 golden demo 시각 검토 및 API/프론트 E2E

## 12. 원본 견적 전수 공급량 audit 및 전수 적재 계획

`aihub_estimate_raw` 원본 125,006건을 DB write 없이 표준화 규칙으로 전수 검사했다. 이 audit은 DB 전체에 비용 사례가 있는지를 확인하는 것이며, 검색 Top-100 안에서 이미지가 충분히 유사한지는 전수 embedding 후 별도로 재검증해야 한다.

| 구분 | 결과 |
| --- | --- |
| 원본 견적서 문서 | 125,006건 |
| 원본 견적 행 | 1,716,713건 |
| 원본 전체에서 유효 FULL_REPAIR 10건 이상 확보 | FRONT/REAR_BUMPER, FENDER 좌·우, WHEEL 좌·우, HEAD_LIGHT 좌·우, SIDE_MIRROR 좌·우 |
| 원본 전체에서도 10건 미만 | ROCKER_PANEL_L 1건, ROCKER_PANEL_R 4건, TAIL_LAMP_L/R 0건 |

DEV 표본에서 부족했던 REAR_WHEEL_L/R는 원본에서 각각 204/274건, HEAD_LIGHT_L/R는 378/400건으로 확인돼 전수 적재 효과를 기대할 수 있다. 반면 로커패널·테일램프는 전수 이미지 적재만으로 최소 10 case 정책을 충족할 수 없다.

### 전수 적재 범위

split manifest 전체 55,363건은 운영 corpus 수가 아니다.

| 구분 | case 수 | 처리 |
| --- | ---: | --- |
| `TRAIN_ONLY` | 39,676 | 현재 전수 검색·견적 corpus 대상 |
| `VALIDATION_ONLY` | 956 | DEMO/EVAL query용, corpus 제외 |
| `MIXED` | 14,731 | TRAIN·VALIDATION 양쪽에 있는 동일 case. 정량 EVAL에서는 제외하고, 평가 종료 후 TRAIN 쪽 이미지로 운영 추가 여부를 결정 |

전수 적재용 manifest는 `outputs/data_validation/search_case_manifests_2026-09-11/search_train_only_cases.csv`이며, 39,676건 모두 `TRAIN_ONLY`, 중복 `case_id`는 0건이다. 기존 DEV 1,000건은 이 목록에 포함된다.

`load_search_data.py`는 기본적으로 DEV manifest만 허용한다. 이 전수 manifest를 사용할 때는 `--manifest-scope train-only`를 반드시 지정하며, `DEV`·`POOL` 이외 purpose 또는 `TRAIN_ONLY` 이외 split이 있으면 적재 전에 실패한다.

### 전수 적재 진행 상태 및 남은 순서

1. **완료** — TRAIN_ONLY 39,676 case를 `repair_case` / `repair_case_item` / `repair_case_image`와 v1 feature로 idempotent upsert했다.
   - `DAMAGE` 이미지 109,011장, `DAMAGE_PART` 이미지 59,286장
   - `repair_case_item` 393,339행, v1 damage feature 205,977건
   - case 39,043건은 견적 부품 후보를 하나 이상 보유하고, 39,676건 모두 DAMAGE geometry를 보유한다.
   - 상태 `PARTIAL`은 case·이미지 적재 실패가 아니라 원천 견적 item 정규화 보류를 뜻한다. `unmapped_part` 164,734행, work type 누락 9,503행, 수치 범위 오류 2,721행은 현재 유효 비용 행에서 제외된다. 이 전체 backlog를 즉시 해결하지 않고, 다음 coverage audit에서 데모 대상 부품에 실제 부족이 있을 때만 해당 원시 명칭을 우선 보완한다.
2. **완료** — v2 `DAMAGE` feature 347,086건을 생성했다. canonical 계약은 `part_code=NULL`·`pair_status=UNPAIRED`이며 repair hint는 분리 테이블에만 저장했다.
3. **완료** — v2 DAMAGE 원본 이미지 109,011장에 YOLO 부품 후보를 전수 추론했다. 신규 104,757장 처리, 기존 DEV 4,254장 skip, YOLO 오류 0건이다. 소요 시간은 1시간 21분이었다.
4. **완료** — searchable v2 feature 294,519건의 DINOv2 embedding을 생성했다. 기존 13,375건을 재사용하고 신규 281,144건을 생성했으며 실패 0건, 소요 시간은 2시간 46분이었다.
5. **1차 완료** — case-dedup Top-200과 raw 정답 비용 audit으로 v2 YOLO 비용 참조 정책을 최소 5·최대 30 case로 정했다. 작업 방식 분리 적용 뒤 동일 기준을 재검증한다.
6. **보류** — 검색·견적·S3 key 정합성, API/프론트 E2E 및 v2 활성화 여부를 최종 결정한다.

S3 원본 이미지는 이미 전수 업로드됐으므로, 위 적재는 S3 재업로드가 아니라 DB의 `storage_key`와 이미지 행을 정합화하는 작업이다.

MVP 데모의 상세 범위·완료 조건은 [MVP 유사 사례 견적 데모 실행 기준](MVP-유사-사례-견적-데모-실행-기준.md)에서 관리한다.

## 13. 관련 검토 산출물

- `outputs/ab_eval_2026-09-18/guide_good_v2_yolo_boost_sweep.html`
- `outputs/ab_eval_2026-09-18/guide_good_v2_yolo_boost_strategy_ab.html`
- `outputs/ab_eval_2026-09-18/v2_yolo_flat005_multi_query_review.html`
- `outputs/ab_eval_2026-09-18/v2_yolo_flat005_rank_diff_review.html`
- `outputs/ab_eval_2026-09-18/v2_yolo_estimate_candidate_pool_audit.html`
- `outputs/ab_eval_2026-09-18/pool_only_estimate_reference_review.html`
- `outputs/ab_eval_2026-09-18/repair_case_item_part_cost_coverage_audit.html`
- `outputs/ab_eval_2026-09-18/rocker_panel_case_work_association_audit.html`
- `outputs/ab_eval_2026-09-18/damage_type_work_method_compatibility_audit.html`
- `outputs/ab_eval_2026-09-18/estimate_method_stratification_audit.html`
- `outputs/ab_eval_2026-09-18/estimate_reference_similarity_review.html`
- `outputs/ab_eval_2026-09-18/full_source_estimate_supply_audit.html`

## 14. 별도: S3 Key 결정

AI-Hub 사례 이미지는 기존 서비스 버킷의 실제 Key 규칙을 유지한다. 문서상 신규 builder 규칙으로 기존 객체를 임의 이전하지 않는다.

확인된 기존 예시는 다음과 같다.

```text
repair-cases/AIHUB_AS/as-0000160/0406472/original.jpg
```

S3 전수 업로드는 완료됐다. 향후 적재 job은 S3 객체를 재업로드하지 않고 DB의 source image reference와 storage key만 정합화한다.
