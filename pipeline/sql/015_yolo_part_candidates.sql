-- v2 DAMAGE corpus: YOLO part detection evidence is kept separate from feature.part_code.
-- Existing repair hints remain audit-only; this migration never changes feature/embedding rows.
BEGIN;

CREATE TABLE IF NOT EXISTS repair_case_image_part_inference (
    image_part_inference_id BIGSERIAL PRIMARY KEY,
    case_image_id BIGINT NOT NULL REFERENCES repair_case_image(case_image_id) ON DELETE CASCADE,
    part_model_name VARCHAR(100) NOT NULL,
    part_model_version VARCHAR(50) NOT NULL,
    weights_sha256 VARCHAR(64),
    run_status VARCHAR(30) NOT NULL,
    detected_part_count INTEGER NOT NULL DEFAULT 0,
    raw_predictions JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_code VARCHAR(100),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rcipi_model UNIQUE (case_image_id, part_model_name, part_model_version),
    CONSTRAINT ck_rcipi_status CHECK (run_status IN ('SUCCEEDED','PART_NOT_DETECTED','ERROR')),
    CONSTRAINT ck_rcipi_count CHECK (detected_part_count >= 0),
    CONSTRAINT ck_rcipi_error CHECK (
        (run_status = 'ERROR' AND error_code IS NOT NULL) OR
        (run_status <> 'ERROR' AND error_code IS NULL)
    )
);

CREATE TABLE IF NOT EXISTS repair_case_damage_feature_part_mapping (
    damage_feature_id BIGINT NOT NULL REFERENCES repair_case_damage_feature(damage_feature_id) ON DELETE CASCADE,
    image_part_inference_id BIGINT NOT NULL REFERENCES repair_case_image_part_inference(image_part_inference_id) ON DELETE CASCADE,
    mapping_status VARCHAR(20) NOT NULL,
    primary_candidate_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_rcdfpm PRIMARY KEY (damage_feature_id, image_part_inference_id),
    CONSTRAINT ck_rcdfpm_status CHECK (mapping_status IN ('PAIRED','UNPAIRED','AMBIGUOUS'))
);

CREATE TABLE IF NOT EXISTS repair_case_damage_feature_part_candidate (
    candidate_id BIGSERIAL PRIMARY KEY,
    damage_feature_id BIGINT NOT NULL REFERENCES repair_case_damage_feature(damage_feature_id) ON DELETE CASCADE,
    image_part_inference_id BIGINT NOT NULL REFERENCES repair_case_image_part_inference(image_part_inference_id) ON DELETE CASCADE,
    candidate_index SMALLINT NOT NULL,
    part_code VARCHAR(50) NOT NULL REFERENCES part_code(part_code) ON DELETE RESTRICT,
    part_confidence NUMERIC(5,4) NOT NULL,
    part_bbox JSONB NOT NULL,
    overlap_score NUMERIC(6,5) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rcdfpc_candidate UNIQUE (damage_feature_id, image_part_inference_id, candidate_index),
    CONSTRAINT ck_rcdfpc_index CHECK (candidate_index >= 0),
    CONSTRAINT ck_rcdfpc_confidence CHECK (part_confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_rcdfpc_overlap CHECK (overlap_score BETWEEN 0 AND 1)
);

ALTER TABLE repair_case_damage_feature_part_mapping
    ADD CONSTRAINT fk_rcdfpm_primary_candidate
    FOREIGN KEY (primary_candidate_id)
    REFERENCES repair_case_damage_feature_part_candidate(candidate_id)
    ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ix_rcipi_image_status
    ON repair_case_image_part_inference (case_image_id, run_status);
CREATE INDEX IF NOT EXISTS ix_rcdfpm_inference_status
    ON repair_case_damage_feature_part_mapping (image_part_inference_id, mapping_status);
CREATE INDEX IF NOT EXISTS ix_rcdfpc_inference
    ON repair_case_damage_feature_part_candidate (image_part_inference_id);
CREATE INDEX IF NOT EXISTS ix_rcdfpc_part
    ON repair_case_damage_feature_part_candidate (part_code);

COMMIT;
