import json
import tempfile
import unittest
from pathlib import Path

from load_search_data import class_from_labels, repair_case_image_key


class SearchDataLoaderTest(unittest.TestCase):
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
