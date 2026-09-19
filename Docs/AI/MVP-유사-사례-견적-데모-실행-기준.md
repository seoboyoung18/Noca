# MVP 유사 사례 견적 데모 실행 기준

작성일: 2026-09-19  
상위 문서: [검색 corpus · YOLO · 견적 연결 검증 정리](검색-견적-corpus-YOLO-검증-정리.md)

## 1. 이번 MVP의 목표

모든 차량·모든 손상 유형에 견적을 내는 것이 이번 범위는 아니다. 사용자가 부품과 손상 부위가 잘 보이게 입력한 **일반적인 범퍼 스크래치**에서 다음 흐름을 안정적으로 보이는 것이 목표다.

```text
입력 사진 (예: 아반떼급 전·후 범퍼 스크래치)
→ DAMAGE ROI 생성
→ YOLO가 보이는 범퍼를 PAIRED로 확인
→ 같은 부품의 외관·손상 범위가 가까운 사례를 검색
→ 가까운 유효 견적 사례 여러 건을 근거로 비용 범위를 제공
```

입력 가이드는 부품과 손상 부위가 함께 보이는 사진을 요구한다. 부품이 화면에 거의 없거나, 손상 범위가 식별되지 않는 입력까지 데모 성공 조건에 넣지 않는다.

## 2. 데모 성공 기준

데모 후보는 아래 조건을 모두 만족하는 실제 query로 고른다.

1. `FRONT_BUMPER` 또는 `REAR_BUMPER`가 YOLO `PAIRED`로 확인된다.
2. 검색 Top-10은 손상 ROI 기준으로 사람이 보기에 같은 범퍼·유사한 손상 유형/범위를 가진 사례가 다수다.
3. case-dedup Top-200 탐색 pool에서 같은 부품·같은 작업 방식 그룹의 가까운 `FULL_REPAIR` 사례가 최소 5건 이상인지 확인한다.
4. 비용은 전체 Top-200 평균이 아니라, 위 조건을 만족하는 사례 중 **유사도 순으로 가까운 최대 30건**만 사용해 p25 / median / p75로 표시한다.
5. 화면에는 검색 결과 Top-10과 견적 참조 사례를 구분하고, `유사 사례 N건 기준`이라는 근거를 함께 표시한다.

최소 5건을 채우지 못하면 억지로 먼 사례나 DB 전체 통계를 섞지 않고 `유사 견적 사례 부족`을 표시한다. 30건은 상한이며, 후보가 더 많아도 추가 평균에는 넣지 않는다. 이 값은 아래 raw 정답 비용 audit과 시각 검토를 함께 보고 정한 MVP 정책이다.

## 3. 비용 참조 원칙

이미지 유사도는 같은 부품뿐 아니라 손상 범위·심각도를 반영하기 위한 신호다. 그래서 후보를 단순히 많이 섞으면 안 된다.

- 검색 화면: 가까운 유사 사례 Top-10
- 견적 탐색: case-dedup Top-200. 화면에 보이지 않는 사례도 탐색에는 사용 가능하다.
- 견적 선택: query와 같은 YOLO 부품, 같은 작업 방식 그룹을 통과한 뒤 유사도 순으로 가까운 사례만 선택한다.
- 작업 방식은 `EXCHANGE_INCLUDED`와 `REPAIR_FAMILY`를 섞지 않는다.
- 현재 MVP에서는 DB 전체 동일 부품 통계 fallback과 `PART_PRICE_ONLY` fallback을 사용하지 않는다.

Top-100은 비용을 평균낼 범위가 아니라, Top-10 밖에도 있을 수 있는 **가까운 견적 참조 후보를 찾기 위한 범위**다.

## 4. 범위 밖 처리

다음은 오류가 아니라 정직하게 비용을 보류하는 경우다.

- YOLO가 부품을 찾지 못했거나 DAMAGE ROI와 매칭되지 않은 경우
- 로커패널·테일램프처럼 원본 견적서 전체에서도 유효 사례가 희소한 부품
- 같은 부품이어도 유효 WORK 또는 같은 작업 방식 그룹 사례가 부족한 경우
- 흐리거나 부품·손상이 잘 보이지 않는 입력

