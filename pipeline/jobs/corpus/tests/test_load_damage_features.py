import json
import tempfile
import unittest
from pathlib import Path

from pipeline.jobs.ingestion.load_damage_features import (
    repair_hints,
    upsert_damage_case,
)


class DamageFeatureLoaderTest(unittest.TestCase):
    class Cursor:
        def __init__(self):
            self.calls = []
            self.next_row = None

        def execute(self, sql, values):
            self.calls.append((sql, values))
            if "SELECT i.case_image_id" in sql:
                self.next_row = (701,)
            elif "INSERT INTO repair_case_damage_feature(" in sql:
                self.next_row = (801,)
            else:
                self.next_row = None

        def fetchone(self):
            return self.next_row

    def test_repair_hints_keep_multiple_parts_and_merge_methods(self):
        hints, unknown_count = repair_hints({
            "repair": [
                "Front bumper:repair,coating",
                "Front bumper:exchange",
                "Rear bumper:coating",
                "not-a-repair-label",
            ]
        })

        self.assertEqual(unknown_count, 1)
        self.assertEqual({hint["part_code"] for hint in hints}, {"FRONT_BUMPER", "REAR_BUMPER"})
        front = next(hint for hint in hints if hint["part_code"] == "FRONT_BUMPER")
        self.assertEqual(front["repair_methods"], ["REPAIR", "COATING", "EXCHANGE"])

    def test_damage_feature_has_null_part_and_unpaired_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subset_root = root / "subset"
            dataset_root = root / "dataset"
            label = subset_root / (
                "1.Training/2.라벨링데이터/TL_damage/damage/"
                "0406472_as-0000001.json"
            )
            label.parent.mkdir(parents=True)
            label.write_text(json.dumps({
                "images": {"width": 800, "height": 600},
                "annotations": [{
                    "id": 17,
                    "damage": "Scratched",
                    "bbox": [100, 100, 200, 150],
                    "repair": ["Front bumper:repair,coating", "Rear bumper:exchange"],
                }],
            }), encoding="utf-8")
            image = dataset_root / (
                "1.Training/1.원천데이터/TS_damage/damage/"
                "0406472_as-0000001.jpg"
            )
            image.parent.mkdir(parents=True)
            image.write_bytes(b"jpg")

            cursor = self.Cursor()
            counts = upsert_damage_case(
                cursor,
                "as-0000001",
                [label],
                dataset_root=dataset_root,
                subset_root=subset_root,
                pipeline_version_id=2,
            )

            feature_inserts = [
                call for call in cursor.calls
                if "INSERT INTO repair_case_damage_feature(" in call[0]
            ]
            hint_inserts = [
                call for call in cursor.calls
                if "INSERT INTO repair_case_damage_feature_part_hint" in call[0]
            ]
            self.assertEqual(len(feature_inserts), 1)
            self.assertIn("NULL,'UNPAIRED'", feature_inserts[0][0])
            self.assertEqual(len(hint_inserts), 2)
            self.assertEqual(counts["damage_feature_count"], 1)
            self.assertEqual(counts["repair_hint_count"], 2)
            self.assertEqual(counts["multi_part_hint_count"], 1)


if __name__ == "__main__":
    unittest.main()
