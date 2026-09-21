# ERD 다이어그램

## 목적

47개 테이블의 관계를 한눈에 보이도록 정리한다. 전부 한 장에 넣으면 읽을 수 없으므로
**전체 개요 + 그룹별 상세** 로 나눈다. 컬럼 상세는 [ERD](../05_데이터/ERD.md)에 있다.

## 전체 개요 (테이블 그룹 관계)

```mermaid
flowchart TB
    subgraph M["마스터 10"]
        M1["part_code · part_name_mapping"]
        M2["vehicle_model"]
        M3["repair_code · repair_method_rule"]
        M4["estimate_validation_rule"]
        M5["repair_checklist_common_item"]
        M6["estimate_notice"]
        M7["feature_pipeline_version"]
        M8["embedding_model_version"]
    end
    subgraph U["회원 3"]
        U1["member"]
        U2["terms_agreement"]
        U3["vehicle"]
    end
    subgraph A["사고 4"]
        A1["accident"]
        A2["accident_image"]
        A3["accident_image_asset"]
        A4["accident_review"]
    end
    subgraph N["분석 4"]
        N1["analysis_job"]
        N2["analysis_stage"]
        N3["analysis_image_result"]
        N4["damaged_part"]
    end
    subgraph E["견적 4"]
        E1["estimate"]
        E2["estimate_item"]
        E3["estimate_narrative"]
        E4["estimate_report"]
    end
    subgraph C["corpus 11"]
        C1["repair_case · repair_case_item"]
        C2["repair_case_image"]
        C3["repair_case_damage_feature"]
        C4["repair_case_roi_embedding"]
        C5["part_annotation · part_inference<br/>part_hint · part_mapping · part_candidate"]
        C6["repair_cost_stat"]
    end
    subgraph V["검증 4"]
        V1["estimate_validation"]
        V2["estimate_validation_item"]
        V3["estimate_validation_report"]
        V4["estimate_validation_question"]
    end
    subgraph D["파생 4"]
        D1["repair_checklist · repair_checklist_item"]
        D2["repair_question · repair_question_item"]
    end
    subgraph O["운영 3"]
        O1["batch_job_execution"]
        O2["data_validation_error"]
        O3["audit_log"]
    end

    M2 --> U3 --> A1 --> N1 --> E1
    U1 --> U3
    U1 --> U2
    A1 --> A2 --> A3
    N1 --> N2
    N1 --> N3
    E1 --> E2
    E1 --> E3
    E1 --> E4
    C1 -.->|검색 근거| E2
    M2 --> C1
    C2 --> C3 --> C4
    M7 --> C3
    M8 --> C4
    A1 --> D1
    A1 --> D2
    A1 --> V1 --> V2
    M1 --> N4 --> E2
    O1 --> O2
```

## 핵심 경로 (사고 → 견적)

```mermaid
erDiagram
    member ||--o{ vehicle : "보유"
    vehicle_model ||--o{ vehicle : "모델"
    vehicle ||--o{ accident : "사고"
    accident ||--o{ accident_image : "사진"
    accident_image ||--o{ accident_image_asset : "변형 3종"
    accident ||--o{ analysis_job : "분석"
    analysis_job ||--o{ analysis_stage : "단계"
    analysis_job ||--o{ analysis_image_result : "이미지 결과"
    analysis_job ||--o{ estimate : "견적 (version)"
    estimate ||--o{ estimate_item : "항목"
    damaged_part ||--o{ estimate_item : "부위"
    part_code ||--o{ damaged_part : "부품 코드"
```

## 검색 corpus

```mermaid
erDiagram
    vehicle_model ||--o{ repair_case : "차종"
    repair_case ||--o{ repair_case_item : "수리 항목"
    repair_case ||--o{ repair_case_image : "사진"
    repair_case_image ||--o{ repair_case_damage_feature : "손상 특징"
    repair_case_image ||--o{ repair_case_image_part_annotation : "부품 라벨"
    repair_case_image ||--o{ repair_case_image_part_inference : "YOLO 추론"
    repair_case_damage_feature ||--o| repair_case_roi_embedding : "768d 벡터"
    repair_case_damage_feature ||--o{ repair_case_damage_feature_part_hint : "힌트"
    repair_case_damage_feature ||--o{ repair_case_damage_feature_part_mapping : "매핑"
    repair_case_image_part_inference ||--o{ repair_case_damage_feature_part_mapping : "매핑"
    repair_case_image_part_inference ||--o{ repair_case_damage_feature_part_candidate : "후보"
    feature_pipeline_version ||--o{ repair_case_damage_feature : "파이프라인 버전"
    embedding_model_version ||--o{ repair_case_roi_embedding : "임베딩 버전"
```

## 파생 기능