이 경우에도 검색 결과는 pure vector로 제공할 수 있지만, 비용 범위의 근거가 부족하면 견적을 만들어 내지 않는다.

## 5. 전수 적재 이후 검증 순서

1. **완료** — v2 `DAMAGE` feature 전수 생성 (`part_code=NULL` 계약 유지)
2. **완료** — v2 이미지 YOLO 전수 추론 및 후보 저장
3. **완료** — v2 DINOv2 embedding 전수 생성
4. **1차 완료** — case-dedup Top-100 기준의 같은 부품·유효 FULL_REPAIR 확보율 audit
5. 작업 방식별(`EXCHANGE_INCLUDED` / `REPAIR_FAMILY`) 유효 `FULL_REPAIR` 5건/10건 확보율 audit
6. 범퍼 스크래치 query 여러 장을 골라 검색 Top-10과 견적 참조 이미지를 HTML로 육안 검토
7. **AI 단 E2E 완료 (2026-09-20)** — golden demo input 고정, flag-on 실측. 아래 절 참고. 프론트 분리 표시 확인은 남아 있다
8. 결과를 보고 v2 활성화와 운영 최소 표본 수를 결정

### AI 단 flag-on E2E 실측 (2026-09-20)

로컬 DB 전수 corpus에 운영 예정 설정을 그대로 넣고 `POST /analyze` → 콜백까지 측정했다.
query 는 golden demo input 인 `0507522_sc-195094.jpg` (FRONT_BUMPER / SCRATCHED / PAIRED)다.
`2.Validation` case 라 corpus 에 없으므로 자기 자신이 후보로 잡히지 않는다.

설정: `ANALYSIS_PROFILE=production` · `FEATURE_PIPELINE_VERSION_ID=2` ·
`ENABLE_YOLO_ESTIMATE_REFERENCES=true` · `YOLO_ESTIMATE_CANDIDATE_K=200` ·
`YOLO_ESTIMATE_MAX_CASES=30` · `YOLO_ESTIMATE_MIN_CASES=5`

| 항목 | 결과 | 확인 내용 |
| --- | --- | --- |
| `estimable` | `true` | 오류 없이 견적 산출 |
| `modelVersion` | `part-35ep/damage-60ep` | mock 이 아닌 실경로 |
| `refCaseTotal` · `referencedCaseIds` | **30** | pool 유효 47건에서 상한 30건으로 절삭 |
| `fallbackStage` | **`MODEL`** | 완화 없이 동일 차량명 사례만으로 30건 확보 |
| `confidenceGrade` | `LOW` | 표본 수가 아니라 탐지 신뢰도가 등급을 낮춘 경우 |
| `costDistribution` | p25 143,820 / median 155,760 / p75 202,352 | 20건 시각 검토값(140,102 / 160,750 / 184,100)과 같은 대역 |

**`referencedCaseIds` 가 30건이라는 점이 이 검증의 핵심이다.** `estimateReferencedCaseIds` 로 고른
사례를 기존 `referencedCaseIds` 필드로 내보내는 경로가 의도대로 동작하므로, **백엔드 DTO 를 바꾸지
않고도 기존 계약으로 비용·유사 사례 화면까지 연결된다.** 다만 프론트에서 "검색 유사 사례 Top-10"과
"견적 참조 사례"를 구분해 표시하는지는 별도 확인이 남아 있다.

### 응답 시간

| 조건 | 콜백까지 | 비고 |
| --- | ---: | --- |
| 1장 (첫 요청) | 7.28초 | YOLO 2모델·DINOv2 가중치 로드 포함 |
| 8장 (워밍업 후) | 3.12초 | 장당 0.39초 |

