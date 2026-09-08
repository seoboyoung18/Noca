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
    CONSTRAINT ck_pc_zone CHECK (layout_zone IN ('FRONT','REAR','SIDE_L','SIDE_R','TOP','UNDER'))
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
    s3_key_overlay   VARCHAR(500),
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
    repair_method   VARCHAR(20)  NOT NULL,
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
    part_code     VARCHAR(50) NOT NULL REFERENCES part_code(part_code) ON DELETE RESTRICT,
    raw_item_name VARCHAR(500),
    line_type     VARCHAR(20) NOT NULL DEFAULT 'WORK',
    work_type     VARCHAR(20),
    work_code     VARCHAR(30),
    hq            NUMERIC(6,2),
    reference_part_price INTEGER,
    part_cost     INTEGER,
    labor_cost    INTEGER,
    pre_adjustment_part_cost  INTEGER,
    pre_adjustment_labor_cost INTEGER,
    post_adjustment_part_cost  INTEGER,
    post_adjustment_labor_cost INTEGER,
    item_total    INTEGER,
    CONSTRAINT ck_rci_line_type CHECK (line_type IN ('WORK','PART_PRICE')),
    CONSTRAINT ck_rci_work CHECK (
        (line_type = 'PART_PRICE' AND work_type IS NULL AND work_code IS NULL)
        OR
        (line_type = 'WORK' AND work_type IS NOT NULL AND work_code IS NOT NULL
         AND (work_type, work_code) IN (
             ('교환', 'EXCHANGE'),
             ('탈착', 'REMOVE_INSTALL'),
             ('판금', 'SHEET_METAL'),
             ('도장', 'COATING'),
             ('오버홀', 'OVERHAUL'),
             ('수리', 'REPAIR')
         ))
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
    case_image_id    BIGSERIAL    PRIMARY KEY,
    case_id          BIGINT       NOT NULL REFERENCES repair_case(case_id) ON DELETE CASCADE,
    source_image_ref VARCHAR(255) NOT NULL,
    storage_key      VARCHAR(500) NOT NULL,
    blur_key         VARCHAR(500),
    angle_tag        VARCHAR(20),
    quality_status   VARCHAR(20),
    is_searchable    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_rci_src UNIQUE (source_image_ref)
);

-- ─── 9. 임베딩 (고속 증가) ───────────────────────────────────
CREATE TABLE repair_case_roi_embedding (
    roi_embedding_id BIGSERIAL    PRIMARY KEY,
    case_image_id    BIGINT       NOT NULL REFERENCES repair_case_image(case_image_id)          ON DELETE CASCADE,
    model_version_id BIGINT       NOT NULL REFERENCES embedding_model_version(model_version_id) ON DELETE RESTRICT,
    part_code        VARCHAR(50)  NOT NULL REFERENCES part_code(part_code)                      ON DELETE RESTRICT,
    damage_type      VARCHAR(20)  NOT NULL,
    roi_index        SMALLINT     NOT NULL,
    damage_polygon   JSONB        NOT NULL,
    confidence       NUMERIC(5,4),
    embedding        vector(768)  NOT NULL,
    is_searchable    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_roi      UNIQUE (case_image_id, model_version_id, roi_index),
    CONSTRAINT ck_roi_type CHECK (damage_type IN ('Scratched','Separated','Crushed','Breakage'))
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
    item_min          INTEGER     NOT NULL,
    item_median       INTEGER     NOT NULL,
    item_max          INTEGER     NOT NULL,
    ref_case_count    INTEGER     NOT NULL,
    ref_condition     JSONB       NOT NULL,
    is_low_confidence BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_ei_method CHECK (repair_method IN ('coating','sheet_metal','exchange','repair'))
);

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
    before_data     JSONB,
    after_data      JSONB,
    ip_address      INET,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
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

-- ── 임베딩 ──
CREATE INDEX ix_roi_filter        ON repair_case_roi_embedding (part_code, damage_type)
    WHERE is_searchable;
-- 주의: 대량 적재 전에 만들면 INSERT 가 느려집니다 (실측 11.4만건 65초).
--       초기 backfill 은 인덱스 없이 적재한 뒤 이 문을 실행하세요.
CREATE INDEX ix_roi_hnsw          ON repair_case_roi_embedding
    USING hnsw (embedding vector_cosine_ops);

-- ── 견적서 검증 ──
CREATE INDEX ix_ev_member         ON estimate_validation (member_id, created_at DESC);
CREATE INDEX ix_ev_accident       ON estimate_validation (accident_id, created_at DESC);
CREATE INDEX ix_ev_queue          ON estimate_validation (status, created_at)
    WHERE status IN ('QUEUED','PROCESSING');
-- ix_evi_validation 제거: uk_evi (validation_id, line_no) 와 동일

-- ── 감사 로그 ──
CREATE INDEX ix_audit_actor       ON audit_log (actor_member_id, created_at DESC);
CREATE INDEX ix_audit_target      ON audit_log (target_type, target_id, created_at DESC);
-- 기간별 감사 조회 (actor·target 조건 없이)
CREATE INDEX ix_audit_created     ON audit_log (created_at DESC);

-- ── 멱등성 ──
CREATE UNIQUE INDEX ux_job_inflight ON analysis_job (accident_id)
    WHERE status IN ('QUEUED','PROCESSING');
CREATE UNIQUE INDEX ux_er_inflight  ON estimate_report (estimate_id)
    WHERE status IN ('QUEUED','PROCESSING');
