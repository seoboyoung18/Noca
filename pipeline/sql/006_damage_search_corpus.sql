-- A307 검색 코퍼스 계약 006 · damage 중심 유사 사례 검색 전환
--
-- 적용 대상: 기존 PostgreSQL 검색 DB
-- 정본 반영: Docs/Erd/A307_ddl_final.sql
-- 설계 계약: Docs/Erd/A307_DAMAGE_SEARCH_SCHEMA.md
--
-- 이 migration은 기존 damage_part 적재 행을 삭제하지 않는다. 기존 행에 이미지
-- 역할을 명시하고, 새 damage ROI가 부품 미확정 상태로 적재될 수 있게 한다.
-- DB·S3 재적재는 이 migration만으로 수행하지 않는다.

BEGIN;

ALTER TABLE repair_case_image
    ADD COLUMN IF NOT EXISTS image_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS source_dataset_split VARCHAR(20);

-- 이 migration 이전의 검색 이미지는 damage_part 전용 loader가 적재했다.
UPDATE repair_case_image
   SET image_type = 'DAMAGE_PART'
 WHERE image_type IS NULL;

ALTER TABLE repair_case_image
    ALTER COLUMN image_type SET NOT NULL;

ALTER TABLE repair_case_image
    DROP CONSTRAINT IF EXISTS ck_rcimg_type,
    DROP CONSTRAINT IF EXISTS ck_rcimg_source_split;

ALTER TABLE repair_case_image
    ADD CONSTRAINT ck_rcimg_type
        CHECK (image_type IN ('DAMAGE', 'DAMAGE_PART')),
    ADD CONSTRAINT ck_rcimg_source_split
        CHECK (source_dataset_split IS NULL
            OR source_dataset_split IN ('TRAIN', 'VALIDATION'));

ALTER TABLE repair_case_roi_embedding
    ALTER COLUMN part_code DROP NOT NULL;

ALTER TABLE repair_case_item
    ADD COLUMN IF NOT EXISTS source_item_key VARCHAR(100);

-- 기존 표본 적재분은 원천 배열 순번을 복원할 수 없다. 기존 PK를 안정적인 backfill 키로
-- 사용하고, 새 loader는 item-00001 형식의 원천 순번을 넣는다.
UPDATE repair_case_item
   SET source_item_key = 'legacy-' || case_item_id
 WHERE source_item_key IS NULL;

ALTER TABLE repair_case_item
    ALTER COLUMN source_item_key SET NOT NULL;

ALTER TABLE repair_case_item
    DROP CONSTRAINT IF EXISTS uk_rci_source_item;

ALTER TABLE repair_case_item
    ADD CONSTRAINT uk_rci_source_item UNIQUE (case_id, source_item_key);

CREATE TABLE IF NOT EXISTS repair_case_image_part_annotation (
    case_image_part_annotation_id BIGSERIAL    PRIMARY KEY,
    case_image_id                 BIGINT       NOT NULL REFERENCES repair_case_image(case_image_id) ON DELETE CASCADE,
    part_code                     VARCHAR(50)  NOT NULL REFERENCES part_code(part_code) ON DELETE RESTRICT,
    source_annotation_ref         VARCHAR(255) NOT NULL,
    part_polygon                  JSONB,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_rcipa_source UNIQUE (case_image_id, source_annotation_ref)
);

CREATE INDEX IF NOT EXISTS ix_roi_damage_filter
    ON repair_case_roi_embedding (damage_type, model_version_id)
    WHERE is_searchable;

CREATE INDEX IF NOT EXISTS ix_rcipa_image
    ON repair_case_image_part_annotation (case_image_id);

COMMIT;

-- 적용 확인
-- SELECT image_type, COUNT(*) FROM repair_case_image GROUP BY image_type;
-- SELECT COUNT(*) AS searchable_unknown_part_roi
--   FROM repair_case_roi_embedding
--  WHERE is_searchable AND part_code IS NULL;
-- SELECT indexname FROM pg_indexes
--  WHERE tablename = 'repair_case_roi_embedding';

-- 되돌리기 전 확인: 신규 DAMAGE 행 또는 part_code NULL ROI가 있으면 데이터 손실 없이
-- 되돌릴 수 없다. 해당 데이터를 별도로 처리한 뒤 아래를 검토한다.
-- DROP INDEX IF EXISTS ix_rcipa_image;
-- DROP INDEX IF EXISTS ix_roi_damage_filter;
-- DROP TABLE IF EXISTS repair_case_image_part_annotation;
-- ALTER TABLE repair_case_roi_embedding ALTER COLUMN part_code SET NOT NULL;
-- ALTER TABLE repair_case_image DROP CONSTRAINT IF EXISTS ck_rcimg_source_split;
-- ALTER TABLE repair_case_image DROP CONSTRAINT IF EXISTS ck_rcimg_type;
-- ALTER TABLE repair_case_image DROP COLUMN source_dataset_split;
-- ALTER TABLE repair_case_image DROP COLUMN image_type;