`202` 접수 응답은 두 경우 모두 0.02~0.03초다. 첫 요청이 더 느린 것은 모델 지연 로드 때문이며,
운영에서는 컨테이너 기동 후 첫 요청에만 해당하고 Dockerfile 의 헬스체크 `start-period=60s` 가 흡수한다.
**K=200·최대 30건에서도 장당 0.39초**라 "유사 사례 검색 2초" 기준에 여유가 있다.
이 수치는 GPU(RTX 4050 Laptop) 로컬 기준이며, CPU 로 도는 운영 AI 박스에서는 별도 재측정이 필요하다.

### 검증 중 확인된 운영 주의점

로컬 DB 에 `pipeline/sql/012~014` 가 적용돼 있지 않아 첫 시도가 실패했다. `vector_repository._price_tier`
는 요청에 `modelId` 가 있으면 `vehicle_model.price_tier` 를 무조건 조회하므로, 컬럼이 없으면 검색이
예외로 끝나고 콜백이 `INTERNAL` 로 온다. 기존 audit 들은 `model_id` 없이 검색해 이 경로를 타지 않아
문제가 드러나지 않았다. **운영 스모크는 반드시 `modelId` 를 포함해 호출해야 이 종류의 누락을 잡는다.**

## 6. 현재 데이터 상태

TRAIN_ONLY 39,676 case의 기본 정규화 적재, v2 feature·YOLO·embedding 전수 구축과 S3 원본 이미지 업로드는 완료됐다. 전수 v2 searchable embedding은 294,519건이며, 이 batch는 배포 환경에서 매 요청마다 수행하는 작업이 아니라 초기 corpus 구축 작업이다.

K=200 전수 audit에서는 YOLO Top-10 5건 이상 114 query, Top-200 pool 5건 이상 227 query가 확인됐다. 10건은 Top-10 2 query, Top-200 pool 189 query였다. 이는 검색 화면 Top-10과 견적 참조 Top-200을 분리할 필요를 보여 주지만, 작업 방식 분리 전 수치이므로 운영 보장 수치가 아니다.

### 참조 사례 상한 결정 (raw 정답 비용 audit)

Validation query는 TRAIN_ONLY corpus에 적재하지 않으므로, query 자체의 정답 비용은 `repair_case_item`이 아니라 전수 보존한 `aihub_estimate_raw.payload`에서 같은 `external_ref`·같은 YOLO 부품의 `FULL_REPAIR` 비용으로 읽었다. 검색 후보는 query 본인을 제외한 K=200 결과를 그대로 재사용했다.

| 상한 N | 평가 coverage | MdAPE | WAPE | 실제 비용의 p25~p75 적중률 |
| ---: | ---: | ---: | ---: | ---: |
| 10 | 85.5% | 20.24% | 28.70% | 43.08% |
| 15 | 85.5% | 22.55% | 28.15% | 44.62% |
| 20 | 85.5% | 22.66% | 28.37% | 46.15% |
| 30 | 85.5% | 21.75% | 28.63% | 43.08% |

- YOLO PAIRED·`case + part` dedup query는 230건, raw 정답 비용을 확보한 query는 76건, 실제 평가 가능 query는 65건이다.
- N은 정확히 N건을 강제하는 값이 아니라, 최소 5건을 만족하는 가까운 유효 사례를 **최대 N건**까지 쓰는 상한이다.
- MdAPE는 N=10이 가장 낮고 WAPE는 N=15가 가장 낮았으나, 차이가 작고 20~30건의 시각 검토에서도 외관 맥락이 납득됐다. MVP는 사용자에게 더 많은 실제 근거를 설명할 수 있도록 **최소 5건·최대 30건**을 채택한다.
- 산출물: `outputs/ab_eval_2026-09-20/estimate_reference_count_accuracy_k200_raw_truth.html`

원천 item 중 부품 정규화가 되지 않은 행은 남아 있다. 이는 후속 데이터 품질 backlog로 관리하되, 우선 전수 후 범퍼 데모의 실제 유효 견적 사례가 충분한지부터 검증한다.

### 검증 완료 golden demo 후보

