-- A307 검색 표본 적재 계약 002
-- 기존 로컬 DB를 작업 6종·부품가격 별도 행·AIHUB_SC 적재 구조로 올린다.

BEGIN;

ALTER TABLE repair_case
    DROP CONSTRAINT IF EXISTS ck_rc_src;
ALTER TABLE repair_case
    ADD CONSTRAINT ck_rc_src CHECK (source IN ('AIHUB_AS', 'AIHUB_SC', 'SERVICE'));
ALTER TABLE repair_case
    ALTER COLUMN total_cost DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS claim_amount INTEGER,
    ADD COLUMN IF NOT EXISTS paid_amount INTEGER;

ALTER TABLE repair_case_item
    DROP CONSTRAINT IF EXISTS ck_rci_work;
ALTER TABLE repair_case_item
    ADD COLUMN IF NOT EXISTS raw_item_name VARCHAR(500),
    ADD COLUMN IF NOT EXISTS line_type VARCHAR(20) NOT NULL DEFAULT 'WORK',
    ADD COLUMN IF NOT EXISTS work_code VARCHAR(30),
    ADD COLUMN IF NOT EXISTS reference_part_price INTEGER,
    ADD COLUMN IF NOT EXISTS pre_adjustment_part_cost INTEGER,
    ADD COLUMN IF NOT EXISTS pre_adjustment_labor_cost INTEGER,
    ADD COLUMN IF NOT EXISTS post_adjustment_part_cost INTEGER,
    ADD COLUMN IF NOT EXISTS post_adjustment_labor_cost INTEGER,
    ALTER COLUMN work_type DROP NOT NULL,
    ALTER COLUMN item_total DROP NOT NULL;
ALTER TABLE repair_case_item
    DROP CONSTRAINT IF EXISTS ck_rci_line_type;
ALTER TABLE repair_case_item
    ADD CONSTRAINT ck_rci_line_type CHECK (line_type IN ('WORK', 'PART_PRICE'));
ALTER TABLE repair_case_item
    ADD CONSTRAINT ck_rci_work CHECK (
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
    );

CREATE TABLE IF NOT EXISTS batch_job_execution (
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

CREATE TABLE IF NOT EXISTS data_validation_error (
    data_validation_error_id BIGSERIAL PRIMARY KEY,
    batch_job_execution_id   BIGINT NOT NULL REFERENCES batch_job_execution(batch_job_execution_id) ON DELETE RESTRICT,
    error_type               VARCHAR(50) NOT NULL,
    source_ref               VARCHAR(500),
    case_external_ref        VARCHAR(100),
    category_id              VARCHAR(100),
    error_detail             JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_dve_batch ON data_validation_error (batch_job_execution_id, created_at);
CREATE INDEX IF NOT EXISTS ix_dve_type ON data_validation_error (error_type, created_at);
CREATE INDEX IF NOT EXISTS ix_dve_case_ref ON data_validation_error (case_external_ref);

COMMIT;
