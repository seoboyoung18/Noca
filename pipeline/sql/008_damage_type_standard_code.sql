-- A307 검색 코퍼스 계약 008 · feature hardening 및 damage_type 표준 코드 전환
--
-- 대상 데이터는 A-4 초기화 직후 0건이다. 데이터 이관은 수행하지 않는다.
-- damaged_part와 repair_cost_stat은 다른 담당 영역이므로 이 migration에서 변경하지 않는다.

BEGIN;

-- 007에서 NULL 허용으로 추가한 연결을 필수로 만든다. 고아 embedding은
-- damage metadata와 pipeline version을 식별할 수 없으므로 허용하지 않는다.
ALTER TABLE repair_case_roi_embedding
    ALTER COLUMN damage_feature_id SET NOT NULL;

-- 동시에 활성화된 pipeline/model을 방지한다. A/B 모델 비교가 필요하면
-- embedding model 정책과 이 partial unique index를 먼저 협의·변경한다.
CREATE UNIQUE INDEX ux_fpv_active
    ON feature_pipeline_version (is_active)
    WHERE is_active;

CREATE UNIQUE INDEX ux_emv_active
    ON embedding_model_version (is_active)
    WHERE is_active;

-- 표준화 catalog의 key와 동일한 대문자 damage code를 사용한다.
ALTER TABLE repair_case_damage_feature
    DROP CONSTRAINT ck_rcdf_type;

ALTER TABLE repair_case_damage_feature
    ADD CONSTRAINT ck_rcdf_type
        CHECK (damage_type IN ('SCRATCHED','SEPARATED','CRUSHED','BREAKAGE'));

COMMIT;

-- 적용 확인
-- SELECT conname, pg_get_constraintdef(oid)
--   FROM pg_constraint
--  WHERE conrelid = 'repair_case_damage_feature'::regclass;
-- \d repair_case_roi_embedding
-- SELECT indexname FROM pg_indexes
--  WHERE indexname IN ('ux_fpv_active','ux_emv_active');
-- SELECT COUNT(*) FROM feature_pipeline_version;
-- SELECT COUNT(*) FROM repair_case_damage_feature;
-- SELECT COUNT(*) FROM repair_case_roi_embedding;

-- 되돌리기 주의:
-- feature·embedding 데이터가 생긴 뒤에는 damage_type 값과 NOT NULL 연결을
-- 데이터 이관 없이 되돌릴 수 없다. 아래는 A-5 적재 전 0건일 때만 검토한다.
-- DROP INDEX IF EXISTS ux_fpv_active;
-- DROP INDEX IF EXISTS ux_emv_active;
-- ALTER TABLE repair_case_damage_feature DROP CONSTRAINT IF EXISTS ck_rcdf_type;
-- ALTER TABLE repair_case_damage_feature ADD CONSTRAINT ck_rcdf_type
--     CHECK (damage_type IN ('Scratched','Separated','Crushed','Breakage'));
-- ALTER TABLE repair_case_roi_embedding ALTER COLUMN damage_feature_id DROP NOT NULL;
