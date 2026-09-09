-- A307 원천 스테이징 계층 003
-- 원천 견적 JSON 원문과 AI-Hub 차량파손 라벨을 Raw 계층에 보존한다.
-- 검색 테이블(repair_case 계열)과는 별개 계층이며, 의미 분류·비용 계산은 여기서 하지 않는다.

BEGIN;

-- 원천 견적 JSON 원문 보존.
-- repair_case.case_id을 FK로 두지 않는다. Raw 적재가 repair_case 적재를 기다리게 되면
-- 원문을 먼저 확보한다는 이 계층의 목적이 사라진다.
-- (source, external_ref)는 repair_case의 uk_rc와 같은 키라 나중에 그대로 조인된다.
-- PK가 있으므로 ON CONFLICT로 재실행이 멱등하다.
-- 인덱스는 두지 않는다. 적재 속도가 우선이고 payload 조회 패턴이 아직 없다.
CREATE TABLE IF NOT EXISTS aihub_estimate_raw (
    source       varchar(20)  NOT NULL,
    external_ref varchar(50)  NOT NULL,
    payload      jsonb        NOT NULL,
    source_file  varchar(500) NOT NULL,
    loaded_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (source, external_ref)
);

COMMENT ON TABLE aihub_estimate_raw IS '원천 견적 JSON 원문. 의미 분류 전 단계의 무손실 보존 계층';
COMMENT ON COLUMN aihub_estimate_raw.source_file IS '실패 행 추적용 원천 파일 경로';

-- 이하 AI-Hub 차량파손 이미지 데이터 라벨 원천 4종.
-- ERD/A307_AIHUB_DAMAGE_DATASET.sql에서 내용 변경 없이 옮겼다.
CREATE TABLE IF NOT EXISTS aihub_vehicle_case (
    case_id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_ref    varchar(50) NOT NULL,
    car_class_raw   varchar(100),
    model_year      smallint,
    color_raw       varchar(50),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_aihub_vehicle_case_ref UNIQUE (external_ref),
    CONSTRAINT ck_aihub_vehicle_case_year
        CHECK (model_year IS NULL OR model_year BETWEEN 1950 AND 2100)
);

CREATE TABLE IF NOT EXISTS aihub_vehicle_image (
    image_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id           bigint NOT NULL REFERENCES aihub_vehicle_case(case_id) ON DELETE CASCADE,
    dataset_split     varchar(10) NOT NULL,
    label_type        varchar(20) NOT NULL,
    source_image_id   bigint,
    file_name         varchar(255) NOT NULL,
    file_path         text NOT NULL,
    label_path        text NOT NULL,
    width_px          integer NOT NULL,
    height_px         integer NOT NULL,
    source_name       varchar(100),
    source_created_on date,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_aihub_vehicle_image UNIQUE (dataset_split, label_type, file_name),
    CONSTRAINT ck_aihub_vehicle_image_split CHECK (dataset_split IN ('TRAIN', 'VALIDATION')),
    CONSTRAINT ck_aihub_vehicle_image_label CHECK (label_type IN ('DAMAGE', 'DAMAGE_PART')),
    CONSTRAINT ck_aihub_vehicle_image_size CHECK (width_px > 0 AND height_px > 0)
);

CREATE TABLE IF NOT EXISTS aihub_damage_annotation (
    annotation_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    image_id               bigint NOT NULL REFERENCES aihub_vehicle_image(image_id) ON DELETE CASCADE,
    source_annotation_id   bigint NOT NULL,
    damage_type_raw        varchar(50),
    part_name_raw          varchar(100),
    severity_level         smallint,
    area_px                numeric(16,2),
    bbox                   jsonb,
    segmentation           jsonb,
    raw_repair             jsonb,
    raw_annotation         jsonb NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_aihub_damage_annotation UNIQUE (image_id, source_annotation_id),
    CONSTRAINT ck_aihub_damage_annotation_level
        CHECK (severity_level IS NULL OR severity_level BETWEEN 1 AND 4),
    CONSTRAINT ck_aihub_damage_annotation_bbox
        CHECK (bbox IS NULL OR jsonb_typeof(bbox) = 'array'),
    CONSTRAINT ck_aihub_damage_annotation_segmentation
        CHECK (segmentation IS NULL OR jsonb_typeof(segmentation) = 'array')
);

CREATE TABLE IF NOT EXISTS aihub_annotation_repair_method (
    annotation_id    bigint NOT NULL REFERENCES aihub_damage_annotation(annotation_id) ON DELETE CASCADE,
    part_name_raw    varchar(100) NOT NULL,
    repair_method    varchar(30) NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (annotation_id, part_name_raw, repair_method),
    CONSTRAINT ck_aihub_annotation_repair_method
        CHECK (repair_method IN ('coating', 'repair', 'sheet_metal', 'exchange'))
);

CREATE INDEX IF NOT EXISTS ix_aihub_vehicle_image_case
    ON aihub_vehicle_image(case_id);
CREATE INDEX IF NOT EXISTS ix_aihub_vehicle_image_split_type
    ON aihub_vehicle_image(dataset_split, label_type);
CREATE INDEX IF NOT EXISTS ix_aihub_annotation_image
    ON aihub_damage_annotation(image_id);
CREATE INDEX IF NOT EXISTS ix_aihub_annotation_damage
    ON aihub_damage_annotation(damage_type_raw)
    WHERE damage_type_raw IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_aihub_annotation_part
    ON aihub_damage_annotation(part_name_raw)
    WHERE part_name_raw IS NOT NULL;

COMMENT ON TABLE aihub_vehicle_case IS 'AI-Hub category_id 기준 사고 차량';
COMMENT ON TABLE aihub_vehicle_image IS 'Training/Validation 이미지와 라벨 파일 메타데이터';
COMMENT ON TABLE aihub_damage_annotation IS '손상 또는 부품 polygon 원천 annotation';
COMMENT ON TABLE aihub_annotation_repair_method IS 'repair 라벨을 부품과 작업 단위로 정규화한 값';
COMMENT ON COLUMN aihub_damage_annotation.raw_annotation IS '원천 라벨 유실 방지를 위한 annotation 전체 JSON';

COMMIT;
