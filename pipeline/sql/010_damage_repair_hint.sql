-- A307 검색 corpus 계약 010 · DAMAGE repair hint 보존
--
-- DAMAGE ROI의 part_code는 직접 부품 bbox 근거가 없으므로 NULL로 유지한다.
-- 원천 annotation의 repair 부품 후보는 이 테이블에서 별도 감사·실험용으로만
-- 보존하며, 검색 STRICT 필터와 견적 산출의 part_code로 사용하지 않는다.

BEGIN;

CREATE TABLE IF NOT EXISTS repair_case_damage_feature_part_hint (
    hint_id             BIGSERIAL PRIMARY KEY,
    damage_feature_id   BIGINT       NOT NULL
        REFERENCES repair_case_damage_feature(damage_feature_id) ON DELETE CASCADE,
    part_code           VARCHAR(50)  NOT NULL
        REFERENCES part_code(part_code) ON DELETE RESTRICT,
    hint_source         VARCHAR(20)  NOT NULL DEFAULT 'REPAIR',
    raw_part_name       VARCHAR(100) NOT NULL,
    repair_methods      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    source_annotation_ref VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_rcdfph_source CHECK (hint_source = 'REPAIR'),
    CONSTRAINT ck_rcdfph_methods CHECK (jsonb_typeof(repair_methods) = 'array'),
    CONSTRAINT uk_rcdfph_source UNIQUE (
        damage_feature_id, part_code, hint_source, source_annotation_ref
    )
);

CREATE INDEX IF NOT EXISTS ix_rcdfph_feature
    ON repair_case_damage_feature_part_hint (damage_feature_id);
CREATE INDEX IF NOT EXISTS ix_rcdfph_part
    ON repair_case_damage_feature_part_hint (part_code);

COMMIT;
