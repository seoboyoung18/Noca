-- A307 DAMAGE corpus feature pipeline v2
-- v1(DAMAGE_PART)은 유지하고 v2는 생성만 한다. 활성화는 운영 검증 후 수동으로 한다.

BEGIN;

INSERT INTO feature_pipeline_version(
    pipeline_name, version, pair_rule_version, pair_threshold,
    roi_padding_ratio, params, is_active
)
VALUES (
    'a307-damage-search', 'v2', 'damage-bbox-v1', 0.50,
    0.20,
    '{
      "image_source": "DAMAGE",
      "part_code_policy": "NULL",
      "pair_status": "UNPAIRED",
      "repair_hint_source": "REPAIR",
      "searchability": ["VECTOR_ONLY"]
    }'::jsonb,
    FALSE
)
ON CONFLICT (pipeline_name, version) DO UPDATE SET
    pair_rule_version = EXCLUDED.pair_rule_version,
    pair_threshold = EXCLUDED.pair_threshold,
    roi_padding_ratio = EXCLUDED.roi_padding_ratio,
    params = EXCLUDED.params;

COMMIT;
