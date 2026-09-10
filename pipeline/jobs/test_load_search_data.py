import json
import tempfile
import unittest
from pathlib import Path

from load_search_data import (
    SEARCH_LABEL_DIRS,
    build_label_index,
    class_from_labels,
    load_case_manifest,
    repair_case_image_key,
)


class SearchDataLoaderTest(unittest.TestCase):
    def test_load_case_manifest_reads_case_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cases.csv"
            path.write_text(
                "case_id,purpose\nas-0000001,DEV\ninvalid,DEV\n",
                encoding="utf-8",
            )

            self.assertEqual(load_case_manifest(path), {"as-0000001"})

    def test_search_label_index_uses_damage_part_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative_dir in SEARCH_LABEL_DIRS:
                label_dir = root / relative_dir
                label_dir.mkdir(parents=True)
                (label_dir / "image_as-0000001.json").write_text("{}", encoding="utf-8")

            damage_dir = root / "1.Training/2.라벨링데이터/TL_damage/damage"
            damage_dir.mkdir(parents=True)
            (damage_dir / "image_as-0000001.json").write_text("{}", encoding="utf-8")

            index = build_label_index(root, {"as-0000001"})

            self.assertEqual(len(index["as-0000001"]), 2)
            self.assertTrue(all("damage_part" in path.parts for path in index["as-0000001"]))

    def test_repair_case_image_key(self):
        self.assertEqual(
            repair_case_image_key(1205, 88421),
            "repair-cases/1205/images/88421/original.jpg",
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


if __name__ == "__main__":
    unittest.main()