```mermaid
erDiagram
    accident ||--o{ repair_checklist : "체크리스트"
    repair_checklist ||--o{ repair_checklist_item : "항목"
    repair_checklist_common_item ||--o{ repair_checklist_item : "마스터 유래"
    accident ||--o{ repair_question : "정비소 질문"
    repair_question ||--o{ repair_question_item : "항목"
    estimate ||--o| estimate_narrative : "LLM 요약"
    estimate ||--o{ estimate_report : "PDF"
    accident ||--o{ estimate_validation : "견적서 검증"
    estimate_validation ||--o{ estimate_validation_item : "판정 항목"
    estimate_validation ||--o{ estimate_validation_report : "리포트"
    estimate_validation ||--o{ estimate_validation_question : "질문"
    estimate_validation_rule ||--o{ estimate_validation_item : "규칙"
```

## 작업 큐 테이블의 공통 구조

```mermaid
flowchart LR
    T["작업 테이블"]
    C1["status<br/>QUEUED PROCESSING COMPLETED FAILED"]
    C2["retry_count<br/>CHECK 0~3"]
    C3["failure_reason<br/>VARCHAR"]
    C4["started_at · finished_at"]
    IX["부분 UNIQUE 인덱스<br/>WHERE status IN ('QUEUED','PROCESSING')"]

    T --> C1
    T --> C2
    T --> C3
    T --> C4
    T --> IX
```

이 패턴이 **7곳**에 있다. 진행 중인 작업이 있으면 같은 대상에 새 작업을 넣을 수 없다.

## 버전 관리 테이블

```mermaid
erDiagram
    feature_pipeline_version {
        bigint pipeline_version_id PK
        varchar pipeline_name
        varchar version
        varchar pair_rule_version "전처리 규칙 버전"
        numeric pair_threshold
        numeric roi_padding_ratio
        jsonb params
        boolean is_active "ux_fpv_active 부분 UNIQUE"
    }
    embedding_model_version {
        bigint id PK
        boolean is_active "ux_emv_active 부분 UNIQUE"
    }
```

**전처리 규칙을 데이터로 기록한다.** 규칙이 바뀌면 새 버전을 만들고, corpus와 질의가 같은
버전인지 확인할 수 있다.

`is_active` 가 `true` 인 행이 **하나뿐**임을 부분 UNIQUE 인덱스가 보장한다.

다만 AI 서버는 `is_active` 를 읽지 않고 `FEATURE_PIPELINE_VERSION_ID` 환경변수로 고른다.

## 삭제 전파

```mermaid
flowchart TB
    M["member"]
    V["vehicle"]
    A["accident"]
    AI["accident_image"]
    AA["accident_image_asset"]
    AJ["analysis_job"]
    AS["analysis_stage"]
    AR["analysis_image_result"]
    E["estimate"]
    EI["estimate_item"]
    S3["🔴 S3 객체"]

    M -->|CASCADE| V -->|CASCADE| A
    A -->|CASCADE| AI -->|CASCADE| AA
    A -->|CASCADE| AJ
    AJ -->|CASCADE| AS
    AJ -->|CASCADE| AR
    AJ -->|CASCADE| E -->|CASCADE| EI
    AA -.->|❌ 전파 안 됨| S3
```

**S3 객체는 CASCADE를 따라가지 않는다.** 탈퇴 후에도 사진이 남을 수 있다 —
[데이터 보존 정책](../05_데이터/데이터_보존_정책.md) 참고.

## 벡터 컬럼

```
repair_case_roi_embedding.embedding  vector(768)  NOT NULL
CREATE INDEX ix_roi_hnsw ON repair_case_roi_embedding
    USING hnsw (embedding vector_cosine_ops);
```

유일한 벡터 컬럼이다. 768은 DINOv2-base `pooler_output` 차원이다.

DDL 주석 — "대량 적재 전에 만들면 INSERT 가 느려집니다 (**실측 11.4만건 65초**)."

## 운영 실측 건수 (2026-09-21)

| 테이블 | 건수 |
| --- | --: |
| `repair_case` | 39,676 |
| `repair_case_image` | 168,297 |
| `repair_case_damage_feature` (v1) | 205,977 |
| `repair_case_damage_feature` (v2) | 347,086 |
| `repair_case_roi_embedding` | 304,114 |
| `vehicle_model` | 51 |

## 관련 문서

- [ERD](../05_데이터/ERD.md) — 컬럼 상세
- [테이블 명세](../05_데이터/테이블_명세.md)
- [데이터베이스 구조](../05_데이터/데이터베이스_구조.md)
- [벡터 검색](../05_데이터/벡터_검색.md)

## 근거 자료

- `Docs/Erd/A307_ddl_final.sql` — `CREATE TABLE` 47개, `REFERENCES` 선언
- 2026-09-21 운영 DB 직접 조회

## 확인 필요 항목

- **일부 관계의 카디널리티** — FK로 방향은 확인했으나 1:1 / 1:N 구분을 일부 추정으로 표기함
- **`damaged_part` 생성 경로** — 참조는 확인했으나 채우는 코드를 확인하지 못함
- **`repair_cost_stat` 관계** — 읽는 코드를 확인하지 못함
- **`audit_log` 컬럼** — 인덱스 이름으로 역추정함
- **검증 계열 4개 테이블 FK** — 상세를 전수 확인하지 못함
