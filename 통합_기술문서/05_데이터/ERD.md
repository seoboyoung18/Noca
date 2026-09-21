# ERD

## 목적

테이블 관계를 Mermaid ER 다이어그램으로 나타낸다. 47개를 한 장에 담으면 읽을 수 없으므로
**그룹별로 나눈다**. 전체 개요는 [diagrams/ERD.md](../diagrams/ERD.md)에 있다.

## 현재 구현

### ① 회원 · 사고 · 분석

```mermaid
erDiagram
    member ||--o{ terms_agreement : "동의"
    member ||--o{ vehicle : "보유"
    vehicle_model ||--o{ vehicle : "모델"
    vehicle ||--o{ accident : "사고"
    accident ||--o{ accident_image : "사진"
    accident_image ||--o{ accident_image_asset : "변형"
    accident ||--o{ analysis_job : "분석 작업"
    analysis_job ||--o{ analysis_stage : "진행 단계"
    analysis_job ||--o{ analysis_image_result : "이미지별 결과"
    accident_image ||--o{ analysis_image_result : "대상"
    accident ||--o{ accident_review : "검수"

    member {
        bigint member_id PK
        varchar provider
        varchar provider_user_id
    }
    vehicle {
        bigint vehicle_id PK
        bigint member_id FK
        bigint model_id FK
        smallint model_year
    }
    accident {
        bigint accident_id PK
        bigint vehicle_id FK
        bigint snapshot_model_id "스냅샷"
        varchar snapshot_manufacturer "스냅샷"
        timestamptz hidden_at "숨김"
    }
    accident_image {
        bigint image_id PK
        bigint accident_id FK
        varchar original_filename
        varchar angle_code
        varchar quality_status
    }
    accident_image_asset {
        bigint asset_id PK
        bigint image_id FK
        varchar variant "ORIGINAL RESIZED THUMBNAIL BLURRED"
        varchar s3_key
        integer file_size "<= 20MB"
    }
    analysis_job {
        bigint job_id PK
        bigint accident_id FK
        varchar status "QUEUED PROCESSING COMPLETED FAILED"
        smallint retry_count "0-3"
        varchar failure_reason
        varchar request_id "멱등 키"
        bigint pipeline_version_id
    }
    analysis_image_result {
        bigint result_id PK
        bigint job_id FK
        bigint image_id FK
        jsonb detections
        boolean is_excluded
        varchar exclusion_reason "NOT_VEHICLE RATIO_BELOW_THRESHOLD"
    }
```

### ② 견적 · 파생 기능

```mermaid
erDiagram
    analysis_job ||--o{ estimate : "견적"
    estimate ||--o{ estimate_item : "항목"
    estimate ||--o| estimate_narrative : "LLM 요약"
    estimate ||--o{ estimate_report : "PDF"
    damaged_part ||--o{ estimate_item : "부위"
    accident ||--o{ repair_checklist : "체크리스트"
    repair_checklist ||--o{ repair_checklist_item : "항목"
    accident ||--o{ repair_question : "정비소 질문"
    repair_question ||--o{ repair_question_item : "항목"
    repair_checklist_common_item }o--o{ repair_checklist_item : "마스터 유래"

    estimate {
        bigint estimate_id PK
        bigint job_id FK
        smallint version "재분석마다 증가"
        boolean is_estimable
        varchar non_estimable_reason
        integer total_min
        integer total_median
        integer total_max
        integer ref_case_total
        varchar confidence_grade "HIGH MEDIUM LOW"
        jsonb unresolved_parts
    }
    estimate_item {
        bigint estimate_item_id PK
        bigint estimate_id FK
        bigint damaged_part_id FK
        varchar repair_method "coating sheet_metal exchange repair"
        integer part_cost_median
        integer labor_cost_median
        integer paint_material_cost
        integer item_min
        integer item_median
        integer item_max
        integer ref_case_count
        jsonb ref_condition "fallbackStage 등 근거"
        boolean is_low_confidence
    }
```

### ③ 수리 사례 corpus · 벡터 검색

