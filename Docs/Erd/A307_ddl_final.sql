-- ============================================================
-- A307 · 바른견적 — 최종 DDL
-- 컨설턴트 피드백 + EXPLAIN ANALYZE 실측 + 정적 검증 반영
-- PostgreSQL 16 + pgvector
--
-- 실행 순서 (FK 의존성)
--   1 독립 마스터 → 2 회원·약관 → 3 차량 → 4 사고 → 5 이미지
--   6 분석 → 7 손상부품 → 8 사례·통계 → 9 임베딩
--   10 견적 → 11 견적서 검증 → 12 로그 → 13 인덱스
--
-- 적용 방법
--   Windows  docker cp "Docs\Erd\A307_ddl_final.sql" a307-db:/tmp/ddl.sql
--            docker exec -i a307-db psql -U postgres -d a307 -f /tmp/ddl.sql
--   Mac·Bash docker exec -i a307-db psql -U postgres -d a307 < Docs/Erd/A307_ddl_final.sql
--
--   PowerShell 에서 Get-Content 파이프를 쓰면 한글이 '?' 로 깨집니다.
--   에러가 안 나서 지나치기 쉬우니 적용 후 반드시 확인하세요.
--     psql -c "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ck_rci_work';"
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ─── 1. 독립 마스터 (정적, 증가 없음) ────────────────────────
CREATE TABLE part_code (
    part_code     VARCHAR(50) PRIMARY KEY,
    name_ko       VARCHAR(50) NOT NULL,
    layout_zone   VARCHAR(20) NOT NULL,
    display_order SMALLINT    NOT NULL DEFAULT 0,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    -- AI 핵심 라벨 32종과 견적 전용 확장 코드를 가르는 명시적 속성.
    -- 예전에는 display_order(1~32 vs 101~) 로만 구분됐는데 그것은 표시 순서일 뿐이라
    -- 관리자가 순서를 바꾸면 구분이 조용히 무너졌다.
    code_scope    VARCHAR(20) NOT NULL DEFAULT 'EXTENDED',
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_pc_zone  CHECK (layout_zone IN ('FRONT','REAR','SIDE_L','SIDE_R','TOP','UNDER')),
    CONSTRAINT ck_pc_scope CHECK (code_scope  IN ('AI_LABEL','EXTENDED'))
);

CREATE TABLE part_name_mapping (
    raw_name  VARCHAR(200) PRIMARY KEY,
    part_code VARCHAR(50)  NOT NULL REFERENCES part_code(part_code) ON DELETE RESTRICT
);

CREATE TABLE embedding_model_version (
    model_version_id BIGSERIAL    PRIMARY KEY,
    model_name       VARCHAR(100) NOT NULL,
    version          VARCHAR(50)  NOT NULL,
    dimension        SMALLINT     NOT NULL,
    distance_metric  VARCHAR(20)  NOT NULL DEFAULT 'cosine',
    preprocessing    JSONB,
    is_active        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_emv         UNIQUE (model_name, version),
    CONSTRAINT ck_emv_metric  CHECK (distance_metric IN ('cosine','l2','ip')),
    -- embedding 컬럼이 vector(768) 고정이므로 현재는 768만 허용.
    -- 다른 차원 모델을 쓰려면 파티션 테이블 또는 컬럼 변경이 선행되어야 함
    CONSTRAINT ck_emv_dim     CHECK (dimension = 768)
);

-- ─── 2. 회원 · 약관 (저속 증가) ──────────────────────────────
CREATE TABLE member (
    member_id         BIGSERIAL    PRIMARY KEY,
    provider          VARCHAR(20)  NOT NULL,
    provider_user_id  VARCHAR(255) NOT NULL,
    nickname          VARCHAR(12)  NOT NULL,
    email             VARCHAR(255),
    profile_image_key VARCHAR(500),
    role              VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    withdrawn_at      TIMESTAMPTZ,
    CONSTRAINT uk_member_provider UNIQUE (provider, provider_user_id),
    CONSTRAINT ck_member_provider CHECK (provider IN ('KAKAO','GOOGLE')),
    CONSTRAINT ck_member_role     CHECK (role IN ('USER','ADMIN')),
    CONSTRAINT ck_member_status   CHECK (status IN ('ACTIVE','WITHDRAWN'))
);

CREATE TABLE terms_agreement (
    agreement_id BIGSERIAL   PRIMARY KEY,
    member_id    BIGINT      NOT NULL REFERENCES member(member_id) ON DELETE RESTRICT,
    terms_type   VARCHAR(20) NOT NULL,
    version      VARCHAR(20) NOT NULL,
    agreed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_terms_type CHECK (terms_type IN ('SERVICE','PRIVACY'))
);

-- ─── 3. 차량 ────────────────────────────────────────────────
CREATE TABLE vehicle_model (
    model_id     BIGSERIAL    PRIMARY KEY,
    manufacturer VARCHAR(50)  NOT NULL,
    model_name   VARCHAR(100) NOT NULL,
    vehicle_type VARCHAR(20)  NOT NULL,
    car_class    VARCHAR(20)  NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 관리자 동시 수정의 lost update 방지. JPA @Version 이 UPDATE 조건에 넣는다.
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_vm        UNIQUE (manufacturer, model_name),
    CONSTRAINT ck_vm_type   CHECK (vehicle_type IN ('SEDAN','SUV','VAN','TRUCK')),
    CONSTRAINT ck_vm_class  CHECK (car_class IN ('CityCar','Compact','Mid-size','Full-size'))
);

CREATE TABLE vehicle (
    vehicle_id BIGSERIAL   PRIMARY KEY,
    member_id  BIGINT      NOT NULL REFERENCES member(member_id)       ON DELETE RESTRICT,
    model_id   BIGINT      NOT NULL REFERENCES vehicle_model(model_id) ON DELETE RESTRICT,
    model_year SMALLINT    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 폐차·매각 시 목록에서 숨김. 사고 이력의 차종 표시와 재분석을 위해 행은 보존
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_v_year CHECK (model_year BETWEEN 1980 AND 2100)
);

-- ─── 4. 사고 (중속 증가) ─────────────────────────────────────
CREATE TABLE accident (
    accident_id             BIGSERIAL   PRIMARY KEY,
    -- member_id 없음: accident → vehicle → member 로 도달 가능한 이행적 종속.
    -- 직접 보관하면 차량 주인과 사고 주인이 어긋나도 DB 가 막지 못함
    vehicle_id              BIGINT      NOT NULL REFERENCES vehicle(vehicle_id) ON DELETE RESTRICT,
    -- 접수 당시 차량 정보. 차량/마스터 수정·소프트 삭제와 무관하게 과거 조건을 보존
    vehicle_input_type      VARCHAR(10)  NOT NULL,
    snapshot_model_id       BIGINT       NOT NULL,
    snapshot_manufacturer   VARCHAR(50)  NOT NULL,
    snapshot_model_name     VARCHAR(100) NOT NULL,
    snapshot_vehicle_type   VARCHAR(20)  NOT NULL,
    snapshot_car_class      VARCHAR(20)  NOT NULL,
    snapshot_model_year     SMALLINT     NOT NULL,
    actual_repair_cost      INTEGER,
    actual_repair_completed_date DATE,
    repair_shop_name        VARCHAR(100),
    actual_cost_recorded_at TIMESTAMPTZ,
    -- 목록에서 감춘 시각 (S15P21A307-554). NULL 이면 보인다. 삭제가 아니라 숨김이라
    -- 딸린 사진·분석·견적·체크리스트는 그대로 남고 링크로 열던 리포트도 살아 있다.
    hidden_at               TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ac_input_type CHECK (vehicle_input_type IN ('REGISTERED','DIRECT')),
    CONSTRAINT ck_ac_vehicle_type CHECK (snapshot_vehicle_type IN ('SEDAN','SUV','VAN','TRUCK')),
    CONSTRAINT ck_ac_car_class CHECK (snapshot_car_class IN ('CityCar','Compact','Mid-size','Full-size')),
    CONSTRAINT ck_ac_model_year CHECK (snapshot_model_year BETWEEN 1980 AND 2100),
    CONSTRAINT ck_ac_cost CHECK (actual_repair_cost IS NULL OR actual_repair_cost > 0)
);

