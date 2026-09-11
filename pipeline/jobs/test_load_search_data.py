import json
import tempfile
import unittest
from pathlib import Path

from load_search_data import (
    SEARCH_LABEL_DIRS,
    build_label_index,
    class_from_labels,
    direct_damage_part_annotations,
    image_metadata_for_label,
    load_case_manifest,
    load_damage_part_documents,
    repair_case_image_key,
    upsert_damage_features,
    upsert_estimate_items,
)


class SearchDataLoaderTest(unittest.TestCase):
    class Cursor:
        def __init__(self):
            self.calls = []

        def execute(self, sql, values):
            self.calls.append((sql, values))

    class FeatureCursor:
        def __init__(self, case_image_id=101):
            self.calls = []
            self.case_image_id = case_image_id

        def execute(self, sql, values):
            self.calls.append((sql, values))

        def fetchone(self):
            sql = self.calls[-1][0]
            if "SELECT case_image_id" in sql:
                return (self.case_image_id,)
            if "RETURNING damage_feature_id" in sql:
                return (900 + len(self.calls),)
            return None

    def test_load_case_manifest_reads_case_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cases.csv"
            path.write_text(
                "case_id,purpose\nas-0000001,DEV\ninvalid,DEV\n",
                encoding="utf-8",
            )

            self.assertEqual(load_case_manifest(path), {"as-0000001"})

    def test_load_case_manifest_rejects_demo_or_eval_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cases.csv"
            path.write_text(
                "case_id,purpose\nas-0000001,DEV\nas-0000002,EVAL\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "purpose=DEV"):
                load_case_manifest(path)

    def test_search_label_index_includes_damage_and_damage_part(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative_dir in SEARCH_LABEL_DIRS:
                label_dir = root / relative_dir
                label_dir.mkdir(parents=True)
                (label_dir / "image_as-0000001.json").write_text("{}", encoding="utf-8")

            index = build_label_index(root, {"as-0000001"})

            self.assertEqual(len(index["as-0000001"]), 4)
            self.assertEqual(
                {"damage" in path.parts for path in index["as-0000001"]}, {True, False}
            )

    def test_repair_case_image_key(self):
        self.assertEqual(
            repair_case_image_key(1205, 88421),
            "repair-cases/1205/images/88421/original.jpg",
        )

    def test_image_metadata_preserves_damage_role(self):
        self.assertEqual(
            image_metadata_for_label(Path("1.Training/2.라벨링데이터/TL_damage/damage/x_as-0000001.json")),
            ("DAMAGE", "TRAIN"),
        )

    def test_estimate_items_use_case_scoped_idempotency_key(self):
        cursor = self.Cursor()
        estimate = {"수리내역": [
            {"작업항목 및 부품명": "Front bumper", "작업": "도장", "부품가격": "1000", "공임": "2000"}
        ]}
        first = upsert_estimate_items(cursor, 11, estimate, "AIHUB_AS", {"Front bumper": "FRONT_BUMPER"})
        second = upsert_estimate_items(cursor, 11, estimate, "AIHUB_AS", {"Front bumper": "FRONT_BUMPER"})

        self.assertEqual(first[0], 1)
        self.assertEqual(second[0], 1)
        self.assertEqual(len(cursor.calls), 2)
        self.assertTrue(all("ON CONFLICT (case_id, source_item_key)" in sql for sql, _ in cursor.calls))
        self.assertTrue(all(values[1] == "item-00001" for _, values in cursor.calls))
        self.assertTrue(all(values[2] == "FRONT_BUMPER" for _, values in cursor.calls))

    def test_damage_part_annotation_is_not_attached_to_damage_label(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            damage = root / "1.Training/2.라벨링데이터/TL_damage/damage/x_as-0000001.json"
            context = root / "1.Training/2.라벨링데이터/TL_damage_part/damage_part/x_as-0000001.json"
            damage.parent.mkdir(parents=True)
            context.parent.mkdir(parents=True)
            payload = {"annotations": [{"id": 7, "part": "Front bumper", "bbox": [1, 2, 3, 4]}]}
            damage.write_text(json.dumps(payload), encoding="utf-8")
            context.write_text(json.dumps(payload), encoding="utf-8")

            self.assertEqual(direct_damage_part_annotations(damage), [])
            self.assertEqual(direct_damage_part_annotations(context), [("FRONT_BUMPER", "7", [1, 2, 3, 4])])
        self.assertEqual(
            image_metadata_for_label(Path("2.Validation/2.라벨링데이터/VL_damage_part/damage_part/x_as-0000001.json")),
            ("DAMAGE_PART", "VALIDATION"),
        )
        self.assertEqual(
            repair_case_image_key(1205, 88421, "thumbnail", ".JPG"),
            "repair-cases/1205/images/88421/thumbnail.jpg",
        )

    def test_class_from_labels_requires_consistent_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            labels = []
            for index, car_class in enumerate(("Compact", "Compact car")):
                path = root / f"{index}.json"
                path.write_text(
                    json.dumps({"categories": {"supercategory_name": car_class}}),
                    encoding="utf-8",
                )
                labels.append(path)

            self.assertEqual(class_from_labels(labels), ("Compact", None))

    def test_class_from_labels_rejects_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            labels = []
            for index, car_class in enumerate(("Compact", "Mid-size")):
                path = root / f"{index}.json"
                path.write_text(
                    json.dumps({"categories": {"supercategory_name": car_class}}),
                    encoding="utf-8",
                )
                labels.append(path)

            car_class, reason = class_from_labels(labels)
            self.assertIsNone(car_class)
            self.assertIn("car_class 불일치", reason)

    def test_upsert_damage_features_writes_pairing_states_and_original_indexes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            label = root / (
                "1.Training/2.라벨링데이터/TL_damage_part/damage_part/"
                "fixture_as-0000001.json"
            )
            label.parent.mkdir(parents=True)
            label.write_text(json.dumps({
                "images": {"width": 800, "height": 600},
                "annotations": [
                    # 이 non-damage annotation도 roi_index 0을 소비한다.
                    {"id": 10, "part": "Front bumper", "bbox": [500, 10, 100, 100]},
                    # INVALID damage도 roi_index 1을 소비하지만 feature 행은 만들지 않는다.
                    {"id": 16, "damage": "Separated", "bbox": [500, 10, 0, 100]},
                    # PAIRED -> 원본 배열 index 2
                    {"id": 11, "damage": "Scratched", "bbox": [500, 10, 100, 100]},
                    # UNPAIRED -> 원본 배열 index 3
                    {"id": 12, "damage": "Crushed", "bbox": [10, 10, 100, 100]},
                    # AMBIGUOUS -> 원본 배열 index 4
                    {"id": 13, "damage": "Breakage", "bbox": [300, 10, 100, 100]},
                    {"id": 14, "part": "Front bumper", "bbox": [300, 10, 100, 100]},
                    {"id": 15, "part": "Bonnet", "bbox": [300, 10, 100, 100]},
                ],
            }), encoding="utf-8")

            cursor = self.FeatureCursor()
            documents = load_damage_part_documents([label])
            counts = upsert_damage_features(
                cursor, 7, [label], root, 1, documents=documents
            )

            self.assertEqual(counts["damage_feature_count"], 3)
            self.assertEqual(counts["paired_roi_count"], 1)
            self.assertEqual(counts["unpaired_damage_count"], 1)
            self.assertEqual(counts["ambiguous_part_match_count"], 1)
            self.assertEqual(counts["missing_part_count"], 1)
            self.assertEqual(counts["vector_only_roi_count"], 2)

            inserts = [call for call in cursor.calls if "INSERT INTO repair_case_damage_feature" in call[0]]
            self.assertEqual(len(inserts), 3)
            values_by_index = {values[2]: values for _, values in inserts}
            self.assertEqual(set(values_by_index), {2, 3, 4})
            self.assertEqual(values_by_index[2][3], "SCRATCHED")
            self.assertEqual(values_by_index[2][6], "FRONT_BUMPER")
            self.assertEqual(values_by_index[2][7], "PAIRED")
            self.assertIsNone(values_by_index[3][6])
            self.assertEqual(values_by_index[3][7], "UNPAIRED")
            self.assertIsNone(values_by_index[4][6])
            self.assertEqual(values_by_index[4][7], "AMBIGUOUS")
            self.assertIn('"x": 480', values_by_index[2][5])

    def test_upsert_damage_features_requires_pipeline_version_id_before_db_access(self):
        cursor = self.FeatureCursor()
        with self.assertRaisesRegex(ValueError, "pipeline_version_id"):
            upsert_damage_features(cursor, 7, [], Path("."), None)
        self.assertEqual(cursor.calls, [])


if __name__ == "__main__":
    unittest.main()
