-- A307 검색 코퍼스 계약 007 · damage feature 정본 계층
--
-- 006 적용 및 검색 사례 테이블 초기화 후 적용한다.
-- repair_case_damage_feature가 damage_type·geometry·part 연결 상태의 정본이고,
-- repair_case_roi_embedding은 이 feature와 model_version의 벡터만 보관한다.
--
-- pair_rule_version / pair_threshold / roi_padding_ratio는 feature_pipeline_version에
-- 기록한다. 규칙을 바꾸면 새 pipeline_version_id를 만들고 기존 feature·embedding과
-- 공존시킬 수 있다.

BEGIN;

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
    CONSTRAINT ck_rcdf_type CHECK (damage_type IN ('Scratched','Separated','Crushed','Breakage')),
    CONSTRAINT ck_rcdf_pair CHECK (pair_status IN ('PAIRED','UNPAIRED','AMBIGUOUS'))
);

CREATE INDEX ix_rcdf_damage
    ON repair_case_damage_feature (damage_type, pipeline_version_id)
    WHERE is_searchable;

CREATE INDEX ix_rcdf_part
    ON repair_case_damage_feature (part_code, damage_type)
    WHERE is_searchable;

CREATE INDEX ix_rcdf_image
    ON repair_case_damage_feature (case_image_id);

-- 기존 ROI 테이블의 metadata 정본을 feature 테이블로 이동한다.
-- 기존 적재 전부를 초기화해 실제 데이터가 0건인 상태에서 실행한다.
ALTER TABLE repair_case_roi_embedding
    DROP CONSTRAINT IF EXISTS repair_case_roi_embedding_part_code_fkey,
    DROP CONSTRAINT IF EXISTS uk_roi,
    DROP CONSTRAINT IF EXISTS ck_roi_type;

DROP INDEX IF EXISTS ix_roi_filter;
DROP INDEX IF EXISTS ix_roi_damage_filter;

ALTER TABLE repair_case_roi_embedding
    ADD COLUMN damage_feature_id BIGINT
        REFERENCES repair_case_damage_feature(damage_feature_id) ON DELETE CASCADE;

ALTER TABLE repair_case_roi_embedding
    DROP COLUMN damage_type,
    DROP COLUMN damage_polygon,
    DROP COLUMN part_code,
    DROP COLUMN roi_index,
    DROP COLUMN is_searchable;

-- roi_index는 feature 정본으로 이동했다. 하나의 feature에는 model_version별
-- embedding을 하나만 허용하는 것이 기존 (case_image_id, model_version_id, roi_index)
-- 계약을 보존하면서 metadata 중복을 제거하는 자연스러운 대체 키다.
ALTER TABLE repair_case_roi_embedding
    ADD CONSTRAINT uk_roi UNIQUE (damage_feature_id, model_version_id);

-- 적용 확인
-- SELECT to_regclass('feature_pipeline_version'),
--        to_regclass('repair_case_damage_feature');
-- SELECT indexname FROM pg_indexes
--  WHERE tablename = 'repair_case_damage_feature'
--  ORDER BY indexname;
-- SELECT column_name FROM information_schema.columns
--  WHERE table_schema = 'public'
--    AND table_name = 'repair_case_roi_embedding'
--  ORDER BY ordinal_position;

COMMIT;

-- 되돌리기 주의:
-- feature_pipeline_version·repair_case_damage_feature에 데이터가 생긴 뒤에는
-- repair_case_roi_embedding.damage_feature_id를 먼저 NULL 처리하거나 embedding을
-- 별도 보존해야 한다. 아래는 A-5 적재 전, 모든 feature·embedding이 0건일 때만
-- 검토할 수 있는 수동 rollback 예시다.
-- DROP INDEX IF EXISTS ix_rcdf_damage;
-- DROP INDEX IF EXISTS ix_rcdf_part;
-- DROP INDEX IF EXISTS ix_rcdf_image;
-- ALTER TABLE repair_case_roi_embedding DROP CONSTRAINT IF EXISTS uk_roi;
-- ALTER TABLE repair_case_roi_embedding ADD COLUMN damage_type VARCHAR(20),
--     ADD COLUMN damage_polygon JSONB, ADD COLUMN part_code VARCHAR(50),
--     ADD COLUMN roi_index SMALLINT, ADD COLUMN is_searchable BOOLEAN DEFAULT TRUE;
-- DROP TABLE IF EXISTS repair_case_damage_feature;
-- DROP TABLE IF EXISTS feature_pipeline_version;