-- ─── 5. 사고 이미지 (고속 증가) ──────────────────────────────
CREATE TABLE accident_image (
    image_id          BIGSERIAL   PRIMARY KEY,
    accident_id       BIGINT      NOT NULL REFERENCES accident(accident_id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    angle_code        VARCHAR(20),
    quality_status    VARCHAR(20) NOT NULL DEFAULT 'PASS',
    quality_reason    VARCHAR(100),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_quality CHECK (quality_status IN ('PASS','WARN'))
);

-- 원본·리사이즈·썸네일·블러를 행으로. 변형이 늘어도 컬럼 불변
CREATE TABLE accident_image_asset (
    asset_id   BIGSERIAL    PRIMARY KEY,
    image_id   BIGINT       NOT NULL REFERENCES accident_image(image_id) ON DELETE CASCADE,
    variant    VARCHAR(20)  NOT NULL,
    s3_key     VARCHAR(500) NOT NULL,
    width      SMALLINT,
    height     SMALLINT,
    file_size  INTEGER,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_aia      UNIQUE (image_id, variant),
    -- OVERLAY 제외: (job × image)마다 생성되므로 이미지당 1개 제약과 충돌.
    -- 재분석 시 uk_aia 위반이 실측으로 확인됨 → analysis_image_result.s3_key_overlay 로 이관
    CONSTRAINT ck_aia_var  CHECK (variant IN ('ORIGINAL','RESIZED','THUMBNAIL','BLURRED')),
    CONSTRAINT ck_aia_size CHECK (file_size IS NULL OR file_size <= 20971520)
);

-- ─── 6. 분석 작업 ───────────────────────────────────────────
CREATE TABLE analysis_job (
    job_id         BIGSERIAL   PRIMARY KEY,
    accident_id    BIGINT      NOT NULL REFERENCES accident(accident_id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    retry_count    SMALLINT    NOT NULL DEFAULT 0,
    failure_reason VARCHAR(50),
    model_version  VARCHAR(50),
    -- AI callback 멱등성. 같은 requestId 재수신 시 견적 버전을 더 만들지 않는다.
    -- 작업당 하나이며 재분석 시 덮어쓴다 (S15P21A307-155).
    request_id     VARCHAR(64),
    -- AI 가 분석에 쓴 버전 조합 식별자. model_version 문자열만으로는 되짚을 수 없다.
    -- FK 를 걸지 않는다 — feature_pipeline_version 은 파이프라인이 따로 적재한다.
    pipeline_version_id BIGINT,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_aj_status CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_aj_retry  CHECK (retry_count BETWEEN 0 AND 3)
);

-- 화면의 4단계 체크리스트. 단계가 늘어도 스키마 불변
CREATE TABLE analysis_stage (
    stage_id    BIGSERIAL   PRIMARY KEY,
    job_id      BIGINT      NOT NULL REFERENCES analysis_job(job_id) ON DELETE CASCADE,
    stage       VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    detail      VARCHAR(100),
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uk_as        UNIQUE (job_id, stage),
    CONSTRAINT ck_as_stage  CHECK (stage  IN ('PREPROCESS','DETECT','MATCH','ESTIMATE')),
    CONSTRAINT ck_as_status CHECK (status IN ('PENDING','RUNNING','DONE','FAILED'))
);

CREATE TABLE analysis_image_result (
    result_id        BIGSERIAL    PRIMARY KEY,
    job_id           BIGINT       NOT NULL REFERENCES analysis_job(job_id)     ON DELETE CASCADE,
    image_id         BIGINT       NOT NULL REFERENCES accident_image(image_id) ON DELETE CASCADE,
    -- 오버레이 폐기(2026-09-11)로 쓰지 않는다. NULL 로 둔다.
    s3_key_overlay   VARCHAR(500),
    -- 프론트가 원본 위에 손상 영역을 다시 그릴 좌표. AI 가 준 detections[] 를 키 이름·
    -- 구조 그대로 넣는다(pairStatus·searchability 포함). 구조를 DDL 로 강제하지 않는다 —
    -- 검증은 수신 계층이 한다 (S15P21A307-155).
    detections       JSONB,
    is_excluded      BOOLEAN      NOT NULL DEFAULT FALSE,
    exclusion_reason VARCHAR(50),
    CONSTRAINT uk_air        UNIQUE (job_id, image_id),
    CONSTRAINT ck_air_reason CHECK (exclusion_reason IS NULL
                              OR exclusion_reason IN ('NOT_VEHICLE','RATIO_BELOW_THRESHOLD'))
);

-- ─── 7. 손상 부품 ───────────────────────────────────────────
CREATE TABLE damaged_part (
    damaged_part_id BIGSERIAL    PRIMARY KEY,
    job_id          BIGINT       NOT NULL REFERENCES analysis_job(job_id) ON DELETE CASCADE,
    part_code       VARCHAR(50)  NOT NULL REFERENCES part_code(part_code) ON DELETE RESTRICT,
    damage_type     VARCHAR(20)  NOT NULL,
    -- NULL 허용. 후보가 둘인 손상(Separated·Crushed·Breakage)은 심각도 파생 규칙
    -- (S15P21A307-196)이 서기 전까지 확정할 수 없다. 값이 있을 때는 CHECK 가 네 값을 강제한다.
    repair_method   VARCHAR(20),
    severity_score  NUMERIC(6,2),
    confidence      NUMERIC(5,4) NOT NULL,
    CONSTRAINT uk_dp        UNIQUE (job_id, part_code),
    CONSTRAINT ck_dp_damage CHECK (damage_type   IN ('Scratched','Separated','Crushed','Breakage')),
    CONSTRAINT ck_dp_method CHECK (repair_method IN ('coating','sheet_metal','exchange','repair'))
);

-- ─── 8. 수리 사례 · 통계 (정적 적재) ─────────────────────────
CREATE TABLE repair_case (
    case_id      BIGSERIAL   PRIMARY KEY,
    source       VARCHAR(20) NOT NULL,
    external_ref VARCHAR(50),
    model_id     BIGINT      REFERENCES vehicle_model(model_id) ON DELETE SET NULL,
    manufacturer VARCHAR(50),
    model_name   VARCHAR(100),
    car_class    VARCHAR(20) NOT NULL,
    model_year   SMALLINT,
    repair_year  SMALLINT,
    labor_rate   INTEGER,
    total_cost   INTEGER,
    claim_amount INTEGER,
    paid_amount  INTEGER,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rc       UNIQUE (source, external_ref),
    CONSTRAINT ck_rc_src   CHECK (source    IN ('AIHUB_AS','AIHUB_SC','SERVICE')),
    CONSTRAINT ck_rc_class CHECK (car_class IN ('CityCar','Compact','Mid-size','Full-size'))
);

CREATE TABLE repair_case_item (
    case_item_id  BIGSERIAL   PRIMARY KEY,
    case_id       BIGINT      NOT NULL REFERENCES repair_case(case_id) ON DELETE CASCADE,
    -- 원천 견적 배열 순번. 재실행 시 같은 항목을 upsert하는 멱등 키다.
    source_item_key VARCHAR(100) NOT NULL,
    -- ANCILLARY 행은 부품이 아니라 부대 비용이라 표준 부품 코드가 없다.
    -- 검색 대상 행 종류(WORK/PART_PRICE/REFERENCE_PRICE)는 ck_rci_part_code로 NOT NULL을 유지한다.
    part_code     VARCHAR(50) REFERENCES part_code(part_code) ON DELETE RESTRICT,
    raw_item_name VARCHAR(500),
    -- 행 종류. 출처(AIHUB_AS / AIHUB_SC)를 몰라도 해석되도록 4종으로 나눈다.
    --   WORK            공임이 붙는 수리 작업. 정산 포함
    --   PART_PRICE      손해사정에 반영된 부품 명세. 정산 포함
    --   REFERENCE_PRICE AS 견적서의 `신품가` 참고 정가. 정산 제외, item_total 미합산
    --   ANCILLARY       견인·구난 등 수리가 아닌 부대 비용. 정산 포함
    line_type     VARCHAR(20) NOT NULL DEFAULT 'WORK',
    work_type     VARCHAR(20),
    work_code     VARCHAR(30),
    -- 손해사정 상태. `불인정`은 작업 유형이 아니라 손해사정 결과라 line_type이 아닌 여기에 둔다.
    --   NULL / APPROVED / NOT_APPROVED
    -- 원천이 `작업` 필드를 `불인정`으로 덮어써서 그 행의 원래 작업 유형(판금·도장 등)은
    -- 복구할 수 없다. NOT_APPROVED 행의 work_type·work_code가 NULL인 것은 적재 누락이
    -- 아니라 원천에 정보가 없다는 뜻이다.
    assessment_status VARCHAR(20),
    hq            NUMERIC(6,2),
    reference_part_price INTEGER,
    part_cost     INTEGER,
    -- 원천 `부품가격`은 `작업=도장`인 행에서 부품비가 아니라 도장 재료비이고, 정산상
    -- 공임 측 `재료대`에 들어간다. part_cost와 합치면 부품 비용 통계가 틀어지므로 분리한다.
    paint_material_cost INTEGER,
    labor_cost    INTEGER,
    pre_adjustment_part_cost  INTEGER,
    pre_adjustment_labor_cost INTEGER,
    post_adjustment_part_cost  INTEGER,
    post_adjustment_labor_cost INTEGER,
    item_total    INTEGER,
    CONSTRAINT uk_rci_source_item UNIQUE (case_id, source_item_key),
    CONSTRAINT ck_rci_line_type CHECK (line_type IN ('WORK','PART_PRICE','REFERENCE_PRICE','ANCILLARY')),
    CONSTRAINT ck_rci_assessment CHECK (
        assessment_status IS NULL OR assessment_status IN ('APPROVED','NOT_APPROVED')
    ),
    CONSTRAINT ck_rci_part_code CHECK (line_type = 'ANCILLARY' OR part_code IS NOT NULL),
    -- 작업 어휘는 standardization.ESTIMATE_WORKS가 단일 기준이다. 여기서는 코드 집합만
    -- 검사하고 (work_type, work_code) 쌍은 열거하지 않는다. work_type은 별칭이 흔들리는
    -- 원문(`견인비`→TOWING)이라 쌍으로 묶으면 별칭마다 migration이 필요해진다.
    -- NOT_APPROVED는 assessment_status로 가므로 여기 없다.
    CONSTRAINT ck_rci_work_code CHECK (
        work_code IS NULL OR work_code IN (
            'COATING','REPAIR','SHEET_METAL','EXCHANGE','REMOVE_INSTALL','OVERHAUL',
            'OVERHAUL_HALF','OVERHAUL_THIRD','OVERHAUL_QUARTER','ADJUSTMENT',
            'TOWING','RESCUE'
        )
    ),
    CONSTRAINT ck_rci_work CHECK (
        (line_type IN ('PART_PRICE','REFERENCE_PRICE')
         AND work_type IS NULL AND work_code IS NULL)
        OR
        -- `탁송`은 `작업` 값이 아니다. 원천에 `작업=탁송`은 0건이고 `탁송비`는 부품명으로
        -- 나타나며 그 행의 `작업`은 견인 또는 구난이다.
        (line_type = 'ANCILLARY' AND work_code IN ('TOWING','RESCUE'))
        OR
        -- 불인정 행은 원래 작업 유형이 복구 불가능하므로 work_code 없이 허용한다.
        (line_type = 'WORK' AND (work_code IS NOT NULL OR assessment_status = 'NOT_APPROVED'))
    )
);

-- 검색 적재 배치의 실행 이력과 격리된 원천 오류를 보존한다.
CREATE TABLE batch_job_execution (
    batch_job_execution_id BIGSERIAL PRIMARY KEY,
    job_name               VARCHAR(100) NOT NULL,
    job_version            VARCHAR(100),
    status                 VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    input_ref              VARCHAR(500),
    started_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    summary                JSONB,
    error_message          TEXT,
    CONSTRAINT ck_bje_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'))
);

CREATE TABLE data_validation_error (
    data_validation_error_id BIGSERIAL PRIMARY KEY,
    batch_job_execution_id   BIGINT NOT NULL REFERENCES batch_job_execution(batch_job_execution_id) ON DELETE RESTRICT,
    error_type               VARCHAR(50) NOT NULL,
    source_ref               VARCHAR(500),
    case_external_ref        VARCHAR(100),
    category_id              VARCHAR(100),
    error_detail             JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE repair_case_image (
    case_image_id        BIGSERIAL    PRIMARY KEY,
    case_id              BIGINT       NOT NULL REFERENCES repair_case(case_id) ON DELETE CASCADE,
    source_image_ref     VARCHAR(255) NOT NULL,
    -- AI-Hub: repair-cases/{source}/{external_ref}/{source_image_id}/{variant}.{ext}
    -- source_image_id는 원본 파일명 숫자 접두를 보존하며 DB PK와 무관하다.
    storage_key          VARCHAR(500) NOT NULL,
    blur_key             VARCHAR(500),
    angle_tag            VARCHAR(20),
    -- DAMAGE_PART는 주 검색 ROI 원본, DAMAGE는 선택적 시각 참고 이미지다.
    image_type           VARCHAR(20)  NOT NULL,
    -- AI-Hub의 물리 split. 서비스 DEV/DEMO/EVAL subset과 다른 축이다.
    source_dataset_split VARCHAR(20),
    quality_status       VARCHAR(20),
    is_searchable        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_rci_src            UNIQUE (source_image_ref),
    CONSTRAINT ck_rcimg_type         CHECK (image_type IN ('DAMAGE', 'DAMAGE_PART')),
    CONSTRAINT ck_rcimg_source_split CHECK (source_dataset_split IS NULL
                                            OR source_dataset_split IN ('TRAIN', 'VALIDATION'))
);

-- ─── 9. 검색 feature·임베딩 (고속 증가) ──────────────────────
CREATE TABLE feature_pipeline_version (
    pipeline_version_id BIGSERIAL   PRIMARY KEY,
    pipeline_name       VARCHAR(100) NOT NULL,
    version             VARCHAR(50)  NOT NULL,
    pair_rule_version   VARCHAR(50)  NOT NULL,
    pair_threshold      NUMERIC(3,2) NOT NULL,
    roi_padding_ratio   NUMERIC(3,2),
    params              JSONB,
    is_active           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_fpv UNIQUE (pipeline_name, version)
);

CREATE TABLE repair_case_damage_feature (
    damage_feature_id   BIGSERIAL   PRIMARY KEY,
    case_image_id       BIGINT      NOT NULL REFERENCES repair_case_image(case_image_id) ON DELETE CASCADE,
    pipeline_version_id BIGINT      NOT NULL REFERENCES feature_pipeline_version(pipeline_version_id) ON DELETE RESTRICT,
    roi_index            SMALLINT    NOT NULL,
    damage_type          VARCHAR(20) NOT NULL,
    damage_polygon       JSONB       NOT NULL,
    roi_box              JSONB       NOT NULL,
    part_code            VARCHAR(50) REFERENCES part_code(part_code) ON DELETE RESTRICT,
    pair_status          VARCHAR(20) NOT NULL,
    quality_status       VARCHAR(20) NOT NULL,
    confidence           NUMERIC(5,4),
    is_searchable        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rcdf UNIQUE (case_image_id, pipeline_version_id, roi_index),
    CONSTRAINT ck_rcdf_type CHECK (damage_type IN ('SCRATCHED','SEPARATED','CRUSHED','BREAKAGE')),
    CONSTRAINT ck_rcdf_pair CHECK (pair_status IN ('PAIRED','UNPAIRED','AMBIGUOUS'))
);

CREATE TABLE repair_case_roi_embedding (
    roi_embedding_id BIGSERIAL    PRIMARY KEY,
    case_image_id    BIGINT       NOT NULL REFERENCES repair_case_image(case_image_id)          ON DELETE CASCADE,
    model_version_id BIGINT       NOT NULL REFERENCES embedding_model_version(model_version_id) ON DELETE RESTRICT,
    damage_feature_id BIGINT      NOT NULL REFERENCES repair_case_damage_feature(damage_feature_id) ON DELETE CASCADE,
    confidence       NUMERIC(5,4),
    embedding        vector(768)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_roi UNIQUE (damage_feature_id, model_version_id)
);

-- damage_part 이미지에 직접 라벨된 부품 영역. 같은 이미지의 damage ROI와 명확히
-- 매칭할 때만 ROI part_code로 연결한다.
CREATE TABLE repair_case_image_part_annotation (
    case_image_part_annotation_id BIGSERIAL    PRIMARY KEY,
    case_image_id                 BIGINT       NOT NULL REFERENCES repair_case_image(case_image_id) ON DELETE CASCADE,
    part_code                     VARCHAR(50)  NOT NULL REFERENCES part_code(part_code) ON DELETE RESTRICT,
    source_annotation_ref         VARCHAR(255) NOT NULL,
    part_polygon                  JSONB,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_rcipa_source UNIQUE (case_image_id, source_annotation_ref)
);

CREATE TABLE repair_cost_stat (
    stat_id           BIGSERIAL   PRIMARY KEY,
    car_class         VARCHAR(20) NOT NULL,
    part_code         VARCHAR(50) NOT NULL,
    damage_type       VARCHAR(20) NOT NULL,
    repair_method     VARCHAR(20) NOT NULL,
    source            VARCHAR(20) NOT NULL,
    case_count        INTEGER     NOT NULL,
    hq_median         NUMERIC(6,2),
    cost_min          INTEGER     NOT NULL,
    cost_p25          INTEGER     NOT NULL,
    cost_median       INTEGER     NOT NULL,
    cost_p75          INTEGER     NOT NULL,
    cost_max          INTEGER     NOT NULL,
    part_cost_median  INTEGER,
    labor_cost_median INTEGER,
    aggregated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rcs UNIQUE (car_class, part_code, damage_type, repair_method, source)
);

-- ─── 10. 예상 견적 ──────────────────────────────────────────
CREATE TABLE estimate (
    estimate_id          BIGSERIAL   PRIMARY KEY,
    job_id               BIGINT      NOT NULL REFERENCES analysis_job(job_id) ON DELETE CASCADE,
    version              SMALLINT    NOT NULL DEFAULT 1,
    is_estimable         BOOLEAN     NOT NULL DEFAULT TRUE,
    non_estimable_reason VARCHAR(100),
    labor_rate           INTEGER,
    total_hq             NUMERIC(7,2),
    total_min            INTEGER,
    total_median         INTEGER,
    total_max            INTEGER,
    ref_case_total       INTEGER,
    confidence_grade     VARCHAR(10),
    -- 부분 견적에서 산정하지 못해 총액에서 뺀 부위. AI 가 준 unresolvedParts[] 를
    -- [{partCode, damageType, reason}] 모양 그대로 넣되, 마스터에 없는 부위·items[] 와
    -- 겹치는 부위·중복은 수신 계층이 거른다. 이 열 이전 견적은 NULL (S15P21A307-534).
    unresolved_parts     JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_est       UNIQUE (job_id, version),
    CONSTRAINT ck_est_grade CHECK (confidence_grade IS NULL
                             OR confidence_grade IN ('HIGH','MEDIUM','LOW'))
);

CREATE TABLE estimate_item (
    estimate_item_id  BIGSERIAL   PRIMARY KEY,
    estimate_id       BIGINT      NOT NULL REFERENCES estimate(estimate_id)         ON DELETE CASCADE,
    damaged_part_id   BIGINT      NOT NULL REFERENCES damaged_part(damaged_part_id) ON DELETE CASCADE,
    repair_method     VARCHAR(20) NOT NULL,
    standard_hq       NUMERIC(6,2),
    part_cost_median  INTEGER,
    labor_cost_median INTEGER,
    -- 도장 재료비. 리포트가 공임과 나눠 보여 주며 repair_case_item 과 표현을 맞춘다.
    -- 도장이 없는 작업에는 값이 없다 — 0 으로 채우면 "도장했는데 0원" 과 구분되지 않는다.
    paint_material_cost INTEGER,
    item_min          INTEGER     NOT NULL,
    item_median       INTEGER     NOT NULL,
    item_max          INTEGER     NOT NULL,
    ref_case_count    INTEGER     NOT NULL,
    ref_condition     JSONB       NOT NULL,
    is_low_confidence BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_ei_method CHECK (repair_method IN ('coating','sheet_metal','exchange','repair'))
);

-- 견적 화면·리포트·PDF 가 함께 쓰는 고지 문구. 코드에 상수로 박지 않는 이유는 문구가
-- 법무·기획 사정으로 바뀌는 값이고, 바뀔 때마다 재배포를 요구하면 안 되기 때문이다
-- (S15P21A307-288). 관리자 API 는 두지 않았다 — 값 변경은 psql UPDATE 로 한다.
-- version(낙관적 잠금)이 없는 것은 repair_code 와 달리 동시 편집 경로가 없어서다.
CREATE TABLE estimate_notice (
    code          VARCHAR(30)  PRIMARY KEY,
    message       VARCHAR(500) NOT NULL,
    display_order SMALLINT     NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ─── 견적 리포트 LLM 요약 (S15P21A307-537) ──────────────────────────
-- 리포트는 숫자와 표만 있고 "이 견적을 어떻게 읽어야 하는가" 를 말해 주는 문장이 없다.
--
-- 왜 estimate 에 열을 붙이지 않았나 — 이것은 큐다. status·실패 사유·선점 시각이 따라붙고
-- 대기 건만 훑는 인덱스가 필요하다. 견적 본문에 큐 열을 섞으면 견적을 읽는 모든 쿼리가
-- 그 열을 함께 진다. 생성이 견적 저장보다 나중에 다른 트랜잭션에서 끝난다는 것도 이유다.
--
-- PK 가 estimate_id 다. 견적 한 건에 요약 한 행이라 별도 시퀀스와 UNIQUE 대신 PK 를 FK 로
-- 삼았다. 견적은 재산정마다 새 행이므로(uk_est) 요약도 버전마다 따로 생긴다.
CREATE TABLE estimate_narrative (
    estimate_id    BIGINT      PRIMARY KEY REFERENCES estimate(estimate_id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    -- 문장만 담는다. 금액·등급·판정은 여기 없다 — 그 값들은 estimate·estimate_item 이 이미
    -- 가지고 있고, LLM 이 만든 숫자가 협상 근거가 되면 안 된다. 원문에 없던 숫자가 섞인
    -- 문장은 저장 전에 버리고 규칙이 만든 문장을 그대로 쓴다.
    -- {"summary":"…","cautions":["…"],"basisNotes":[{"partCode":"…","text":"…"}]}
    content        JSONB,
    failure_reason VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 선점·완료 시각. 워커가 멈춘 PROCESSING 건을 되찾는 기준이다.
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_en_status CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    -- 완료인데 문장이 없으면 화면이 빈 요약을 그린다. 그 상태를 스키마가 막는다.
    CONSTRAINT ck_en_done   CHECK (status <> 'COMPLETED' OR content IS NOT NULL)
);

-- 대기·처리 중인 건만 담는다. 완료가 쌓여도 인덱스는 대기 건수만큼만 커진다.
CREATE INDEX ix_en_queue ON estimate_narrative (updated_at, estimate_id)
    WHERE status IN ('QUEUED','PROCESSING');

-- 견적과 생명주기가 다름 (재생성·삭제·실패)
CREATE TABLE estimate_report (
    report_id      BIGSERIAL    PRIMARY KEY,
    estimate_id    BIGINT       NOT NULL REFERENCES estimate(estimate_id) ON DELETE CASCADE,
    report_no      VARCHAR(24)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    s3_key_pdf     VARCHAR(500),
    failure_reason VARCHAR(200),
    retry_count    SMALLINT     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT uk_er_no     UNIQUE (report_no),
    CONSTRAINT ck_er_status CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_er_retry  CHECK (retry_count BETWEEN 0 AND 3),
    CONSTRAINT ck_er_done   CHECK (completed_at IS NULL OR status IN ('COMPLETED','FAILED'))
);

-- ─── 관리자 마스터 표시층 · 판정 규칙 (S15P21A307 관리자 기능) ───────────
-- canonical code 는 Java enum·DDL CHECK·통계 쿼리가 공유한다. 관리자가 새 코드를
-- 추가하면 DB 행만 늘고 계산이 실패하므로, 코드는 불변으로 두고 표시명·순서·활성만 관리한다.
CREATE TABLE repair_code (
    code_type     VARCHAR(20) NOT NULL,
    code          VARCHAR(20) NOT NULL,
    display_name  VARCHAR(50) NOT NULL,
    display_order SMALLINT    NOT NULL DEFAULT 0,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    version       BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (code_type, code),
    CONSTRAINT ck_rcode_type CHECK (code_type IN ('REPAIR_METHOD','DAMAGE_TYPE'))
);

-- 심각도 구간 → 수리 방식. severity_score 의 값 범위는 아직 확정되지 않아
-- DB 는 "하한 < 상한" 만 강제한다. 경계는 [min, max) 이고 max_inclusive 로 마지막 구간만 닫는다.
CREATE TABLE repair_method_rule (
    rule_id       BIGSERIAL    PRIMARY KEY,
    damage_type   VARCHAR(20)  NOT NULL,
    part_code     VARCHAR(50)  REFERENCES part_code(part_code) ON DELETE RESTRICT,
    severity_min  NUMERIC(6,2) NOT NULL,
    severity_max  NUMERIC(6,2) NOT NULL,
    max_inclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    repair_method VARCHAR(20)  NOT NULL,
    priority      SMALLINT     NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_rmr_range  CHECK (severity_min < severity_max),
    CONSTRAINT ck_rmr_damage CHECK (damage_type   IN ('Scratched','Separated','Crushed','Breakage')),
    CONSTRAINT ck_rmr_method CHECK (repair_method IN ('coating','sheet_metal','exchange','repair'))
);

-- 이상 탐지 임계값. 행은 불변이고 현재 규칙은 rule_version 이 가장 큰 행이다 —
-- 활성 플래그를 두지 않아 "활성이 둘" 상태가 생기지 않는다.
CREATE TABLE estimate_validation_rule (
    rule_version                        INTEGER      PRIMARY KEY,
    reference_percentile                SMALLINT     NOT NULL,
    severe_over_p75_multiplier          NUMERIC(5,2) NOT NULL,
    caution_total_difference_ratio      NUMERIC(5,4) NOT NULL,
    needs_review_total_difference_ratio NUMERIC(5,4) NOT NULL,
    needs_review_item_count             SMALLINT     NOT NULL,
    changed_by                          BIGINT       REFERENCES member(member_id) ON DELETE SET NULL,
    change_note                         VARCHAR(200),
    created_at                          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_evr_version CHECK (rule_version >= 1),
    CONSTRAINT ck_evr_pct     CHECK (reference_percentile BETWEEN 1 AND 100),
    CONSTRAINT ck_evr_mult    CHECK (severe_over_p75_multiplier > 1.0),
    CONSTRAINT ck_evr_caution CHECK (caution_total_difference_ratio >= 0),
    CONSTRAINT ck_evr_review  CHECK (needs_review_total_difference_ratio >= caution_total_difference_ratio),
    CONSTRAINT ck_evr_count   CHECK (needs_review_item_count >= 1)
);

-- ─── 11. 견적서 검증 ────────────────────────────────────────
CREATE TABLE estimate_validation (
    validation_id  BIGSERIAL    PRIMARY KEY,
    member_id      BIGINT       NOT NULL REFERENCES member(member_id)     ON DELETE RESTRICT,
    accident_id    BIGINT       NOT NULL REFERENCES accident(accident_id) ON DELETE CASCADE,
    estimate_id    BIGINT       REFERENCES estimate(estimate_id)          ON DELETE SET NULL,
    s3_key_file    VARCHAR(500),
    file_type      VARCHAR(10)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    claimed_total  INTEGER,
    llm_model      VARCHAR(50),
    llm_grade      VARCHAR(20),
    llm_summary    TEXT,
    failure_reason VARCHAR(200),
    -- 이 검증이 어떤 이상 탐지 규칙 버전으로 판정됐는지. 규칙이 바뀌어도 과거 결과는
    -- 다시 계산되지 않으므로, 근거를 되짚으려면 버전이 남아야 한다.
    -- 컬럼 생성 이전 검증은 알 수 없어 NULL 이다.
    rule_version      INTEGER   REFERENCES estimate_validation_rule(rule_version) ON DELETE SET NULL,
    review_item_count INTEGER   NOT NULL DEFAULT 0,
    total_item_count  INTEGER   NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT ck_ev_status CHECK (status    IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_ev_type   CHECK (file_type IN ('IMAGE','PDF','MANUAL')),
    CONSTRAINT ck_ev_grade  CHECK (llm_grade IS NULL
                             OR llm_grade IN ('APPROPRIATE','CAUTION','NEEDS_REVIEW')),
    -- 직접 입력이면 파일이 없음
    CONSTRAINT ck_ev_file   CHECK ((file_type = 'MANUAL' AND s3_key_file IS NULL)
                             OR    (file_type <> 'MANUAL' AND s3_key_file IS NOT NULL)),
    CONSTRAINT ck_ev_done   CHECK (completed_at IS NULL OR status IN ('COMPLETED','FAILED'))
);

-- 화면의 항목별 비교표. JSONB로는 정렬·집계 불가
CREATE TABLE estimate_validation_item (
    validation_item_id BIGSERIAL    PRIMARY KEY,
    validation_id      BIGINT       NOT NULL REFERENCES estimate_validation(validation_id) ON DELETE CASCADE,
    line_no            SMALLINT     NOT NULL,
    raw_item_name      VARCHAR(200) NOT NULL,
    normalized_item_name VARCHAR(100),
    part_code          VARCHAR(50)  REFERENCES part_code(part_code) ON DELETE SET NULL,
    work_type          VARCHAR(20),
    quantity           SMALLINT     NOT NULL DEFAULT 1,
    part_cost          INTEGER,
    labor_cost         INTEGER,
    subtotal           INTEGER,
    llm_flag           VARCHAR(30),
    llm_reason         VARCHAR(300),
    reference_min      INTEGER,
    reference_median   INTEGER,
    reference_p75      INTEGER,
    reference_max      INTEGER,
    reference_case_count INTEGER,
    CONSTRAINT uk_evi UNIQUE (validation_id, line_no)
);

CREATE TABLE estimate_validation_report (
    validation_id  BIGINT       PRIMARY KEY REFERENCES estimate_validation(validation_id) ON DELETE CASCADE,
    status         VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    s3_key_pdf     VARCHAR(500),
    failure_reason VARCHAR(200),
    retry_count    SMALLINT     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT ck_evr_status CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_evr_retry  CHECK (retry_count BETWEEN 0 AND 3),
    CONSTRAINT ck_evr_done   CHECK (completed_at IS NULL OR status IN ('COMPLETED','FAILED'))
);

-- 검증 완료 시 생성한 질문 스냅샷. 조회할 때 문장을 다시 생성하지 않는다.
CREATE TABLE estimate_validation_question (
    question_id        BIGSERIAL    PRIMARY KEY,
    validation_id      BIGINT       NOT NULL REFERENCES estimate_validation(validation_id) ON DELETE CASCADE,
    validation_item_id BIGINT       REFERENCES estimate_validation_item(validation_item_id) ON DELETE CASCADE,
    source_flag        VARCHAR(30)  NOT NULL,
    display_order      SMALLINT     NOT NULL,
    question_text      VARCHAR(500) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_evq_item_flag UNIQUE (validation_item_id, source_flag),
    CONSTRAINT uk_evq_order UNIQUE (validation_id, display_order)
);

-- ─── 12. 감사 로그 (관리자 행위만. HTTP 로그는 파일) ──────────
CREATE TABLE audit_log (
    audit_log_id    BIGSERIAL    PRIMARY KEY,
    actor_member_id BIGINT       REFERENCES member(member_id) ON DELETE SET NULL,
    action_type     VARCHAR(50)  NOT NULL,
    target_type     VARCHAR(50)  NOT NULL,
    target_id       VARCHAR(100) NOT NULL,
    request_id      VARCHAR(64),
    -- JSONB 가 아니라 TEXT 다 — 정정.
    -- 이 두 컬럼은 검색 조건으로 쓰지 않기로 했고(필터는 행위자·대상·기간뿐), JSONB 의 이점은
    -- 인덱스와 연산자뿐이다. 그런데 Java 쪽은 평범한 String 이라 ddl-auto=validate 가
    -- jsonb ↔ varchar 를 불일치로 잡아 기동이 실패한다.
    -- estimate_validation.llm_summary 가 같은 이유로 TEXT 다 (그때는 실제로 기동이 깨졌다).
    before_data     TEXT,
    after_data      TEXT,
    -- 관리자가 적은 변경 사유. nullable 이다 — 지금 사유를 필수로 받는 경로는 부품명
    -- 매핑의 등록·수정·삭제뿐이고, NOT NULL 로 잠그면 아직 받지 않는 경로가 전부 깨진다.
    change_reason   VARCHAR(500),
    -- INET 이 아니라 VARCHAR(45) 다 — 같은 이유. IPv6 최대 표기 길이가 45자다.
    ip_address      VARCHAR(45),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ─── 12-1. 정비 체크리스트 (S15P21A307-509) ──────────────────
-- 정비소 방문 전·중에 쓰는 체크리스트다. 사고 현장 체크리스트(S15P21A307-121,
-- backend/src/main/resources/checklist.json)와 이름만 겹치고 다른 기능이다 — 저쪽은 정적
-- 12항목·무인증·저장 없음, 이쪽은 사고별 AI 생성·인증 필요·DB 저장·사용자 편집이다.
-- 헷갈리기 쉬워 repair_ 접두어로 갈라 둔다.
--
-- member_id 를 두지 않는다. accident → vehicle → member 로 도달 가능한 이행적 종속이고,
-- accident 가 같은 이유로 member_id 를 두지 않았다(위 4장 주석). 직접 보관하면 사고 주인과
-- 체크리스트 주인이 어긋나도 DB 가 막지 못한다.

-- 공통 확인 항목 마스터 (S15P21A307-462 · -463). 파손 부위와 무관하게 모든 체크리스트에
-- 들어가는 6종이다. 문안은 -462 본문에 확정돼 있어 백엔드가 창작하지 않는다.
-- 형태는 estimate_notice 를 그대로 따랐다 — 성격이 같은 문구 마스터이고, 관리자 API 없이
-- psql UPDATE 로 고친다. 동시 편집 경로가 없어 version(낙관적 잠금)도 두지 않는다.
-- 시드 6행은 Docs/Erd/migrations/2026-09-14-repair-checklist.sql 이 넣는다.
CREATE TABLE repair_checklist_common_item (
    code          VARCHAR(30)  PRIMARY KEY,
    message       VARCHAR(500) NOT NULL,
    display_order SMALLINT     NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 체크리스트 머리. 사고 한 건에 하나다 (S15P21A307-459 "체크리스트는 사고 건에 연결된다").
CREATE TABLE repair_checklist (
    checklist_id   BIGSERIAL   PRIMARY KEY,
    accident_id    BIGINT      NOT NULL REFERENCES accident(accident_id) ON DELETE CASCADE,
    -- 생성 상태 (S15P21A307-460 · -461). 화면이 말하는 대기·완료·실패는 여기서
    -- QUEUED+PROCESSING · COMPLETED · FAILED 로 대응한다. 네 값 표기는 analysis_job ·
    -- estimate_report · estimate_validation 과 같은 형태를 따른 것이다.
    status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    -- 재생성 추적 (S15P21A307-486). 재생성은 "기존 항목 교체" 라서 머리를 새로 만들지 않는다.
    -- 몇 번째 생성분인지는 이 값으로만 남는다.
    generation_no  SMALLINT    NOT NULL DEFAULT 1,
    failure_reason VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    regenerated_at TIMESTAMPTZ,
    -- AI 한 줄 요약 (S15P21A307-544). 사고 성격과 중점 확인 권장을 한 문장으로 적는다.
    -- 생성은 200자 이내로 지시하고 열은 300 으로 둔다 — 모델이 조금 넘겨도 저장이 깨지지
    -- 않는다(서버가 자른다). NULL 이면 화면이 요약 영역을 그리지 않는다.
    summary        VARCHAR(300),
    CONSTRAINT uk_rcl_accident   UNIQUE (accident_id),
    CONSTRAINT ck_rcl_status     CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_rcl_done       CHECK (completed_at IS NULL OR status IN ('COMPLETED','FAILED')),
    CONSTRAINT ck_rcl_generation CHECK (generation_no >= 1),
    CONSTRAINT ck_rcl_regen      CHECK (regenerated_at IS NULL OR generation_no > 1)
);

-- 체크리스트 항목. AI 생성 · 공통 · 사용자 추가 셋이 한 목록에 섞인다.
CREATE TABLE repair_checklist_item (
    item_id       BIGSERIAL    PRIMARY KEY,
    checklist_id  BIGINT       NOT NULL REFERENCES repair_checklist(checklist_id) ON DELETE CASCADE,
    source        VARCHAR(20)  NOT NULL,
    -- 공통 항목일 때만 채운다. 문안은 content 에 복사해 두므로 마스터 문구가 나중에 바뀌어도
    -- 이미 만들어진 체크리스트는 그대로 남는다 — accident 의 스냅샷 컬럼과 같은 방식이다.
    common_code   VARCHAR(30)  REFERENCES repair_checklist_common_item(code) ON DELETE RESTRICT,
    content       VARCHAR(500) NOT NULL,
    is_checked    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 메모 (S15P21A307-482 · -483). 두 제목이 메모를 말하고 -482 스토리 문장만 빠뜨렸다.
    -- 스키마라서 안전한 쪽을 택했다 — 안 쓰면 NULL 로 두면 되지만, 없는 컬럼은 나중에
    -- 마이그레이션을 또 요구한다.
    memo          VARCHAR(500),
    display_order SMALLINT     NOT NULL DEFAULT 0,
    checked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- ── 부위·분류 (S15P21A307-544) ──────────────────────────────────────
    -- 화면이 공통 / 부품별 / 함께 점검 세 탭을 그린다. source 는 "누가 만들었나" 이고
    -- 이 열은 "어느 탭인가" 다 — 사용자가 추가한 항목도 부품별·함께 점검에 들어간다.
    -- HIDDEN 은 사진에 보이지 않지만 함께 점검을 권하는 항목이다.
    category      VARCHAR(10)  NOT NULL DEFAULT 'PART',
    -- 그 항목이 가리키는 부위. 마스터 FK 다 — 서버가 프롬프트에 넘긴 목록 밖 코드를
    -- NULL 로 바꾸지만, 그 판단 한 곳에만 기대지 않는다. ON DELETE RESTRICT 인 이유는
    -- repair_question_item.part_code 와 같다: 부품이 마스터에서 빠졌다고 사용자가
    -- 받아 둔 체크리스트가 사라지면 안 된다.
    --
    -- 이름 사본(snapshot_part_name)을 두지 않는다. repair_question_item 은 문안에 부품
    -- 이름이 박히지만, 여기서는 화면이 마스터의 현재 이름으로 그룹 제목을 그린다 —
    -- 이름이 바뀌면 바뀐 이름으로 보이는 편이 맞다.
    part_code     VARCHAR(50)  REFERENCES part_code(part_code) ON DELETE RESTRICT,
    -- HIDDEN 항목이 "왜 이것도 보라고 하는가" 한 문장. PART · COMMON 은 NULL 이다.
    -- 조건 제약을 걸지 않는다 — 사용자가 직접 추가하는 항목은 category 만 보내고
    -- 이유를 보내지 않는다. 제약을 걸면 그 정상적인 요청이 500 이 된다.
    reason        VARCHAR(300),
    -- 같은 공통 항목이 한 체크리스트에 두 번 들어가지 않는다. NULL 은 서로 충돌하지 않으므로
    -- AI · 사용자 항목은 몇 개든 들어간다.
    CONSTRAINT uk_rcli_common  UNIQUE (checklist_id, common_code),
    CONSTRAINT ck_rcli_source  CHECK (source IN ('AI','COMMON','USER')),
    CONSTRAINT ck_rcli_category CHECK (category IN ('COMMON','PART','HIDDEN')),
    CONSTRAINT ck_rcli_link    CHECK ((source =  'COMMON' AND common_code IS NOT NULL)
                                   OR (source <> 'COMMON' AND common_code IS NULL)),
    CONSTRAINT ck_rcli_checked CHECK (checked_at IS NULL OR is_checked = TRUE)
);

-- ─── 12-2. 정비소 확인 질문 (S15P21A307-510) ──────────────────
-- 정비소 상담 때 물어볼 질문 목록이다 (S15P21A307-476 · -477). 사고 분석 결과와 예상 견적만으로
-- 만든다 — 견적서 업로드가 전제가 아니다.
--
-- 왜 estimate_validation_question 을 쓰지 않는가. 저 테이블은 validation_id 가 NOT NULL 이라
-- 견적서를 올려 검증한 경우에만 행이 생긴다. -476 은 견적서 없이도 되는 기능이라 붙을 자리가
-- 없다. 저 테이블은 견적서 검증 전용으로 그대로 둔다 — 한 줄도 고치지 않았다.
--
-- 구조는 바로 위 정비 체크리스트(S15P21A307-509)를 그대로 따랐다. 두 기능이 사고 건에 하나씩
-- 붙고 · 생성 상태가 있고 · 재생성되고 · 항목이 딸린다는 점에서 같아서, 다르게 지으면 나중에
-- 읽는 사람이 왜 다른지 찾느라 시간을 쓴다(-510 본문). 머리·항목 두 단 구조와 상태값 네 개는
-- 글자까지 같다. 다른 점은 아래 두 곳뿐이고 각각 주석을 달아 뒀다 — 공통 마스터가 없다는 것과,
-- 항목이 근거(어느 부품·판정에서 나왔는지)를 들고 있다는 것이다.
--
-- member_id 를 두지 않는 이유도 같다 — accident → vehicle → member 로 도달 가능한 이행적
-- 종속이고, 직접 보관하면 사고 주인과 질문 목록 주인이 어긋나도 DB 가 막지 못한다.
--
-- 공통 질문 마스터를 두지 않는다. 체크리스트는 -462 가 문안 6종을 본문에 확정해 줘서
-- repair_checklist_common_item 이 생겼지만, 질문 쪽에는 그런 확정 문안이 어느 티켓에도 없다.
-- -476 의 예시 두 줄은 "형태를 가늠하는 데만 쓴다" 고 못박혀 있어 시드가 아니다. 없는 마스터를
-- 지어내지 않는다 — 나중에 확정되면 그때 체크리스트와 같은 형태로 더하면 된다.

-- 질문 목록 머리. 사고 한 건에 하나다.
CREATE TABLE repair_question (
    question_id    BIGSERIAL   PRIMARY KEY,
    accident_id    BIGINT      NOT NULL REFERENCES accident(accident_id) ON DELETE CASCADE,
    -- 상태 어휘와 CHECK 네 개는 repair_checklist 와 글자까지 같다. 화면이 말하는 대기·완료·실패는
    -- QUEUED+PROCESSING · COMPLETED · FAILED 로 대응한다.
    status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    -- 재생성은 "기존 질문 교체" 라서 머리를 새로 만들지 않고 이 값만 올린다.
    generation_no  SMALLINT    NOT NULL DEFAULT 1,
    failure_reason VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    regenerated_at TIMESTAMPTZ,
    CONSTRAINT uk_rq_accident   UNIQUE (accident_id),
    CONSTRAINT ck_rq_status     CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_rq_done       CHECK (completed_at IS NULL OR status IN ('COMPLETED','FAILED')),
    CONSTRAINT ck_rq_generation CHECK (generation_no >= 1),
    CONSTRAINT ck_rq_regen      CHECK (regenerated_at IS NULL OR generation_no > 1)
);

-- 질문 항목.
CREATE TABLE repair_question_item (
    item_id       BIGSERIAL    PRIMARY KEY,
    question_id   BIGINT       NOT NULL REFERENCES repair_question(question_id) ON DELETE CASCADE,
    -- repair_checklist_item 은 'AI','COMMON','USER' 인데 여기는 COMMON 이 빠진 두 값이다.
    -- 참조할 공통 문안 마스터가 없으니 COMMON 은 FK 없는 유령 값이 된다(위 주석).
    -- AI 하나만 두면 NOT NULL 컬럼이 상수가 되지만, 그렇다고 컬럼을 지우면 재생성 때
    -- "지워도 되는 행" 과 "지우면 안 되는 행" 을 가를 축이 사라진다 — generation_no 가
    -- 이미 재생성을 전제하므로 그 축은 스키마 단계에서 필요하다.
    -- ⚠ 현재 USER 행을 만드는 경로는 없다. -476 은 생성·복사만 요구하고 사용자 추가는
    --   어느 티켓에도 없다. 그 기능이 생기면 여기에 붙고, 끝내 안 생기면 쓰이지 않는 값으로 남는다.
    source        VARCHAR(20)  NOT NULL,
    -- 그대로 복사해 쓰는 완성된 문장이다 — -476 이 개별 복사와 전체 복사를 요구하므로 조각으로
    -- 쪼개 두고 화면에서 조립하게 만들지 않는다. 길이는 repair_checklist_item.content ·
    -- estimate_validation_question.question_text 와 같은 500 이다.
    content       VARCHAR(500) NOT NULL,
    -- ── 근거: 어느 부품·판정에서 나온 질문인가 (-510 본문 "질문 항목 … 근거") ──
    -- damaged_part 를 FK 로 가리키지 않는다. 저 테이블은 job_id 에 매여 있어 사고 한 건을
    -- 재분석하면 다른 job 의 행이 되고, 질문 목록은 job 이 아니라 사고에 하나씩 붙기 때문이다.
    -- ON DELETE CASCADE 도 걸려 있어 job 이 지워지면 질문까지 따라 사라진다. 그래서 값을
    -- 복사해 둔다 — content 를 완성된 문장으로 복사해 두는 것과 같은 이유다.
    --
    -- part_code 는 마스터 FK 로 남긴다. 질문을 부품 코드로 되짚을 수 있어야 하고, 마스터에
    -- 없는 코드가 들어오는 것도 막아야 한다. ON DELETE RESTRICT 인 이유는 하나다 —
    -- 부품이 마스터에서 빠졌다고 사용자가 받아 둔 질문이 같이 사라지면 안 된다(-510 본문).
    -- damaged_part.part_code · repair_checklist_item.common_code 도 같은 RESTRICT 이고,
    -- 관리자 API 에는 애초에 부품 삭제 경로가 없다(is_active 토글뿐). 실제로 걸릴 일이 없는
    -- 방어막이지만, 걸릴 때는 삭제를 막는 쪽이 맞다.
    part_code     VARCHAR(50)  REFERENCES part_code(part_code) ON DELETE RESTRICT,
    -- 생성 시점의 part_code.name_ko 사본. 마스터가 이름을 고쳐도 이미 만들어진 질문 문안
    -- ("리어 도어는 …") 과 근거 표시가 어긋나지 않는다 — accident 의 snapshot_* 과 같은 방식이다.
    snapshot_part_name VARCHAR(50),
    -- damaged_part 의 두 판정을 그대로 복사한다. 값 목록도 ck_dp_damage · ck_dp_method 와 같다.
    -- 셋 다 NULL 이면 부품에 매이지 않은 질문이다 ("순정 외 부품 사용 시 금액 차이는 …").
    damage_type   VARCHAR(20),
    repair_method VARCHAR(20),
    display_order SMALLINT     NOT NULL DEFAULT 0,
    -- updated_at 이 없다. 체크리스트 항목은 완료 체크·메모로 바뀌지만 질문 항목을 고치는 기능은
    -- 없다 — 재생성은 행을 갈아 끼운다. 쓰이지 않을 컬럼을 now() 로 채워 두지 않는다.
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_rqi_source CHECK (source IN ('AI','USER')),
    CONSTRAINT ck_rqi_damage CHECK (damage_type   IN ('Scratched','Separated','Crushed','Breakage')),
    CONSTRAINT ck_rqi_method CHECK (repair_method IN ('coating','sheet_metal','exchange','repair')),
    -- 근거는 통째로 있거나 통째로 없다. 부품 없이 판정만 남은 행은 어느 부품 이야기인지
    -- 알 수 없고, 코드만 있고 이름 사본이 없으면 마스터가 바뀔 때 화면이 과거를 재현하지 못한다.
    CONSTRAINT ck_rqi_basis  CHECK ((part_code IS NULL) = (snapshot_part_name IS NULL)),
    CONSTRAINT ck_rqi_part   CHECK (part_code IS NOT NULL
                                 OR (damage_type IS NULL AND repair_method IS NULL))
);

-- ─── 12-3. 사고 데이터·피드백 검수 (S15P21A307-513) ──────────────────
-- 관리자가 서비스 사고 한 건을 보고 "재학습 데이터로 써도 되는가" 를 판정한 결과다
-- (S15P21A307-349 · -350 · -352).
--
-- 왜 필요한가 — 이미 있는 구멍을 메운다
--   pipeline/jobs/load_service_accidents.py (S15P21A307-223) 가 actual_repair_cost 가 입력된
--   사고를 repair_case 에 source='SERVICE' 로 적재하고 있다. 그런데 그 행은 조회에서 통째로
--   빠진다 — RepairCaseDetailRepository.findPublicCase 와 SimilarCaseRepository.findCases 가
--   둘 다 rc.source <> 'SERVICE' 를 건다. 그 파일 머리말이 이유를 적어 두었다:
--   "공개 정책이 정해지기 전까지 AI-Hub 사례만 연다."
--   이 테이블이 그 "공개 정책" 의 자리다. 사람이 한 건씩 판정한다.
--
-- 왜 audit_log 가 아닌가
--   audit_log 는 **일어난 일의 기록**이고 이것은 **아직 처리되지 않은 상태**다. 상태는 갱신되고
--   기록은 갱신되지 않는다. 한 테이블에 두면 "승인 대기 목록" 이 기록 전체를 훑게 되고,
--   사고당 한 건이라는 제약(uk_ar_accident)을 걸 자리도 없다(target_id 는 FK 없는 VARCHAR 다).
--   승인·반려 **행위** 자체는 -352 가 audit_log 에도 남기면 된다 — 정본은 이 테이블이다.
--
-- 왜 검수 단위가 사고인가
--   재학습 데이터셋에 들어가는 단위가 사고이기 때문이다. load_service_accidents.py 가
--   external_ref = 'svc-<accident_id>' 로 사고 한 건을 사례 한 행에 대응시킨다.
--   job 단위로 두면 사고 하나에 검수 행이 여러 개 쌓이는데 적재는 그것을 구분하지 않고,
--   수정 내역 단건으로 두면 관리자가 봐야 할 맥락(사고 전체)이 흩어진다.
--
--   "그러면 재분석해서 결과가 바뀌면 검수한 것은 언제 것인가" 를 아래 두 열이 답한다 —
--   reviewed_job_id 와 snapshot_actual_repair_cost 가 **무엇을 보고 승인했는지**를 박아 둔다.
--
-- 항목별 검수 테이블을 만들지 않았다
--   사용자가 AI 결과를 고칠 수 있는 경로를 전수 조사한 결과 damaged_part(부위 판정)와
--   estimate_item(견적 행)에는 수정 경로도, "누가 고쳤다" 를 담을 열도 없다. 담을 것이
--   정해지지 않은 테이블을 미리 만들지 않는다 — 필요해지면 그때 review_id 를 FK 로 붙인다.

CREATE TABLE accident_review (
    review_id          BIGSERIAL    PRIMARY KEY,
    accident_id        BIGINT       NOT NULL REFERENCES accident(accident_id) ON DELETE CASCADE,
    -- PENDING · APPROVED · REJECTED. 생성·분석 상태 열거형들(analysis_job · repair_checklist)이
    -- 네 값인 것과 달리 여기는 셋이다 — 사람이 판정하는 일이라 "처리 중" 이 없다.
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    -- 관리자가 본 분석 결과가 어느 실행분이었나. 재분석하면 damaged_part 가 통째로 바뀌므로
    -- 이 값이 없으면 "무엇을 승인했는지" 를 나중에 되짚을 수 없다.
    -- ON DELETE SET NULL 이다 — 분석 실행이 지워져도 판정 기록은 남아야 한다.
    reviewed_job_id    BIGINT       REFERENCES analysis_job(job_id) ON DELETE SET NULL,
    -- 승인 시점의 실제 수리비 사본. 이 값이 재학습 데이터셋에 실린다(-353).
    -- 사용자가 나중에 금액을 고치면 승인받지 않은 값이 흘러가므로, 적재 쪽이 이 사본과
    -- accident.actual_repair_cost 를 대조해 재검수를 걸 수 있어야 한다.
    -- accident 의 snapshot_* 열과 같은 방식이고, 범위 CHECK 도 ck_ac_cost 와 같은 형태다.
    snapshot_actual_repair_cost INTEGER,
    -- 누가 판정했나. 탈퇴해도 판정 자체는 남는다 — audit_log.actor_member_id 와 같은 형태다.
    -- 그래서 이 열을 status 와 CHECK 로 묶지 않았다. 묶으면 회원 삭제가 제약에 걸려 실패한다.
    reviewer_member_id BIGINT       REFERENCES member(member_id) ON DELETE SET NULL,
    -- 반려 사유 (S15P21A307-352). 반려일 때만 있고, 반려가 아니면 없다 — ck_ar_reject 가
    -- 양방향으로 강제한다. 승인에 사유를 받지 않는 이유는 열 이름이 말하는 그대로다.
    reject_reason      VARCHAR(500),
    queued_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_at        TIMESTAMPTZ,
    -- 사고당 한 건이다. 같은 사고가 큐에 두 번 쌓이면 관리자가 같은 것을 두 번 본다.
    -- -350 의 적재 로직은 이 제약 위에서 ON CONFLICT DO NOTHING 으로 멱등해진다.
    CONSTRAINT uk_ar_accident UNIQUE (accident_id),
    CONSTRAINT ck_ar_status   CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    -- 판정 시각은 판정이 끝났을 때만 있다. repair_checklist.ck_rcl_done 과 같은 식이되
    -- 양방향이다 — 여기는 "끝난 상태" 가 둘뿐이라 등가로 쓸 수 있다.
    CONSTRAINT ck_ar_done     CHECK ((status =  'PENDING') = (reviewed_at IS NULL)),
    CONSTRAINT ck_ar_reject   CHECK ((status =  'REJECTED') = (reject_reason IS NOT NULL)),
    CONSTRAINT ck_ar_cost     CHECK (snapshot_actual_repair_cost IS NULL
                                  OR snapshot_actual_repair_cost > 0)
);

-- ============================================================
-- 13. 인덱스
-- ============================================================

-- ── 로그인 (최다 호출) ──
-- SELECT ... WHERE provider=? AND provider_user_id=?  → uk_member_provider 가 커버

-- ── 첫 화면 · 마이페이지 ──
-- 삭제된 차량은 인덱스에서 제외 (목록 조회는 항상 deleted_at IS NULL)
CREATE INDEX ix_vehicle_member    ON vehicle (member_id) WHERE deleted_at IS NULL;
-- 사고 이력 목록: vehicle 을 거쳐 회원을 찾으므로 vehicle_id 선두
CREATE INDEX ix_accident_vehicle  ON accident (vehicle_id, created_at DESC);
-- ix_terms_member 제거: 가입 시 1회 쓰고 조회 없음

-- ── 사고 상세 ──
CREATE INDEX ix_img_accident      ON accident_image (accident_id);
-- ix_asset_image 제거: uk_aia (image_id, variant) 선두가 커버

-- ── 분석 ──
CREATE INDEX ix_job_accident      ON analysis_job (accident_id, created_at DESC);
CREATE INDEX ix_job_queue         ON analysis_job (status, created_at)
    WHERE status IN ('QUEUED','PROCESSING');
-- ix_stage_job 제거: UNIQUE (job_id, stage) 가 이미 커버
-- ix_air_job 제거: uk_air (job_id, image_id) 선두가 커버
-- ix_dp_job 제거: uk_dp (job_id, part_code) 선두가 커버

-- ── 견적 ──
-- ix_est_job 제거: uk_est (job_id, version) 이 동일 순서를 커버
CREATE INDEX ix_ei_estimate       ON estimate_item (estimate_id);
CREATE INDEX ix_er_estimate       ON estimate_report (estimate_id, created_at DESC);
CREATE INDEX ix_er_queue          ON estimate_report (status, created_at)
    WHERE status IN ('QUEUED','PROCESSING');

-- ── 유사 사례 (핵심) ──
-- case_id 제거로 43MB -> 약 20MB
CREATE INDEX ix_rci_search        ON repair_case_item (part_code, work_type);
CREATE INDEX ix_rci_case          ON repair_case_item (case_id);
CREATE INDEX ix_dve_batch         ON data_validation_error (batch_job_execution_id, created_at);
CREATE INDEX ix_dve_type          ON data_validation_error (error_type, created_at);
CREATE INDEX ix_dve_case_ref      ON data_validation_error (case_external_ref);
CREATE INDEX ix_rc_class          ON repair_case (car_class);
CREATE INDEX ix_rc_model          ON repair_case (model_id) WHERE model_id IS NOT NULL;
CREATE INDEX ix_rcimg_case        ON repair_case_image (case_id);

-- ── 검색 feature·임베딩 ──
CREATE INDEX ix_rcdf_damage       ON repair_case_damage_feature (damage_type, pipeline_version_id)
    WHERE is_searchable;
CREATE INDEX ix_rcdf_part         ON repair_case_damage_feature (part_code, damage_type)
    WHERE is_searchable;
CREATE INDEX ix_rcdf_image        ON repair_case_damage_feature (case_image_id);
CREATE UNIQUE INDEX ux_fpv_active ON feature_pipeline_version (is_active)
    WHERE is_active;
CREATE UNIQUE INDEX ux_emv_active ON embedding_model_version (is_active)
    WHERE is_active;
-- 주의: 대량 적재 전에 만들면 INSERT 가 느려집니다 (실측 11.4만건 65초).
--       초기 backfill 은 인덱스 없이 적재한 뒤 이 문을 실행하세요.
CREATE INDEX ix_roi_hnsw          ON repair_case_roi_embedding
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ix_rcipa_image       ON repair_case_image_part_annotation (case_image_id);

-- ── 견적서 검증 ──
CREATE INDEX ix_ev_member         ON estimate_validation (member_id, created_at DESC);
CREATE INDEX ix_ev_accident       ON estimate_validation (accident_id, created_at DESC);
CREATE INDEX ix_ev_queue          ON estimate_validation (status, created_at)
    WHERE status IN ('QUEUED','PROCESSING');
-- ix_evi_validation 제거: uk_evi (validation_id, line_no) 와 동일
-- 검증 결과 PDF 생성 큐. estimate_validation_report 의 PK 는 validation_id 라
-- status·created_at 조회를 덮지 못한다 — 워커의 큐 조회·소진 조회·고아 회수 3개가 그 형태다.
-- 형태는 ix_er_queue·ix_job_queue 와 같다. 부분 인덱스라 COMPLETED·FAILED 로 끝난 행은
-- 인덱스에서 빠지므로, 검증이 쌓여도 인덱스는 큐에 남은 건수만큼만 커진다.
-- retry_count 는 넣지 않았다: 0~3 네 값뿐이라 선택도가 낮고 크기만 커진다.
CREATE INDEX ix_evr_queue         ON estimate_validation_report (status, created_at)
    WHERE status IN ('QUEUED','PROCESSING');

-- ── 감사 로그 ──
CREATE INDEX ix_audit_actor       ON audit_log (actor_member_id, created_at DESC);
CREATE INDEX ix_audit_target      ON audit_log (target_type, target_id, created_at DESC);
-- 기간별 감사 조회 (actor·target 조건 없이)
CREATE INDEX ix_audit_created     ON audit_log (created_at DESC);
CREATE INDEX ix_audit_action      ON audit_log (action_type, created_at DESC);
CREATE INDEX ix_rmr_lookup        ON repair_method_rule (damage_type, is_active, priority);

-- ── 멱등성 ──
CREATE UNIQUE INDEX ux_job_inflight ON analysis_job (accident_id)
    WHERE status IN ('QUEUED','PROCESSING');
-- 같은 requestId 의 callback 이 두 번 와도 견적 버전이 늘지 않게 한다.
-- NULL 은 서로 충돌하지 않으므로 값이 있는 행만 겹치지 않는다 (S15P21A307-155).
CREATE UNIQUE INDEX ux_aj_request   ON analysis_job (request_id)
    WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX ux_er_inflight  ON estimate_report (estimate_id)
    WHERE status IN ('QUEUED','PROCESSING');

-- ── 정비 체크리스트 ──
-- 항목 목록은 늘 한 체크리스트 것을 정렬해 읽고, 진행률(완료/전체)도 같은 범위를 센다.
CREATE INDEX ix_rcli_checklist    ON repair_checklist_item (checklist_id, display_order);
-- ix_rcl_accident 제거: uk_rcl_accident 가 그대로 커버한다

-- ── 정비소 확인 질문 ──
-- 질문 목록은 늘 한 사고 것을 정렬해 읽는다. 체크리스트의 ix_rcli_checklist 와 같은 형태다.
CREATE INDEX ix_rqi_question      ON repair_question_item (question_id, display_order);
-- ix_rq_accident 제거: uk_rq_accident 가 그대로 커버한다
-- ix_rqi_part 제거: 부품 코드로 질문을 거슬러 찾는 화면이 아직 없다. 생기면 그때 만든다

-- ── 사고 데이터 검수 ──
-- 관리자 화면이 늘 "대기" 만 오래된 순으로 읽는다. 판정이 끝난 행은 부분 인덱스에서 빠지므로
-- 승인·반려가 쌓여도 인덱스는 대기 건수만큼만 커진다 — ix_job_queue · ix_ev_queue 와 같은 형태다.
CREATE INDEX ix_ar_queue          ON accident_review (status, queued_at)
    WHERE status = 'PENDING';
-- ix_ar_accident 제거: uk_ar_accident 가 그대로 커버한다