```mermaid
erDiagram
    repair_case ||--o{ repair_case_item : "수리 항목"
    repair_case ||--o{ repair_case_image : "사진"
    repair_case_image ||--o{ repair_case_damage_feature : "손상 특징"
    repair_case_image ||--o{ repair_case_image_part_annotation : "부품 라벨"
    repair_case_image ||--o{ repair_case_image_part_inference : "YOLO 부품 추론"
    repair_case_damage_feature ||--o| repair_case_roi_embedding : "768d 벡터"
    repair_case_damage_feature ||--o{ repair_case_damage_feature_part_hint : "부품 힌트"
    repair_case_damage_feature ||--o{ repair_case_damage_feature_part_mapping : "매핑"
    repair_case_image_part_inference ||--o{ repair_case_damage_feature_part_mapping : "매핑"
    repair_case_image_part_inference ||--o{ repair_case_damage_feature_part_candidate : "후보"
    feature_pipeline_version ||--o{ repair_case_damage_feature : "버전"
    embedding_model_version ||--o{ repair_case_roi_embedding : "모델 버전"
    vehicle_model ||--o{ repair_case : "차종"

    repair_case {
        bigint case_id PK
        bigint model_id FK "84% 채워짐"
        varchar car_class
        varchar price_tier
    }
    repair_case_damage_feature {
        bigint damage_feature_id PK
        bigint case_image_id FK
        bigint pipeline_version_id FK
        varchar damage_type
        varchar part_code
        boolean is_searchable
    }
    repair_case_roi_embedding {
        bigint roi_embedding_id PK
        bigint damage_feature_id FK
        vector embedding "vector(768)"
    }
    feature_pipeline_version {
        bigint pipeline_version_id PK
        varchar pipeline_name
        varchar version
        varchar pair_rule_version
        numeric pair_threshold
        numeric roi_padding_ratio
        jsonb params
        boolean is_active "부분 UNIQUE"
    }
```

### ④ 마스터 · 견적서 검증 · 운영

```mermaid
erDiagram
    part_code ||--o{ part_name_mapping : "별칭"
    part_code ||--o{ damaged_part : "부위"
    repair_code ||--o{ repair_method_rule : "수리 방식 규칙"
    estimate_validation_rule ||--o{ estimate_validation_item : "판정 규칙"
    accident ||--o{ estimate_validation : "검증"
    estimate_validation ||--o{ estimate_validation_item : "항목"
    estimate_validation ||--o{ estimate_validation_report : "리포트"
    estimate_validation ||--o{ estimate_validation_question : "질문"
    batch_job_execution ||--o{ data_validation_error : "검증 오류"

    part_code {
        varchar part_code PK
        varchar part_name
    }
    estimate_validation {
        bigint validation_id PK
        bigint accident_id FK
        varchar status
    }
    audit_log {
        bigint audit_id PK
        varchar actor
        varchar action
        varchar target
        timestamptz created_at
    }
```

## 동작 흐름 (데이터가 흐르는 방향)

```mermaid
flowchart LR
    A["accident<br/>사고"] --> AI["accident_image<br/>사진"]
    AI --> AIA["accident_image_asset<br/>변형"]
    A --> AJ["analysis_job<br/>작업"]
    AJ --> AIR["analysis_image_result<br/>이미지 결과"]
    AJ --> E["estimate<br/>견적"]
    E --> EI["estimate_item<br/>항목"]
    RC["repair_case<br/>corpus"] -.->|근거| EI
    RCE["repair_case_roi_embedding<br/>벡터"] -.->|유사도| RC
    E --> EN["estimate_narrative"]
    E --> ER["estimate_report"]
    A --> CK["repair_checklist"]
```

## 주요 구성 요소

- `Docs/Erd/A307_ddl_final.sql` — 정본 DDL
- `Docs/Erd/바른견적_ERD.png` — 기존 ERD 이미지
- `Docs/backend-db/erd.md` · `erd.mmd` · `erd.svg` — 별도 ERD 자료

## 설정 및 실행 방법

ERD 도구로 보려면 정본 DDL을 import 한다. `vector(768)` 타입을 지원하지 않는 도구에서는
해당 컬럼을 별도 처리해야 한다.

## 오류 및 예외 처리

해당 없음 (구조 문서).

## 관련 소스코드

- `Docs/Erd/A307_ddl_final.sql`
- `backend/src/main/java/com/ssafy/a307/*/entity/` — JPA 엔티티

## 근거 자료

- 정본 DDL의 `CREATE TABLE` · `REFERENCES` 선언
- 컬럼·제약 조건 직접 확인

## 확인 필요 항목

- **일부 관계의 카디널리티** — FK 선언으로 방향은 확인했으나 1:1 / 1:N 구분을 일부 추정으로 표기함
- **`damaged_part` 의 생성 경로** — `estimate_item` 이 참조하지만 채우는 코드를 확인하지 못함
- **`repair_checklist_common_item` ↔ `repair_checklist_item` 관계** — 마스터 유래로 추정
- **`audit_log` 의 컬럼 전체** — 인덱스 이름으로 역추정했고 정의를 전수 확인하지 못함
- **`estimate_validation` 계열 FK** — 상세 관계를 전수 확인하지 못함
