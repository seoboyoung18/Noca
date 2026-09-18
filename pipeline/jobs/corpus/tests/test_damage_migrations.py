import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SQL_ROOT = REPO_ROOT / "sql"


class DamageMigrationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = {
            name: (SQL_ROOT / name).read_text(encoding="utf-8")
            for name in (
                "007_damage_feature_layer.sql",
                "008_damage_type_standard_code.sql",
                "010_damage_repair_hint.sql",
                "011_damage_pipeline_v2.sql",
                "015_yolo_part_candidates.sql",
            )
        }

    def test_feature_layer_is_explicitly_preconditioned(self):
        migration = self.sql["007_damage_feature_layer.sql"]
        self.assertIn("CREATE TABLE feature_pipeline_version", migration)
        self.assertIn("CREATE TABLE repair_case_damage_feature", migration)
        self.assertIn("ALTER TABLE repair_case_roi_embedding", migration)
        self.assertIn("damage_feature_id BIGINT", migration)
        self.assertIn("feature_pipeline_version", migration)

    def test_damage_type_hardening_contract(self):
        migration = self.sql["008_damage_type_standard_code.sql"]
        self.assertIn("damage_feature_id SET NOT NULL", migration)
        self.assertIn("ux_fpv_active", migration)
        self.assertIn("ux_emv_active", migration)
        for code in ("SCRATCHED", "SEPARATED", "CRUSHED", "BREAKAGE"):
            self.assertIn(code, migration)

    def test_repair_hints_are_separate_from_feature_part_code(self):
        migration = self.sql["010_damage_repair_hint.sql"]
        canonical = (REPO_ROOT.parent / "Docs" / "Erd" / "A307_ddl_final.sql").read_text(
            encoding="utf-8"
        )
        self.assertIn("repair_case_damage_feature_part_hint", migration)
        self.assertIn("repair_case_damage_feature_part_hint", canonical)
        self.assertIn("damage_feature_id", migration)
        self.assertIn("hint_source = 'REPAIR'", migration)
        self.assertIn("repair_methods", migration)
        self.assertIn("JSONB", migration)
        self.assertIn(
            "UNIQUE (\n        damage_feature_id, part_code, hint_source, source_annotation_ref",
            migration,
        )

    def test_v1_v2_and_v2_params_are_separate_and_inactive(self):
        v2 = self.sql["011_damage_pipeline_v2.sql"]
        self.assertIn("'v2'", v2)
        self.assertIn('"image_source": "DAMAGE"', v2)
        self.assertIn('"part_code_policy": "NULL"', v2)
        self.assertIn('"pair_status": "UNPAIRED"', v2)
        self.assertIn('"repair_hint_source": "REPAIR"', v2)
        self.assertIn("FALSE", v2)
        self.assertIn("ON CONFLICT (pipeline_name, version)", v2)

    def test_yolo_candidates_are_separate_from_hints_and_feature_part_code(self):
        migration = self.sql["015_yolo_part_candidates.sql"]
        canonical = (REPO_ROOT.parent / "Docs" / "Erd" / "A307_ddl_final.sql").read_text(
            encoding="utf-8"
        )
        for table in (
            "repair_case_image_part_inference",
            "repair_case_damage_feature_part_mapping",
            "repair_case_damage_feature_part_candidate",
        ):
            self.assertIn(f"CREATE TABLE IF NOT EXISTS {table}", migration)
            self.assertIn(f"CREATE TABLE {table}", canonical)
        self.assertIn("'SUCCEEDED','PART_NOT_DETECTED','ERROR'", migration)
        self.assertIn("'PAIRED','UNPAIRED','AMBIGUOUS'", migration)
        self.assertIn("REFERENCES part_code(part_code)", migration)
        self.assertNotIn("UPDATE repair_case_damage_feature", migration)
        self.assertNotIn("repair_case_damage_feature_part_hint", migration)



if __name__ == "__main__":
    unittest.main()