아래 입력은 전수 v2 corpus에서 사람이 이미지와 견적 근거를 확인한 데모 후보다. 운영에서 이 파일 경로 자체를 하드코딩하지 않으며, API/프론트 E2E 검증용 고정 입력으로만 보존한다.

| 항목 | 기록 |
| --- | --- |
| 입력 원본 | `01.데이터_견적서보유/2.Validation/1.원천데이터/VS_damage/damage/0507522_sc-195094.jpg` |
| query ROI | 위 이미지의 `#0` DAMAGE ROI |
| YOLO 결과 | `FRONT_BUMPER` · `SCRATCHED` · `PAIRED` |
| 견적 근거 | Top-200 pool에서 같은 부품 유효 `FULL_REPAIR`를 유사도 순으로 최대 30건 사용; 최초 시각 검토는 상위 10건·20건으로 완료 |
| 비용 검토값 | p25 130,940원 · median 148,230원 · p75 184,050원 |
| 시각 검토 | 입력 이미지와 10개 근거 이미지가 범퍼 스크래치·`repair + coating` 작업 방식으로 대체로 납득됨 |
| 검토 화면 | `outputs/ab_eval_2026-09-19/golden_demo_front_bumper_scratch_estimate.html` |

이 후보는 최소 5건 기준을 넘기므로, 데모에서는 실제 사용 건수에 맞춰 **유사 사례 N건 기준**으로 비용 범위를 제시할 수 있다. 다른 입력도 같은 조건을 만족하는지 별도 E2E에서 확인해야 하며, 이 한 사례로 v2 운영 활성화를 확정하지는 않는다.

## 7. 배포 경계

로컬 전수 corpus 데이터는 Git merge에 포함되지 않는다. 브랜치에는 코드·migration·job·문서만 push하고, 배포 시에는 migration `010 → 011 → 015` 적용과 함께 DB dump/restore 또는 배포 환경 batch 실행으로 feature·YOLO·embedding 데이터를 별도로 옮긴다. v2 pipeline은 E2E 검증 전까지 inactive로 유지한다.

AI 단 flag-on E2E 는 2026-09-20 로컬에서 통과했다(§6). 남은 배포 순서는 다음과 같다.

1. 코드·문서 커밋·푸시·MR (머지는 아래 2번 이후)
2. 운영 DB 에 `pipeline/sql/010 · 011 · 015` 적용 후 `/home/ubuntu/.a307-applied-migrations` 에 기록
   — 기록이 없으면 `migration-guard` 가 백엔드·AI 배포를 **둘 다** 멈춘다. `012~014` 는 적용 완료 상태다
3. 운영 DB 전수 이관 — corpus 테이블만 `pg_dump -Fc`, restore 전 `ix_roi_hnsw` 를 떼고 COPY 후 재생성.
   BIGSERIAL 시퀀스 `setval` 을 빠뜨리지 않는다
4. MR 머지 → 자동 배포
5. `/etc/a307/ai.env` 갱신 후 **컨테이너 재생성** — `--env-file` 은 생성 시점에만 읽힌다

   ```
   FEATURE_PIPELINE_VERSION_ID=2
   ENABLE_YOLO_ESTIMATE_REFERENCES=true
   YOLO_ESTIMATE_CANDIDATE_K=200
   YOLO_ESTIMATE_MAX_CASES=30
   YOLO_ESTIMATE_MIN_CASES=5
   ```

6. `feature_pipeline_version.is_active` 로 v2 활성화 — **데이터 이관이 끝난 뒤 마지막에** 한다.
   corpus 가 없는 상태로 켜면 검색이 0건이 되어 현재보다 나빠진다
7. 운영 스모크 — 사고 1건을 `modelId` 포함해 호출하고 §6 의 네 항목(`estimable` · `refCaseTotal` ·
   `fallbackStage` · `modelVersion`)과 응답 시간을 확인한다. 운영 AI 박스는 CPU 라 응답 시간은
   로컬 수치와 다르게 나올 수 있다
